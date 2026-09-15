package io.github.demianli.projectmcp.gh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.type;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests the process machinery under real subprocess conditions.
 *
 * <p>No network and no real {@code gh}. These tests keep the spawn, pipes, exit codes and
 * timeout real so {@link GhCli}'s actual behavior is observed, not mocked. They exercise
 * machinery that {@link GhStderr} cannot (which tests as strings). Each test needs a real
 * subprocess.
 *
 * <p>The write route reclassifies three specific exits; a non-zero exit must be classified
 * the same way on both routes to prove which path was taken.
 *
 * <p>One branch is unreachable: the {@code ExecutionException} arm when {@code readAllBytes}
 * throws. A stand-in binary cannot trigger this — a killed process gives EOF. Testing it
 * would require seaming {@link ProcessBuilder}, which {@link GhCli}'s javadoc rejects because
 * that is what makes the absent binary, timeout and full pipe scenarios testable at all.
 */
class GhCliFailureTest {

    @TempDir Path tmp;

    private static GhCli pointingAt(String executable) {
        return new GhCli(executable, 30);
    }

    @Test
    void timeoutOnAReadIsRetryAndCarriesNoWait() throws Exception {
        // "exit 0" after the sleep stops shell exec-optimization so sleep is genuinely
        // a grandchild holding the pipe open, reproducing reliably across shells.
        GhCli gh = new GhCli(FakeGh.writing(tmp, "sleep 30\nexit 0"), 1);
        long start = System.nanoTime();
        assertThatThrownBy(() -> gh.run(List.of("issue", "list")))
                .asInstanceOf(type(ToolFailure.class))
                .satisfies(f -> {
                    assertThat(f.remedy()).isEqualTo(Remedy.RETRY);
                    assertThat(f.retryAfterSeconds()).isNull();
                    assertThat(f.getMessage()).contains("within 1 seconds");
                });
        assertThat(Duration.ofNanos(System.nanoTime() - start).toMillis())
                .as("timeout actually waited rather than reporting without taking the time")
                .isBetween(900L, 5000L);
    }

    @Test
    void timeoutOnAWriteSaysToCheckRatherThanToRetry() throws Exception {
        GhCli gh = new GhCli(FakeGh.writing(tmp, "sleep 30\nexit 0"), 1);
        assertThatThrownBy(() -> gh.runWrite(List.of("api", "graphql", "-f", "query=x")))
                .asInstanceOf(type(ToolFailure.class))
                .satisfies(f -> {
                    assertThat(f.remedy()).isEqualTo(Remedy.CHECK_BEFORE_RETRY);
                    assertThat(f.retryAfterSeconds()).isNull();
                    assertThat(f.getMessage())
                            .as("includes the timeout cause, then guidance that the write "
                                    + "may have partially succeeded")
                            .contains("within 1 seconds. The comment could not be "
                                    + "confirmed.")
                            .contains("list_issue_comments");
                });
    }

    @Test
    void interruptionIsRetryOnAReadAndCheckOnAWrite() throws Exception {
        // Pre-set the flag before the call to avoid guessing an interrupt window on a second
        // thread. Shell is `sleep 1 & exit 0` so the exit returns immediately while the
        // backgrounded sleep holds the pipe open, exercising both the waitFor and Future.get
        // code paths that could notice the interrupted flag.
        String slow = FakeGh.writing(tmp, "sleep 1 & exit 0");
        long start = System.nanoTime();

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> new GhCli(slow, 30).run(List.of("issue", "list")))
                    .asInstanceOf(type(ToolFailure.class))
                    .satisfies(f -> {
                        assertThat(f.remedy()).isEqualTo(Remedy.RETRY);
                        assertThat(f.getMessage()).doesNotContain("list_issue_comments");
                    });
        } finally {
            // GhCli re-sets the flag on its way out, which is correct and must not leak
            // to subsequent tests.
            assertThat(Thread.interrupted()).isTrue();
        }

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(
                    () -> new GhCli(slow, 30).runWrite(List.of("api", "graphql", "-f", "query=x")))
                    .asInstanceOf(type(ToolFailure.class))
                    .satisfies(f -> {
                        assertThat(f.remedy()).isEqualTo(Remedy.CHECK_BEFORE_RETRY);
                        assertThat(f.getMessage())
                                .contains("interrupted")
                                .contains("list_issue_comments");
                    });
        } finally {
            assertThat(Thread.interrupted()).isTrue();
        }

        assertThat(Duration.ofNanos(System.nanoTime() - start).toSeconds())
                .as("an interrupt returns immediately without waiting for the abandoned process")
                .isLessThan(10);
    }

    @Test
    void aWriteThatFailsForAnyOtherReasonIsClassifiedExactlyAsAReadWouldBe() throws Exception {
        // The write route changes only the three exits that abandon the call without
        // learning the outcome. A non-zero exit gives gh's answer, so classification is
        // independent of the route.
        GhCli gh = new GhCli(FakeGh.failing(tmp,
                "GraphQL: Could not resolve to a Repository with the name 'a/b'. (repository)"),
                30);
        assertThatThrownBy(() -> gh.runWrite(List.of("api", "graphql", "-f", "query=x")))
                .asInstanceOf(type(ToolFailure.class))
                .satisfies(f -> {
                    assertThat(f.remedy()).isEqualTo(Remedy.FIX_REQUEST);
                    assertThat(f.getMessage()).doesNotContain("list_issue_comments");
                });
    }

    @Test
    void anAbsentBinaryIsAskOperatorAndCarriesNoStderr() {
        // An absent binary never reaches a non-zero exit and produces no stderr. The path
        // truly does not exist so the IOException comes from ProcessBuilder.start() itself.
        GhCli gh = pointingAt(tmp.resolve("no-such-gh").toString());
        assertThatThrownBy(() -> gh.run(List.of("issue", "list")))
                .asInstanceOf(type(ToolFailure.class))
                .satisfies(f -> {
                    assertThat(f.remedy()).isEqualTo(Remedy.ASK_OPERATOR);
                    assertThat(f.stderr()).isEmpty();
                    assertThat(f.getMessage()).contains("not installed");
                });
    }

    @Test
    void stderrLargerThanThePipeBufferDoesNotDeadlock() throws Exception {
        // Tests concurrent draining to prevent deadlock when stderr exceeds the pipe buffer.
        // Only a real process can fill a real buffer, which is why the seam is at the
        // executable name rather than lower.
        GhCli gh = pointingAt(FakeGh.writing(tmp,
                "i=0; while [ $i -lt 4000 ]; do echo 'noise noise noise noise' >&2; "
                        + "i=$((i+1)); done; echo '[]'"));
        assertThat(gh.run(List.of("issue", "list"))).isEqualTo("[]\n");
    }

    @Test
    void successReturnsStdoutUntouched() throws Exception {
        GhCli gh = pointingAt(FakeGh.writing(tmp, "echo '[{\"number\":1}]'"));
        assertThat(gh.run(List.of("issue", "list"))).isEqualTo("[{\"number\":1}]\n");
    }
}

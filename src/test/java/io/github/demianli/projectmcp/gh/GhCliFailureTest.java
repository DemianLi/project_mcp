package io.github.demianli.projectmcp.gh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.type;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Coverage layer: every Remedy, walked without crossing the wire — the four of ADR-0002 and
 * the fifth ADR-0008 added for writes.
 *
 * <p>No network and no real {@code gh}. What is <em>not</em> substituted is the point — the
 * spawn, the pipes, the exit code and the timeout stay real, so these exercise
 * {@link GhCli}'s machinery rather than a description of it.
 *
 * <p>One branch has no test and cannot get one here: the {@code ExecutionException} arm,
 * which ADR-0008 also reclassifies on a write. It fires when {@code readAllBytes} throws,
 * and a stand-in binary cannot make it — a killed process gives EOF, not an
 * {@link java.io.IOException}. Reaching it would mean putting the seam in front of
 * {@link ProcessBuilder}, which is what {@link GhCli}'s own javadoc argues against, because
 * that is what makes the absent binary, the timeout and the full pipe buffer above testable
 * at all. Recorded as a gap rather than paid for. See issue #31.
 */
class GhCliFailureTest {

    @TempDir Path tmp;

    private static GhCli pointingAt(String executable) {
        return new GhCli(executable, 30);
    }

    private ToolFailure failure(String stderr) throws IOException {
        try {
            pointingAt(FakeGh.failing(tmp, stderr)).run(List.of("issue", "list"));
        } catch (ToolFailure e) {
            return e;
        }
        throw new AssertionError("expected a ToolFailure");
    }

    @Test
    void networkUnreachableIsRetry() throws Exception {
        ToolFailure f = failure(
                "Post \"https://api.github.com/graphql\": dial tcp: connect: connection refused");
        assertThat(f.remedy()).isEqualTo(Remedy.RETRY);
        assertThat(f.retryAfterSeconds()).isNull();
        assertThat(f.stderr()).contains("connection refused");
    }

    @Test
    void rateLimitIsRetryAndKeepsTheWaitWhenGhNamesOne() throws Exception {
        assertThat(failure("You have exceeded a secondary rate limit").remedy())
                .isEqualTo(Remedy.RETRY);

        ToolFailure withWait = failure("API rate limit exceeded. Please retry after 60 seconds.");
        assertThat(withWait.remedy()).isEqualTo(Remedy.RETRY);
        assertThat(withWait.retryAfterSeconds()).isEqualTo(60);
    }

    @Test
    void rateLimitWithoutAStatedWaitLeavesItUnset() throws Exception {
        // gh's rate-limit wording is unverified: it could not be provoked against the real
        // API. The contract is built so that not knowing it costs the wait, not the
        // classification.
        assertThat(failure("API rate limit exceeded").retryAfterSeconds()).isNull();
    }

    @Test
    void timeoutOnAReadIsRetryAndCarriesNoWait() throws Exception {
        // "exit 0" after the sleep stops the shell exec-optimising it away, so `sleep`
        // is genuinely a grandchild holding the same pipe open. Without that this
        // reproduces on some shells and not others -- it passed on macOS and took the
        // full 30s on CI.
        GhCli gh = new GhCli(FakeGh.writing(tmp, "sleep 30\nexit 0"), 1);
        long start = System.nanoTime();
        assertThatThrownBy(() -> gh.run(List.of("issue", "list")))
                .asInstanceOf(type(ToolFailure.class))
                .satisfies(f -> {
                    assertThat(f.remedy()).isEqualTo(Remedy.RETRY);
                    // It used to report the budget it spent. ADR-0008 took that away: the
                    // field means "do not retry before this", so a Client obeying it waited
                    // out the timeout and then called again -- harmless on a read and the
                    // duplicate itself on a write.
                    assertThat(f.retryAfterSeconds()).isNull();
                    assertThat(f.getMessage()).contains("within 1 seconds");
                });
        assertThat(Duration.ofNanos(System.nanoTime() - start).toMillis())
                .as("it really waited, rather than reporting a timeout it never took")
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
                            .as("the same first sentence a read gets, so the cause is not "
                                    + "lost, then the ADR-0008 wording -- asserted across "
                                    + "the join, because the space that makes it prose "
                                    + "lives at the head of a constant and a reformat "
                                    + "would eat it silently")
                            .contains("within 1 seconds. The comment could not be "
                                    + "confirmed.")
                            .contains("list_issue_comments");
                });
    }

    @Test
    void interruptionIsRetryOnAReadAndCheckOnAWrite() throws Exception {
        // Setting the flag before the call and letting the call find it, rather than
        // interrupting from a second thread into a window that would have to be guessed at.
        //
        // The shell is `sleep 1 & exit 0` for a measured reason. Two places on this path can
        // notice the flag -- waitFor, and the Future.get that follows it -- and which one
        // does is not predictable: the same pre-set flag threw out of waitFor immediately in
        // a stripped-down harness and only when the process ended (30s) through GhCli. So
        // the fake exits at once, and a backgrounded sleep holds the stdout pipe open so
        // that get() is still blocking when it is reached. Both orders throw, and neither
        // costs 30 seconds.
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
            // GhCli re-sets the flag on its way out, which is correct of it and would leak
            // into whatever runs next on this thread.
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
                .as("an interrupt returns at once; it does not wait out the process it "
                        + "abandoned")
                .isLessThan(10);
    }

    @Test
    void aWriteThatFailsForAnyOtherReasonIsClassifiedExactlyAsAReadWouldBe() throws Exception {
        // The write route changes the three exits that abandon the call without learning
        // what it did, and nothing else. A non-zero exit is `gh` telling us what happened,
        // so classify() has the answer and the route is irrelevant.
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
    void noSuchIssueNumberIsFixRequest() throws Exception {
        // Reachable only once a Tool takes a number, which get_issue is the first to do.
        // Before it, this stderr fell through to UNKNOWN. Captured verbatim from
        // `gh issue view 9999`.
        ToolFailure f = failure("GraphQL: Could not resolve to an issue or pull request "
                + "with the number of 9999. (repository.issue)");
        assertThat(f.remedy()).isEqualTo(Remedy.FIX_REQUEST);
        assertThat(f.getMessage())
                .as("the caller is told which parameter to change")
                .contains("number");
    }

    @Test
    void theGraphqlWordingForTheSameThingIsAlsoFixRequest() throws Exception {
        // The same condition down the other route, and it does not match the branch above:
        // `gh api graphql` says "an Issue with the number of", singular and without the
        // "or pull request" clause. Captured verbatim; before ADR-0005 this fell through
        // to UNKNOWN. It is the whole reason list_issue_comments could not simply inherit
        // the failure contract unchanged.
        ToolFailure f = failure("gh: Could not resolve to an Issue with the number of 14362.");
        assertThat(f.remedy()).isEqualTo(Remedy.FIX_REQUEST);
        assertThat(f.getMessage())
                .as("one sentence covers both causes, because the action is the same")
                .contains("pull request");
    }

    @Test
    void theTwoIssueWordingsDoNotDisturbEachOther() throws Exception {
        // get_issue and list_issues depend on the porcelain wording, so the branch added
        // for GraphQL must not swallow it. Neither string contains the other, and this
        // pins that rather than leaving it to a reading of the chain.
        String porcelain = "GraphQL: Could not resolve to an issue or pull request with the "
                + "number of 9999. (repository.issue)";
        String graphql = "gh: Could not resolve to an Issue with the number of 14362.";

        assertThat(failure(porcelain).getMessage())
                .as("porcelain still gets the sentence that can promise there is no pull "
                        + "request with that number either -- true there, false on GraphQL")
                .contains("no pull request with it either");
        assertThat(failure(graphql).getMessage())
                .doesNotContain("no pull request with it either");
    }

    @Test
    void anUnusableCursorIsFixRequest() throws Exception {
        // Cursors wraps the cursor a Client is given and refuses one from the wrong issue
        // before `gh` is called. It cannot refuse a correctly-addressed wrapper whose
        // inner half is corrupt: that reaches GitHub, and this is what comes back.
        // Captured verbatim.
        ToolFailure f = failure("gh: `not-a-cursor` does not appear to be a valid cursor.");
        assertThat(f.remedy()).isEqualTo(Remedy.FIX_REQUEST);
        assertThat(f.getMessage()).contains("cursor");
    }

    @Test
    void repoNotFoundIsFixRequest() throws Exception {
        assertThat(failure("GraphQL: Could not resolve to a Repository with the name 'a/b'. "
                + "(repository)").remedy()).isEqualTo(Remedy.FIX_REQUEST);
    }

    @Test
    void malformedRepoSlugIsFixRequest() throws Exception {
        assertThat(failure("expected the \"[HOST/]OWNER/REPO\" format, got \"notavalidthing\"")
                .remedy()).isEqualTo(Remedy.FIX_REQUEST);
    }

    @Test
    void issuesDisabledIsFixRequest() throws Exception {
        assertThat(failure("the 'torvalds/linux' repository has disabled issues").remedy())
                .isEqualTo(Remedy.FIX_REQUEST);
    }

    @Test
    void badCredentialsIsAskOperator() throws Exception {
        assertThat(failure("HTTP 401: Bad credentials (https://api.github.com/graphql)\n"
                + "Try authenticating with:  gh auth login").remedy())
                .isEqualTo(Remedy.ASK_OPERATOR);
    }

    @Test
    void anAbsentBinaryIsAskOperatorAndCarriesNoStderr() {
        // The asymmetry ADR-0002 names: this never reaches a non-zero exit. The path below
        // genuinely does not exist, so the IOException is the real one from the real
        // ProcessBuilder.start() rather than one a test invented.
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
    void unrecognisedStderrIsUnknownAndSurvivesVerbatim() throws Exception {
        // The usage blob gh prints for a bad flag. Unreachable through the Tool's typed
        // surface, so it can only mean a bug in this Server — ADR-0002 gives it no Remedy of
        // its own, and this is where it lands instead.
        String blob = "unknown flag: --banana\n\nUsage:  gh issue list [flags]\n\nFlags:\n"
                + "      --app string         Filter by GitHub App author";
        ToolFailure f = failure(blob);
        assertThat(f.remedy()).isEqualTo(Remedy.UNKNOWN);
        assertThat(f.stderr()).isEqualTo(blob);
    }

    @Test
    void stderrLargerThanThePipeBufferDoesNotDeadlock() throws Exception {
        // The single bug GhCli's concurrent draining exists to prevent. Only a real process
        // can fill a real pipe buffer, which is why the seam sits at the executable name and
        // no deeper.
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

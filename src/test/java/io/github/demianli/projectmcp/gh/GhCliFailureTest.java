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
 * 在真正的子行程條件下測試行程處理機制。
 *
 * <p>沒有網路，也沒有真正的 {@code gh}。這些測試讓啟動行程、pipe、結束碼與逾時都是
 * 真的，觀察 {@link GhCli} 的實際行為而不是 mock。它們涵蓋 {@link GhStderr}（以字串
 * 測試）測不到的機制，每個測試都需要真正的子行程。
 *
 * <p>寫入路徑會重新分類三種特定的結束方式；非零結束在兩條路徑上必須分類相同，以此對照
 * 才能證明實際走了哪條路徑。
 *
 * <p>有一個分支測不到：{@code readAllBytes} 拋出例外時的 {@code ExecutionException}
 * 分支。替身執行檔無法觸發它，因為被終止的行程只會給出 EOF。要測它就得替
 * {@link ProcessBuilder} 開接縫；不這麼做，是因為直接使用 ProcessBuilder，「執行檔
 * 不存在」、逾時與 pipe 塞滿的情境才測得到。
 */
class GhCliFailureTest {

    @TempDir Path tmp;

    private static GhCli pointingAt(String executable) {
        return new GhCli(executable, 30);
    }

    @Test
    void timeoutOnAReadIsRetryAndCarriesNoWait() throws Exception {
        // sleep 後的 "exit 0" 阻止 shell 的 exec 最佳化，讓 sleep 確實成為握著 pipe 的
        // 孫行程，在各種 shell 上都能穩定重現。
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
        // 呼叫前先設好中斷旗標，不必在另一個執行緒上猜中斷時機。shell 內容是
        // `sleep 1 & exit 0`：exit 立即返回，背景的 sleep 則握著 pipe，於是 waitFor 與
        // Future.get 兩條可能察覺中斷旗標的程式路徑都會執行到。
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
            // GhCli 返回時會重新設定中斷旗標，這是正確的行為，但不能洩漏到後續測試。
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
        // 寫入路徑只改變三種「沒得知結果就放棄呼叫」的結束方式。非零結束帶有 gh 的回答，
        // 所以分類與路徑無關。
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
        // 執行檔不存在時不會有非零結束，也沒有 stderr。路徑確實不存在，
        // 所以 IOException 直接來自 ProcessBuilder.start()。
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
        // 驗證兩個 pipe 同時讀取，避免 stderr 超過 pipe 緩衝區時死結。
        // 只有真正的行程能塞滿真正的緩衝區，所以接縫設在執行檔名稱，而不是更底層。
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

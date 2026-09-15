package io.github.demianli.projectmcp.gh;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 執行 {@code gh} 並回傳其 stdout。
 *
 * <p>這是 Server 通往 GitHub 的唯一途徑，沒有 REST 或 GraphQL client。認證完全交給
 * {@code gh}：這個 class 從不讀取 token。
 *
 * <p>參數以獨立的 argv 元素傳入，不經 shell。stderr 的分類由 {@link GhStderr} 負責，
 * 而且只在這一處讀取 stderr。每個 Tool 只要呼叫 {@link #run} 或 {@link #runWrite}
 * 就遵守失敗契約，不需要各自的程式碼。
 *
 * <p>讀取時，逾時或 pipe 無法讀取代表什麼都沒發生，可直接重試；寫入時，同樣的結束方式
 * 代表結果未確認，須先檢查再重試。Tool 以選用 {@link #run} 或 {@link #runWrite} 表明
 * 是哪一種。
 */
@Component
public class GhCli {

    private static final Logger log = LoggerFactory.getLogger(GhCli.class);

    /**
     * 單次 {@code gh} 呼叫的逾時秒數，屬於失敗契約的一部分，見 docs/design.md#bounds。
     * 雙參數建構子可覆寫此預設值。
     */
    static final int TIMEOUT_SECONDS = 30;

    /**
     * 單次 {@code gh} 呼叫接受的最大位元組數，超過時以 {@link Remedy#FIX_REQUEST} 拒絕。
     * 見 docs/design.md#bounds。
     */
    static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

    private final String executable;
    private final int timeoutSeconds;

    public GhCli() {
        this("gh", TIMEOUT_SECONDS);
    }

    /**
     * 讓測試指向替身執行檔並縮短逾時。設為 public，因為這是真正的設定點：{@code gh} 裝在
     * 其他位置的部署也能使用。
     */
    public GhCli(String executable, int timeoutSeconds) {
        this.executable = executable;
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * 寫入無法確認時告訴呼叫者的訊息。
     */
    private static final String CHECK_INSTEAD_OF_RETRYING =
            " The comment could not be confirmed. It may already have been posted. Before "
                    + "writing it again, check with `list_issue_comments` whether a comment "
                    + "of yours with this body is already on the issue.";

    /**
     * 執行只讀取的 {@code gh} 呼叫，回傳其 stdout。
     *
     * @throws ToolFailure {@code gh} 不存在、以非零結束或超過逾時時
     */
    public String run(List<String> args) {
        return run(args, false);
    }

    /**
     * 執行會變更內容的 {@code gh} 呼叫，回傳其 stdout。
     *
     * <p>寫入未確認時（逾時、中斷、pipe 無法讀取），呼叫者得到
     * {@link Remedy#CHECK_BEFORE_RETRY} 而非 {@link Remedy#RETRY}。見 docs/design.md#writes。
     *
     * @throws ToolFailure 與 {@link #run} 相同的各種失敗，其中三種未確認寫入的結束方式
     *     改為上述分類
     */
    public String runWrite(List<String> args) {
        return run(args, true);
    }

    private String run(List<String> args, boolean write) {
        List<String> command = new ArrayList<>(args.size() + 1);
        command.add(executable);
        command.addAll(args);

        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException e) {
            // 不是非零結束：行程根本沒有啟動。這是唯一沒有 stderr 可分類的失敗，
            // 所以在這裡依其路徑分類。
            throw failure(command, new ToolFailure(Remedy.ASK_OPERATOR,
                    "The GitHub CLI (`gh`) could not be started. It is probably not "
                            + "installed, or not on this Server's PATH.",
                    "", null));
        }

        // 兩個 pipe 必須同時讀取。若 stderr 塞滿緩衝區時還在把 stdout 讀到底，就會死結：
        // `gh` 卡在寫入、永不結束，Tool 呼叫懸住且沒有任何錯誤可回報。
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<byte[]> stdout = executor.submit(() -> process.getInputStream().readAllBytes());
            Future<byte[]> stderr = executor.submit(() -> process.getErrorStream().readAllBytes());

            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                kill(process);
                stdout.cancel(true);
                stderr.cancel(true);
                throw failure(command, new ToolFailure(
                        write ? Remedy.CHECK_BEFORE_RETRY : Remedy.RETRY,
                        "The GitHub CLI did not answer within " + timeoutSeconds + " seconds."
                                + (write ? CHECK_INSTEAD_OF_RETRYING : ""),
                        "", null));
            }

            byte[] out = stdout.get();
            String err = new String(stderr.get(), StandardCharsets.UTF_8).strip();

            // 先分類 stderr 再檢查大小：失敗原因比位元組數更有價值。
            if (process.exitValue() != 0) {
                throw failure(command, GhStderr.classify(err));
            }
            if (out.length > MAX_RESPONSE_BYTES) {
                throw failure(command, tooLarge(out.length));
            }
            return new String(out, StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            kill(process);
            throw failure(command, new ToolFailure(
                    write ? Remedy.CHECK_BEFORE_RETRY : Remedy.RETRY,
                    "The GitHub CLI call was interrupted before it finished."
                            + (write ? CHECK_INSTEAD_OF_RETRYING : ""),
                    "", null));
        } catch (ExecutionException e) {
            kill(process);
            throw failure(command, new ToolFailure(
                    write ? Remedy.CHECK_BEFORE_RETRY : Remedy.UNKNOWN,
                    "The output of the GitHub CLI could not be read."
                            + (write ? CHECK_INSTEAD_OF_RETRYING : ""),
                    String.valueOf(e.getCause()), null));
        }
    }

    /**
     * 回應超過 {@link #MAX_RESPONSE_BYTES} 時的失敗，一律為 {@link Remedy#FIX_REQUEST}。
     * 這個失敗由本 class 產生：{@code gh} 已成功，但 Server 拒絕承載這個回應。
     */
    private static ToolFailure tooLarge(int bytes) {
        return new ToolFailure(Remedy.FIX_REQUEST,
                "GitHub returned " + bytes + " bytes, over this Server's limit of "
                        + MAX_RESPONSE_BYTES + ". Nothing was lost and nothing was changed; "
                        + "the response was refused rather than carried. If this Tool takes "
                        + "a `limit`, ask for fewer items, or page with a cursor. If it does "
                        + "not, this Tool cannot return this particular subject.",
                "", null);
    }

    /**
     * 終止行程及其衍生的所有行程。只用 {@link Process#destroyForcibly()} 只會終止直接子行程；
     * 孫行程仍握著 pipe，{@code readAllBytes} 會永遠阻塞，逾時也永遠不會觸發。
     */
    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    /**
     * 記錄 argv，並回傳要拋出的失敗。
     *
     * <p>argv 是刻意不放進回傳內容的診斷資訊，所以保留在這裡：它只寫入 log 檔。console
     * appender 已關閉，因為 stdout 屬於 JSON-RPC。
     */
    private static ToolFailure failure(List<String> command, ToolFailure failure) {
        log.warn("`{}` failed [{}]: {}", argv(command), failure.remedy(),
                failure.stderr().isEmpty() ? failure.getMessage() : failure.stderr());
        return failure;
    }

    /**
     * 承載<em>內容</em>而非結構的 GraphQL 變數。只有 {@code body} 在 log 中省略，
     * 見 docs/design.md#logging。
     */
    private static final Set<String> CONTENT_VARIABLES = Set.of("body");

    /**
     * 把 argv 組成一行並省略內容。保留 body 長度，因為它能協助診斷失敗，又不洩漏文字。
     */
    private static String argv(List<String> command) {
        List<String> safe = new ArrayList<>(command.size());
        for (String arg : command) {
            int equals = arg.indexOf('=');
            String name = equals < 0 ? "" : arg.substring(0, equals);
            safe.add(CONTENT_VARIABLES.contains(name)
                    ? name + "=<" + (arg.length() - equals - 1) + " chars>"
                    : arg);
        }
        return String.join(" ", safe);
    }
}

package io.github.demianli.projectmcp.gh;

/**
 * 無法完成工作的 Tool 呼叫。
 *
 * <p>帶有 {@link Remedy}、原樣的 {@code gh} stderr，以及（{@link Remedy#RETRY} 時）
 * 要等多久。message 是給人讀的那一半。
 *
 * <p>以 Tool 而非 {@code gh} 命名，因為不是每個失敗都來自 {@code gh}，有些由本 Server
 * 產生（例如回應超過大小上限）。argv 不在這裡，它只寫入 log 檔供診斷。
 */
public class ToolFailure extends RuntimeException {

    private final Remedy remedy;
    private final String stderr;
    private final Integer retryAfterSeconds;

    public ToolFailure(Remedy remedy, String sentence, String stderr, Integer retryAfterSeconds) {
        super(sentence);
        this.remedy = remedy;
        this.stderr = stderr == null ? "" : stderr;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public Remedy remedy() {
        return remedy;
    }

    /** {@code gh} 的 stderr，與輸出時完全相同；沒有時為空字串。 */
    public String stderr() {
        return stderr;
    }

    /**
     * 重試前要等的秒數；不適用時為 {@code null}。只由 rate limit 填入（來自 {@code gh} 或
     * {@code WriteLimiter}），從不由逾時填入。
     */
    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }
}

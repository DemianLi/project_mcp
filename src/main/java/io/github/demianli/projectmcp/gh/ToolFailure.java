package io.github.demianli.projectmcp.gh;

/**
 * A Tool call that could not do its job.
 *
 * <p>Carries the {@link Remedy}, {@code gh}'s stderr verbatim, and—for
 * {@link Remedy#RETRY}—how long to wait. The message is the human-readable half.
 *
 * <p>Named for the Tool rather than for {@code gh} because not every failure comes from
 * {@code gh}. Some failures are invented here (e.g., a response exceeding the size limit).
 * The argv is not here; it is written to the log file for diagnosis only.
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

    /** {@code gh}'s stderr, exactly as emitted. Empty when there was none. */
    public String stderr() {
        return stderr;
    }

    /**
     * Seconds to wait before retrying, or {@code null} when not applicable. Filled only by
     * rate limits (from {@code gh} or {@code WriteLimiter}), never by timeouts.
     */
    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }
}

package io.github.demianli.projectmcp.gh;

/**
 * A {@code gh} invocation that did not produce usable output.
 *
 * <p>Carries everything the failure contract puts on the wire: the {@link Remedy}, {@code
 * gh}'s own stderr verbatim, and — for {@link Remedy#RETRY} — how long to wait first. The
 * message is the human-readable half.
 *
 * <p>The {@code gh} argv is deliberately <em>not</em> here. It says nothing a caller can act
 * on and would make this Server's internal construction part of its observable surface. It
 * is written to the log file instead, where it is still available for diagnosis.
 */
public class GhFailure extends RuntimeException {

    private final Remedy remedy;
    private final String stderr;
    private final Integer retryAfterSeconds;

    public GhFailure(Remedy remedy, String sentence, String stderr, Integer retryAfterSeconds) {
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

    /** Seconds to wait before retrying, or {@code null} when unknown or not applicable. */
    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }
}

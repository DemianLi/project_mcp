package io.github.demianli.projectmcp.gh;

/**
 * A Tool call that could not do its job.
 *
 * <p>Carries everything the failure contract puts on the wire: the {@link Remedy}, {@code
 * gh}'s stderr verbatim, and — for {@link Remedy#RETRY} — how long to wait first. The
 * message is the human-readable half.
 *
 * <p>Named for the Tool rather than for {@code gh} because not every failure comes from
 * {@code gh}. {@code get_issue} handed a pull request number is the first that does not:
 * {@code gh} succeeded and returned a pull request, and {@link
 * io.github.demianli.projectmcp.tool.IssueTools} judged it unacceptable. On such a path
 * {@link #stderr()} is empty, exactly as it is for a timeout or an absent binary. {@code
 * GhCli} remains the only place that knows how {@code gh} itself fails; only this name is
 * wider. See the 2026-09-05 amendment to
 * {@code docs/adr/0002-failure-contract-for-gh-calls.md}.
 *
 * <p>The {@code gh} argv is deliberately <em>not</em> here. It says nothing a caller can act
 * on and would make this Server's internal construction part of its observable surface. It
 * is written to the log file instead, where it is still available for diagnosis.
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
     * Seconds to wait before retrying, or {@code null} when unknown or not applicable —
     * which is nearly always. It is filled only from a wait {@code gh} names for a rate
     * limit. A timeout carries none: the budget it spent is a fact about the past, and this
     * field says "do not retry before this", which is a claim about the future. ADR-0008
     * removed the one place the two were confused.
     */
    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }
}

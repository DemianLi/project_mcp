package io.github.demianli.projectmcp.gh;

/**
 * What a caller should <em>do next</em> about a failure. Failures are classified by the
 * action available, not by the cause. See docs/design.md#failure-contract.
 */
public enum Remedy {

    /**
     * Try the same call again. May carry a wait, in which case do not retry before it — the
     * wait comes only from a rate limit, either one {@code gh} has named or this Server's own
     * limit on writes, never from a timeout.
     */
    RETRY,

    /**
     * A write whose result this Server could not read. Look whether it landed by
     * {@code list_issue_comments}, then decide: if the comment is there, success; if not,
     * {@link #RETRY}. See docs/design.md#writes.
     */
    CHECK_BEFORE_RETRY,

    /** The call cannot succeed as written. Change the arguments. */
    FIX_REQUEST,

    /** Nothing the caller can change. A human has to fix the environment. */
    ASK_OPERATOR,

    /**
     * {@code gh} failed in a way this Server does not recognise. What travels alongside is
     * all there is to go on — usually {@code gh}'s stderr verbatim, and on the one path
     * where {@code gh} produced none, a read whose output could not be read back, a Java
     * exception string standing in that slot instead.
     */
    UNKNOWN
}

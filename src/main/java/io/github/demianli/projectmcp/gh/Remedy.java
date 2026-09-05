package io.github.demianli.projectmcp.gh;

/**
 * What a caller should <em>do next</em> about a failure.
 *
 * <p>Failures are classified by the action available, not by the cause: two failures with
 * different causes and the same available action share a Remedy. A dropped network and an
 * expired timeout are different events, but a caller does exactly one thing with both.
 *
 * <p>Fixed by {@code docs/adr/0002-failure-contract-for-gh-calls.md}, extended to five
 * constants by {@code docs/adr/0008-failure-contract-for-writes.md}. Resist adding a
 * constant that names a <em>cause</em> — {@code NOT_FOUND}, {@code RATE_LIMITED} — however
 * natural it reads. That is the axis this enum deliberately does not have.
 * {@link #CHECK_BEFORE_RETRY} was admitted against that warning rather than in spite of it:
 * "look whether it landed, then decide" is an action, and it is one no other constant here
 * describes.
 */
public enum Remedy {

    /**
     * Try the same call again. May carry a wait, in which case do not retry before it — the
     * wait comes only from a rate limit {@code gh} has named, never from a timeout.
     */
    RETRY,

    /**
     * A write whose result this Server could not read. Look whether it landed, then decide:
     * if the comment is there, the write succeeded and its {@code url} is in hand; if it is
     * not, treat this as {@link #RETRY}.
     *
     * <p>The key for looking is "a comment of mine whose body equals what I sent", and it is
     * only <em>usually</em> right. Identical text posted twice legitimately finds the
     * earlier one and reads it as this write landing; a body carrying a timestamp or a nonce
     * can never be found at all. There is no better key: GitHub's {@code AddCommentInput}
     * has no idempotency field — {@code clientMutationId} names the client, not the
     * mutation, and the same key with the same body twice produces two comments. See
     * ADR-0008.
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

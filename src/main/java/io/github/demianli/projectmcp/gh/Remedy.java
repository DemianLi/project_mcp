package io.github.demianli.projectmcp.gh;

/**
 * What a caller should <em>do next</em> about a failure.
 *
 * <p>Failures are classified by the action available, not by the cause: two failures with
 * different causes and the same available action share a Remedy. A dropped network and an
 * expired timeout are different events, but a caller does exactly one thing with both.
 *
 * <p>Fixed by {@code docs/adr/0002-failure-contract-for-gh-calls.md}. Resist adding a
 * constant that names a <em>cause</em> — {@code NOT_FOUND}, {@code RATE_LIMITED} — however
 * natural it reads. That is the axis this enum deliberately does not have.
 */
public enum Remedy {

    /** Try the same call again. May carry a wait, in which case do not retry before it. */
    RETRY,

    /** The call cannot succeed as written. Change the arguments. */
    FIX_REQUEST,

    /** Nothing the caller can change. A human has to fix the environment. */
    ASK_OPERATOR,

    /** {@code gh} failed in a way this Server does not recognise. The stderr is all there is. */
    UNKNOWN
}

package io.github.demianli.projectmcp.tool;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import io.github.demianli.projectmcp.gh.Remedy;
import io.github.demianli.projectmcp.gh.ToolFailure;

/**
 * Wraps GitHub's pagination cursor so it cannot be used against the wrong issue.
 *
 * <p><strong>Why this class exists at all.</strong> GitHub's cursor is not opaque: it
 * base64-decodes to {@code cursor:v2:} followed by the {@code databaseId} of the comment it
 * points at, with no TTL and no server-side state. Handing it straight to a Client would be
 * legal — the specification makes opaqueness a MUST for a Client and only a SHOULD for a
 * Server — and was rejected on one measurement. A cursor from another issue is <em>accepted
 * silently and produces a wrong answer</em>: feeding {@code cli/cli#13840}'s cursor to
 * {@code cli/cli#14361}, an issue that genuinely has one comment, returns
 * {@code totalCount: 1} with an empty {@code nodes} and {@code hasPreviousPage: false},
 * exiting zero with no error anywhere. A Client reads that as "this issue has one comment,
 * I can see none of it, and there is nothing more".
 *
 * <p>Under ADR-0002 that is the worst outcome available. Every failure this Server reports
 * carries a Remedy so a Client knows what to do next; a silently wrong success tells it
 * nothing and is not even a failure. So the cursor a Client receives names the issue it
 * came from, and is checked on the way back. It is also the only way to catch this at all:
 * the Server cannot ask which issue a bare comment id belongs to without spending another
 * call.
 *
 * <p>Not encryption and not a signature. A Client that takes one apart learns a repository
 * name it already knew, and one that forges a mismatched wrapper gets the same
 * {@link Remedy#FIX_REQUEST} as one that pastes the wrong cursor. The check is against
 * mistakes, not against an adversary — and this Server is a subprocess of its Client, so
 * there is no adversary to be against.
 */
final class Cursors {

    /** Separates the issue this cursor belongs to from GitHub's own cursor. */
    private static final char SEPARATOR = '|';

    private Cursors() {
    }

    /** Wraps {@code ghCursor}, or returns {@code null} when there is no next response. */
    static String wrap(String owner, String repo, int number, String ghCursor) {
        if (ghCursor == null) {
            return null;
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (issue(owner, repo, number) + SEPARATOR + ghCursor)
                        .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Recovers GitHub's cursor from one this Server issued for this same issue.
     *
     * @return the cursor to send to {@code gh}, or {@code null} if the Client sent none
     * @throws ToolFailure if the cursor is unreadable or belongs to a different issue
     */
    static String unwrap(String owner, String repo, int number, String clientCursor)
            throws ToolFailure {
        if (clientCursor == null || clientCursor.isBlank()) {
            return null;
        }

        String decoded;
        try {
            decoded = new String(Base64.getUrlDecoder().decode(clientCursor),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw unreadable();
        }

        // The issue half cannot contain the separator -- neither an owner, a repository
        // name, nor a decimal number may -- so the first one is the boundary. GitHub's own
        // cursor is base64 and may well contain characters of its own; splitting from the
        // left keeps it intact.
        int boundary = decoded.indexOf(SEPARATOR);
        if (boundary < 0) {
            throw unreadable();
        }

        String from = decoded.substring(0, boundary);
        String here = issue(owner, repo, number);

        // Case-insensitively, because GitHub resolves an owner and a repository name that
        // way and this comparison is asking whether two references name the same issue.
        // Measured: repository(owner:"cli", name:"cli"), owner:"CLI" name:"CLI" and
        // owner:"cLi" name:"Cli" all answer nameWithOwner cli/cli. An exact comparison
        // refused a cursor that was never wrong, in a sentence that named the same issue
        // twice -- "came from cli/cli#14361, but this call asks about CLI/cli#14361" --
        // and told a Client to fix a request with nothing wrong in it.
        //
        // equalsIgnoreCase rather than lowercasing both: String.toLowerCase() without a
        // Locale folds by the default one, and in a Turkish locale `I` does not become `i`.
        // Only the half before the separator is compared, so GitHub's own cursor -- base64
        // and case-sensitive -- is never touched by this.
        //
        // Not canonicalised at wrap() instead. Which case is canonical is GitHub's to say
        // and it only says so in a response; this Server is in no position to declare one.
        if (!from.equalsIgnoreCase(here)) {
            throw new ToolFailure(Remedy.FIX_REQUEST,
                    "That `cursor` came from " + from + ", but this call asks about " + here
                            + ". A cursor is only valid for the issue it was issued for. "
                            + "Omit it to start from the newest comments of " + here + ".",
                    "", null);
        }
        return decoded.substring(boundary + 1);
    }

    private static ToolFailure unreadable() {
        return new ToolFailure(Remedy.FIX_REQUEST,
                "That `cursor` is not one this Server issued. Pass back the `nextCursor` "
                        + "from a previous response unchanged, or omit it to start from the "
                        + "newest comments.",
                "", null);
    }

    private static String issue(String owner, String repo, int number) {
        return owner + "/" + repo + "#" + number;
    }
}

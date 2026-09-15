package io.github.demianli.projectmcp.tool;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import io.github.demianli.projectmcp.gh.Remedy;
import io.github.demianli.projectmcp.gh.ToolFailure;

/**
 * Wraps GitHub cursors with issue identity to prevent silent errors from cross-issue reuse.
 *
 * <p>GitHub's cursor is opaque to Clients but becomes wrong silently if used on a different
 * issue. Wrapping with the issue reference allows validation on the return path.
 */
final class Cursors {

    /** Separates the issue this cursor belongs to from GitHub's own cursor. */
    private static final char SEPARATOR = '|';

    private Cursors() {
    }

    /** Wraps {@code ghCursor}, or returns {@code null} when there is no next response. */
    static String wrap(IssueRef issue, String ghCursor) {
        if (ghCursor == null) {
            return null;
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (issue.reference() + SEPARATOR + ghCursor)
                        .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Recovers GitHub's cursor from one this Server issued for this same issue.
     *
     * @return the cursor to send to {@code gh}, or {@code null} if the Client sent none
     * @throws ToolFailure if the cursor is unreadable or belongs to a different issue
     */
    static String unwrap(IssueRef issue, String clientCursor) throws ToolFailure {
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

        int boundary = decoded.indexOf(SEPARATOR);
        if (boundary < 0) {
            throw unreadable();
        }

        String from = decoded.substring(0, boundary);
        if (!issue.isNamedBy(from)) {
            throw new ToolFailure(Remedy.FIX_REQUEST,
                    "That `cursor` came from " + from + ", but this call asks about "
                            + issue.reference() + ". A cursor is only valid for the issue it "
                            + "was issued for. Omit it to start from the newest comments of "
                            + issue.reference() + ".",
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

}

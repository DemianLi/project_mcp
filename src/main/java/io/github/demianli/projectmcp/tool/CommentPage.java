package io.github.demianli.projectmcp.tool;

import java.util.List;

/**
 * Response envelope for {@code list_issue_comments}.
 *
 * <p>Extends the standard {@code items}, {@code count}, {@code truncated} with
 * {@code totalCount} and {@code nextCursor}, which paging requires.
 *
 * @param totalCount total comments on the issue (may be larger than items returned)
 * @param nextCursor opaque pagination marker, wrapped by {@link Cursors}; null when exhausted
 */
public record CommentPage(
        List<Comment> items,
        int count,
        boolean truncated,
        int totalCount,
        String nextCursor) {
}

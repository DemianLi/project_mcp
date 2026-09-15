package io.github.demianli.projectmcp.tool;

import java.util.List;

/**
 * {@code list_issue_comments} 的回應 Envelope。
 *
 * <p>在標準的 {@code items}、{@code count}、{@code truncated} 之外，加上分頁需要的
 * {@code totalCount} 與 {@code nextCursor}。
 *
 * @param totalCount 此 issue 的留言總數（可能多於本次回傳的 items）
 * @param nextCursor 不透明的分頁標記，由 {@link Cursors} 包裝；沒有更多時為 null
 */
public record CommentPage(
        List<Comment> items,
        int count,
        boolean truncated,
        int totalCount,
        String nextCursor) {
}

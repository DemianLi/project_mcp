package io.github.demianli.projectmcp.tool;

/**
 * {@code list_issue_comments} 回傳的一則留言。
 *
 * @param author 登入名稱；帳號已刪除時為空字串
 * @param authorAssociation OWNER、MEMBER、CONTRIBUTOR、NONE 等（GitHub 定義的集合）
 * @param body 原樣傳遞的 Markdown 文字
 * @param url 留言的永久連結，供 Client 使用
 */
public record Comment(
        String author,
        String authorAssociation,
        String createdAt,
        String body,
        String url) {
}

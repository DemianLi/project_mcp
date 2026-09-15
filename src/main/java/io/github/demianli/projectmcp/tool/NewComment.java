package io.github.demianli.projectmcp.tool;

/**
 * {@code add_issue_comment} 的結果：新留言的 URL。
 *
 * <p>url 是 Client 唯一能用來確認寫入成功的值。與列表操作不同，這不是 Envelope。
 */
public record NewComment(String url) {
}

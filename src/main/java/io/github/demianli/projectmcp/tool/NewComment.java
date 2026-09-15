package io.github.demianli.projectmcp.tool;

/**
 * Result from {@code add_issue_comment}: the URL of the written comment.
 *
 * <p>The url is the only value the Client can use to verify the write landed. Unlike list
 * operations, this is not an Envelope.
 */
public record NewComment(String url) {
}

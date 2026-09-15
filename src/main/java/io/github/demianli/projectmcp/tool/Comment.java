package io.github.demianli.projectmcp.tool;

/**
 * A comment returned by {@code list_issue_comments}.
 *
 * @param author login name; empty when account deleted
 * @param authorAssociation OWNER, MEMBER, CONTRIBUTOR, NONE, etc. (GitHub's set)
 * @param body Markdown text passed through verbatim
 * @param url comment permalink for Client use
 */
public record Comment(
        String author,
        String authorAssociation,
        String createdAt,
        String body,
        String url) {
}

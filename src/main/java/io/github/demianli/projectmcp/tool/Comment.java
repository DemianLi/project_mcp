package io.github.demianli.projectmcp.tool;

/**
 * One comment on an issue, as {@code list_issue_comments} reports it.
 *
 * <p>Five fields, chosen by the query rather than trimmed after arrival — on the GraphQL
 * route the selection <em>is</em> the request. Fixed by
 * {@code docs/adr/0006-list-issue-comments-parameters-and-return-shape.md}, which measured
 * what each one costs.
 *
 * @param author the login only, as {@link IssueDetail#author()} is. Empty when the account
 *     has been deleted: GitHub types this {@code Actor}, not {@code Actor!}, and a login is
 *     never the empty string, so the empty value is unambiguous.
 * @param authorAssociation {@code OWNER}, {@code MEMBER}, {@code CONTRIBUTOR}, {@code NONE}
 *     and the rest of GitHub's set. The cheapest field here at 27 bytes, and the one that
 *     tells a model whether a maintainer or a passer-by wrote the sentence it is reading.
 * @param body the comment's Markdown, passed through. {@code bodyText} would be 26% smaller
 *     and was rejected on measurement: it drops the fences around code blocks, so nothing
 *     marks where code ends, and it strips the target out of every Markdown link.
 * @param url the comment's own permalink, {@code #issuecomment-<id>}. Three times the cost
 *     of the {@code databaseId} it contains, and kept because {@code list_issues} and
 *     {@code get_issue} both carry a {@code url} and because it is the one thing a Client
 *     can hand to a person.
 */
public record Comment(
        String author,
        String authorAssociation,
        String createdAt,
        String body,
        String url) {
}

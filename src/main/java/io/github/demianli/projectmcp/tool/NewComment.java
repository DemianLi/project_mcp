package io.github.demianli.projectmcp.tool;

/**
 * What {@code add_issue_comment} reports about the comment it just wrote.
 *
 * <p>One field, and the shortest argument in this package for why. ADR-0003's rule — a
 * field earns its place if the Client can <em>do</em> something with it — has no next read
 * to point at on a write, so what is left of it is <em>hand the result to a person</em> and
 * <em>prove the write landed</em>. {@code url} is the only field that does either.
 *
 * <p>The other four of {@link Comment}'s five are excluded for reasons that are facts rather
 * than judgements: {@code body} is what the caller just sent, {@code createdAt} is
 * approximately now, {@code author} is whoever {@code gh} is authenticated as, and
 * {@code authorAssociation} — the one a Client genuinely cannot derive — changes nothing
 * about the call it just made. Cost did not decide it either way: ADR-0007 measured all
 * five at 356 B against this one at 139 B across the same two calls, because the fields are
 * already inside the mutation's response.
 *
 * <p>Not an Envelope: the Envelope belongs to {@code list_*} Tools, and one write is not a
 * list. Fixed by
 * {@code docs/adr/0007-add-issue-comment-parameters-return-and-annotations.md}.
 *
 * <p>Proof of landing is not decoration. The spec's own timeout path manufactures "did the
 * write happen?" — a party SHOULD cancel on expiry, the cancellation may arrive after the
 * work is done, and the Client SHOULD ignore a response that arrives afterwards — while
 * offering no way to detect it. This {@code url} is the only artifact this Tool leaves that
 * a later {@code list_issue_comments} can check against, which is what
 * {@link io.github.demianli.projectmcp.gh.Remedy#CHECK_BEFORE_RETRY} sends a Client to do.
 *
 * @param url the new comment's permalink, {@code #issuecomment-<id>}
 */
public record NewComment(String url) {
}

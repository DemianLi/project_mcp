package io.github.demianli.projectmcp.tool;

import java.util.List;

/**
 * The Envelope {@code list_issue_comments} returns: ADR-0001's three keys, plus two.
 *
 * <p>This is the first Tool to add any, and ADR-0001's amendment states the rule it follows
 * — {@code items}, {@code count} and {@code truncated} are present on every {@code list_*}
 * Tool with the meanings given there; a Tool may add keys beside them, and may never remove
 * or redefine one. So a Client that learned {@code list_issues} reads this without surprise,
 * and the two extra keys are capability it has not needed before rather than a second shape
 * it must first classify.
 *
 * <p><strong>Not folded into {@link ListResult}</strong>, deliberately. Widening the shared
 * record would put {@code totalCount} and a {@code nextCursor} of {@code null} on
 * {@code list_issues} and {@code list_labels} — telling a Client "there is no next page"
 * where the truth is that those Tools cannot page at all. The Envelope is a contract about
 * JSON keys; the shared record is one way of honouring it, not the contract itself.
 *
 * <p>The reason this Tool pages when the other two do not is that comments have <em>no
 * narrowing parameter</em>. A Client that finds {@code list_issues} truncated asks again
 * with a different {@code state} or label set, and ADR-0004 tells one that finds
 * {@code list_labels} truncated to use {@code search} instead. An issue's comments offer
 * nothing of the kind, so {@code truncated: true} alone would be a door with nothing behind
 * it. See {@code docs/adr/0006-list-issue-comments-parameters-and-return-shape.md}.
 *
 * @param truncated whether older comments exist beyond this response. Kept although it is
 *     derivable from either of the two fields below, for the reason ADR-0001 gives for
 *     keeping the equally derivable {@code count}: deriving it is a step a model can get
 *     wrong, and reporting it removes the opportunity.
 * @param totalCount how many comments the issue has in total. It arrives in the same
 *     response at no extra call. ADR-0004 refused the equivalent for {@code list_labels}
 *     because {@code truncated} plus its "use `search`" line already carried the decision;
 *     there is no such line here, and "you have 30 of 143" is not the same fact as "there
 *     is more".
 * @param nextCursor an opaque marker for the next, older response — {@code null} when there
 *     is none. Wrapped by {@link Cursors} rather than passed through, because GitHub accepts
 *     another issue's cursor silently and answers wrongly.
 */
public record CommentPage(
        List<Comment> items,
        int count,
        boolean truncated,
        int totalCount,
        String nextCursor) {
}

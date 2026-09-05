package io.github.demianli.projectmcp.tool;

import java.util.List;

/**
 * One issue, as {@code get_issue} reports it.
 *
 * <p>Twelve fields, and no Envelope: a single read returns one object, not a wrapper. The
 * first seven are {@link IssueSummary}'s seven, unchanged in name and shape, so a Client
 * that learned {@code list_issues} reads this for free and the last five are purely
 * additive. Fixed by {@code docs/adr/0003-get-issue-parameters-and-return-shape.md}.
 *
 * <p><strong>{@code comments} is absent</strong>, and that is the load-bearing decision
 * rather than an omission. ADR-0001 kept {@code body} out of {@code list_issues} because it
 * is the one field that grows without bound; one level down, the field that grows without
 * bound inside a single read is {@code comments} — measured at 110,457 bytes against a
 * 1,471-byte body on {@code cli/cli#13840}, which is 62× the entire {@code list_issues}
 * payload ADR-0001 measured. Excluding it also means there is no nested list here, so no
 * second {@code truncated} and no second shape for a Client to learn.
 *
 * @param labels label names only; @param assignees login handles only — both flattened for
 *     the reasons {@link IssueSummary} gives.
 * @param author the login only. {@code gh} returns an object also carrying a node id, a
 *     bot flag and a display name that is empty in practice.
 * @param closedAt {@code null} while the issue is open, and {@code stateReason} is {@code ""}
 *     in the same state. The two disagree because {@code gh} spells them that way, and both
 *     are passed through unchanged: normalising one to match the other would invent a shape
 *     GitHub did not report. Same reasoning as {@link IssueSummary#updatedAt()} being a
 *     String — what crosses the wire is what {@code gh} said.
 * @param stateReason {@code COMPLETED} or {@code NOT_PLANNED} once closed. With
 *     {@code closedAt} it answers the question only a single read asks: when was this
 *     closed, and was it done or abandoned.
 */
public record IssueDetail(
        int number,
        String title,
        String state,
        List<String> labels,
        List<String> assignees,
        String url,
        String updatedAt,
        String body,
        String author,
        String createdAt,
        String closedAt,
        String stateReason) {
}

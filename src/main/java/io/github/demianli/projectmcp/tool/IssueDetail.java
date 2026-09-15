package io.github.demianli.projectmcp.tool;

import java.util.List;

/**
 * Full issue details returned by {@code get_issue}.
 *
 * <p>Twelve fields including seven from {@link IssueSummary} (unchanged, so Clients learn
 * the shape once), plus five additional ones. No {@code comments} field—those require their
 * own Tool.
 *
 * @param labels and assignees: flattened to strings for reuse as filter parameters
 * @param author login name only
 * @param closedAt null while open; {@code stateReason} empty in same state (as gh reports)
 * @param stateReason COMPLETED or NOT_PLANNED when closed
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

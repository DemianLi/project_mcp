package io.github.demianli.projectmcp.tool;

import java.util.List;

/**
 * Issue summary returned by {@code list_issues}.
 *
 * <p>Seven fields allowing Clients to decide which issue to read further. No body (that is
 * for {@code get_issue}).
 *
 * @param labels and assignees: flattened to strings for filter reuse
 * @param updatedAt ISO-8601 timestamp as GitHub reports it (kept as String)
 */
public record IssueSummary(
        int number,
        String title,
        String state,
        List<String> labels,
        List<String> assignees,
        String url,
        String updatedAt) {
}

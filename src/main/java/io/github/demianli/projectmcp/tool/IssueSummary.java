package io.github.demianli.projectmcp.tool;

import java.util.List;

/**
 * One issue, as {@code list_issues} reports it.
 *
 * <p>Seven fields, chosen so a Client can decide <em>which</em> issue to read. {@code body}
 * is deliberately absent — reading an issue is {@code get_issue}'s job, and {@code body} is
 * the one field that grows without bound.
 *
 * @param labels label names only. {@code gh} returns objects carrying a GraphQL node id, a
 *     description and a UI colour; flattening leaves the Client holding exactly the string
 *     it would pass back into the {@code labels} parameter.
 * @param assignees login handles only, flattened for the same reason.
 * @param updatedAt an ISO-8601 instant, passed through as {@code gh} spelled it. Kept as a
 *     String on purpose: a date type would serialise according to whichever Jackson modules
 *     happen to be on the classpath.
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

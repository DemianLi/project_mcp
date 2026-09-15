package io.github.demianli.projectmcp.tool;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Maps issue JSON from {@code gh} to issue result types.
 *
 * <p>Testable as a pure function against captured payloads. Semantic validation (e.g.
 * rejecting pull requests) happens at the Tool level, not here.
 */
@Component
public class IssueMapper {

    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * @param ghJson the array {@code gh issue list --json ...} printed, fetched with one
     *     spare entry so truncation can be detected
     * @param limit the effective, already-clamped limit the Client is owed
     */
    public ListResult<IssueSummary> toEnvelope(String ghJson, int limit) {
        JsonNode root = json.readTree(ghJson);
        List<IssueSummary> issues = new ArrayList<>();
        for (JsonNode issue : root) {
            issues.add(new IssueSummary(
                    issue.path("number").asInt(),
                    issue.path("title").asString(""),
                    issue.path("state").asString(""),
                    flatten(issue.path("labels"), "name"),
                    flatten(issue.path("assignees"), "login"),
                    issue.path("url").asString(""),
                    issue.path("updatedAt").asString("")));
        }
        return ListResult.of(issues, limit);
    }

    /** Maps issue view JSON to {@code IssueDetail}. */
    public IssueDetail toDetail(String ghJson) {
        JsonNode issue = json.readTree(ghJson);
        return new IssueDetail(
                issue.path("number").asInt(),
                issue.path("title").asString(""),
                issue.path("state").asString(""),
                flatten(issue.path("labels"), "name"),
                flatten(issue.path("assignees"), "login"),
                issue.path("url").asString(""),
                issue.path("updatedAt").asString(""),
                issue.path("body").asString(""),
                issue.path("author").path("login").asString(""),
                issue.path("createdAt").asString(""),
                issue.path("closedAt").asString(null),
                issue.path("stateReason").asString(""));
    }

    /** Reduces an array of objects to the one field worth keeping. */
    private static List<String> flatten(JsonNode array, String field) {
        List<String> values = new ArrayList<>();
        for (JsonNode element : array) {
            values.add(element.path(field).asString(""));
        }
        return List.copyOf(values);
    }
}

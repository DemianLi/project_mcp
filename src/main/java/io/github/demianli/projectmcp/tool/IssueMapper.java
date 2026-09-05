package io.github.demianli.projectmcp.tool;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns the JSON {@code gh} prints into the records the Tools return.
 *
 * <p>Kept apart from {@link io.github.demianli.projectmcp.gh.GhCli} so this half is a pure
 * function of a string: it can be exercised against a captured payload without a
 * subprocess, a network call, or a GitHub account.
 *
 * <p>Purity is why nothing here rejects anything. {@code get_issue} refuses a pull request
 * number, but that judgement is made by {@link IssueTools} on the record this class
 * returns, not here on the JSON — so the payload is parsed exactly once and this class
 * keeps knowing nothing about failure. The next Tool with a semantic check should follow
 * the same split.
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

    /**
     * Maps what {@code gh issue view --json ...} prints into one issue.
     *
     * <p>Nulls are passed through as {@code gh} spelled them: {@code closedAt} is absent
     * while an issue is open, and {@code asString(null)} keeps it that way rather than
     * turning it into an empty string that would read as a real timestamp of zero length.
     */
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

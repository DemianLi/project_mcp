package io.github.demianli.projectmcp.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.demianli.projectmcp.gh.FakeGh;
import io.github.demianli.projectmcp.gh.GhCli;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Coverage layer: {@code get_issue}'s payload and its one home-grown failure.
 *
 * <p>Every fixture here is a verbatim capture of what the real {@code gh issue view --json}
 * printed, so the field spellings and the shapes {@code gh} chooses — {@code closedAt} null
 * on an open issue while {@code stateReason} is an empty string — are the real ones rather
 * than what this Server assumes they are.
 */
class GetIssueTest {

    @TempDir Path tmp;

    private CallToolResult get(String fixture, int number) throws IOException {
        Path payload = tmp.resolve("payload.json");
        Files.writeString(payload, Files.readString(Path.of("src/test/resources/gh/" + fixture)));
        var tools = new IssueTools(new GhCli(FakeGh.writing(tmp, "cat " + payload), 30),
                new IssueMapper());
        return tools.getIssue("DemianLi", "project_mcp", number);
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().get(0)).text();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(CallToolResult result) {
        return (Map<String, Object>) result.structuredContent();
    }

    @Test
    void aClosedIssueCarriesTwelveFieldsAndNoEnvelope() throws Exception {
        CallToolResult result = get("issue-view.json", 13);

        assertThat(result.isError()).isFalse();
        assertThat(result.structuredContent())
                .as("ADR-0003 inherits ADR-0001's TEXT mode rather than reopening it")
                .isNull();

        assertThat(text(result))
                .as("a bare object, not an Envelope: a single read has no items/count/truncated")
                .startsWith("{\"number\":13,")
                .doesNotContain("\"items\"", "\"count\"", "\"truncated\"");

        assertThat(text(result))
                .contains("\"state\":\"CLOSED\"")
                .contains("\"stateReason\":\"COMPLETED\"")
                .contains("\"closedAt\":\"")
                .as("labels, assignees and author are all flattened to strings")
                .contains("\"labels\":[\"wayfinder:grilling\"]")
                .contains("\"assignees\":[\"DemianLi\"]")
                .contains("\"author\":\"DemianLi\"");

        assertThat(text(result))
                .as("body is the whole reason this Tool exists")
                .contains("\"body\":\"## Question");
    }

    @Test
    void anOpenIssueKeepsGhsOwnSpellingOfAbsence() throws Exception {
        // gh disagrees with itself here -- closedAt is null, stateReason is "" -- and both
        // are passed through. Normalising either would invent a shape GitHub did not report.
        CallToolResult result = get("issue-view-open.json", 15);

        assertThat(text(result))
                .contains("\"closedAt\":null")
                .contains("\"stateReason\":\"\"")
                .contains("\"state\":\"OPEN\"")
                .as("an unassigned issue is an empty array, not a null")
                .contains("\"assignees\":[]");
    }

    @Test
    void commentsAreNotInThePayloadAtAll() throws Exception {
        // Not merely empty: the field is never requested, so there is no key to read as
        // "this issue has no comments".
        assertThat(text(get("issue-view.json", 13))).doesNotContain("\"comments\"");
    }

    @Test
    void aPullRequestNumberIsRejected() throws Exception {
        // The fixture is a real pull request seen through `gh issue view` -- gh succeeded
        // and answered. Nothing failed; this Server judged the answer unacceptable.
        CallToolResult result = get("issue-view-pull-request.json", 14356);

        assertThat(result.isError()).isTrue();

        Map<String, Object> structured = structured(result);
        assertThat(structured)
                .containsEntry("remedy", "FIX_REQUEST")
                .as("gh wrote nothing to stderr, because gh did not fail")
                .containsEntry("stderr", "");

        assertThat((String) structured.get("message"))
                .as("built per call: the number asked for, and the URL out of the payload")
                .startsWith("#14356 is a pull request, not an issue")
                .endsWith("https://github.com/cli/cli/pull/14356");

        assertThat(text(result))
                .as("with no stderr the text half is the sentence alone, unpadded")
                .isEqualTo((String) structured.get("message"));

        assertThat(text(result))
                .as("none of the pull request's own data leaks out with the refusal")
                .doesNotContain("\"title\"", "\"body\"");
    }

    @Test
    void aGhFailureStillTravelsTheOrdinaryWay() throws Exception {
        // The other origin: get_issue inherits GhCli's classification unchanged, including
        // the case get_issue is the first Tool able to reach.
        var tools = new IssueTools(
                new GhCli(FakeGh.failing(tmp, "GraphQL: Could not resolve to an issue or pull "
                        + "request with the number of 9999. (repository.issue)"), 30),
                new IssueMapper());
        CallToolResult result = tools.getIssue("DemianLi", "project_mcp", 9999);

        assertThat(result.isError()).isTrue();
        assertThat(structured(result)).containsEntry("remedy", "FIX_REQUEST");
        assertThat((String) structured(result).get("stderr")).contains("number of 9999");
    }
}

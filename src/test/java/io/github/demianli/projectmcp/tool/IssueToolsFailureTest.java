package io.github.demianli.projectmcp.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.demianli.projectmcp.gh.FakeGh;
import io.github.demianli.projectmcp.gh.GhCli;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Coverage layer: the wire shape a Tool builds, asserted without crossing the wire.
 *
 * <p>{@code CallToolResult} is a value, so its shape can be checked here. What cannot be
 * checked here is that the Server actually emits it — that is the acceptance layer's job.
 */
class IssueToolsFailureTest {

    @TempDir Path tmp;

    private CallToolResult call(String ghScriptPath) {
        var tools = new IssueTools(new GhCli(ghScriptPath, 30), new IssueMapper());
        return tools.listIssues("DemianLi", "project_mcp", null, null, null);
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().get(0)).text();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(CallToolResult result) {
        return (Map<String, Object>) result.structuredContent();
    }

    @Test
    void aFailureCarriesBothHalvesAndSaysTheSameThingTwice() throws Exception {
        String stderr = "GraphQL: Could not resolve to a Repository with the name 'a/b'. "
                + "(repository)";
        CallToolResult result = call(FakeGh.failing(tmp, stderr));

        assertThat(result.isError()).isTrue();

        Map<String, Object> structured = structured(result);
        assertThat(structured).containsEntry("remedy", "FIX_REQUEST")
                .containsEntry("stderr", stderr)
                .doesNotContainKey("retryAfterSeconds");

        // The human half and the machine half are one message to two readers, not two
        // different pieces of information.
        assertThat(text(result))
                .isEqualTo(structured.get("message") + "\n\n" + stderr);
    }

    @Test
    void theMessageAppearsExactlyOnce() throws Exception {
        // The defect this whole map exists to fix. Throwing a RuntimeException had Spring AI
        // join getMessage() and the root cause's getMessage(), which for a directly-thrown
        // exception are the same sentence -- so it landed twice.
        String stderr = "HTTP 401: Bad credentials";
        CallToolResult result = call(FakeGh.failing(tmp, stderr));

        String message = (String) structured(result).get("message");
        assertThat(text(result).split(java.util.regex.Pattern.quote(message), -1))
                .as("the sentence appears once, not twice")
                .hasSize(2);
        assertThat(text(result).indexOf(stderr))
                .isEqualTo(text(result).lastIndexOf(stderr));
    }

    @Test
    void theGhArgvNeverReachesTheCaller() throws Exception {
        CallToolResult result = call(FakeGh.failing(tmp, "HTTP 401: Bad credentials"));

        assertThat(text(result)).doesNotContain("issue list", "--repo", "--json", "--limit");
        assertThat(result.structuredContent().toString())
                .doesNotContain("issue list", "--repo", "--json", "--limit");
    }

    @Test
    void aRetryableFailureCarriesItsWait() throws Exception {
        CallToolResult result = call(
                FakeGh.failing(tmp, "API rate limit exceeded. Please retry after 60 seconds."));

        assertThat(structured(result))
                .containsEntry("remedy", "RETRY")
                .containsEntry("retryAfterSeconds", 60);
    }

    @Test
    void successIsUnchangedByAnyOfThis() throws Exception {
        // ADR-0001's shape, from a payload captured verbatim from the real gh.
        String fixture = Files.readString(Path.of("src/test/resources/gh/issue-list.json"));
        Path out = tmp.resolve("payload.json");
        Files.writeString(out, fixture);
        CallToolResult result = call(FakeGh.writing(tmp, "cat " + out));

        // isError is false rather than absent: CallToolResult's builder defaults it, and
        // ToolResults.of uses the same builder call Spring AI would have used.
        assertThat(result.isError()).isFalse();
        assertThat(result.structuredContent()).isNull();
        assertThat(text(result))
                .startsWith("{\"items\":[")
                .contains("\"count\":3", "\"truncated\":false")
                .contains("\"labels\":[\"wayfinder:task\"]");
    }
}

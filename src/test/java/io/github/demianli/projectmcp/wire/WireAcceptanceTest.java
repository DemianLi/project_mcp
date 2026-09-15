package io.github.demianli.projectmcp.wire;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests the Server running as a subprocess, answering over Stdio.
 *
 * <p>This is the only layer observing wire behavior: isError, structuredContent, message
 * format. These are produced by the callback layer building CallToolResult and the
 * transport serializing it — invisible in-JVM.
 *
 * <p>Thin by design: proves the wire shape. Remedy coverage belongs in the cheaper layer.
 *
 * <p>Offline: the subprocess has only a stand-in gh on PATH, so no real calls leave the
 * machine.
 */
class WireAcceptanceTest {

    @TempDir Path tmp;

    private static CallToolResult listIssues(McpSyncClient client) {
        return client.callTool(new CallToolRequest("list_issues",
                Map.of("owner", "DemianLi", "repo", "project_mcp")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(CallToolResult result) {
        return (Map<String, Object>) result.structuredContent();
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().get(0)).text();
    }


    @Test
    void aFailureCrossesTheWireAsAnErrorResultWithBothHalves() throws Exception {
        String stderr = "GraphQL: Could not resolve to a Repository with the name "
                + "'DemianLi/project_mcp'. (repository)";
        try (McpSyncClient client = LaunchedServer.withGh(tmp, (
                "cat >&2 <<'STDERR'\n" + stderr + "\nSTDERR\nexit 1"))) {

            CallToolResult result = listIssues(client);

            assertThat(result.isError()).isTrue();

            Map<String, Object> structured = structured(result);
            assertThat(structured)
                    .as("structuredContent survives the wire -- an error result skips "
                            + "output-schema validation, so it is forwarded untouched")
                    .containsEntry("remedy", "FIX_REQUEST")
                    .containsEntry("stderr", stderr);

            String message = (String) structured.get("message");
            assertThat(text(result))
                    .as("a human reading a Client that renders only text still sees a sentence")
                    .startsWith(message)
                    .contains(stderr);
            assertThat(text(result).split(java.util.regex.Pattern.quote(message), -1))
                    .as("the sentence appears once. Throwing a RuntimeException put it twice")
                    .hasSize(2);
            assertThat(text(result))
                    .as("the gh argv stays out of what a Client can see")
                    .doesNotContain("issue list", "--repo", "--json");
        }
    }

    @Test
    void anAbsentGhCrossesTheWireToo() throws Exception {
        // Absent binary produces no stderr. Empty PATH ensures gh is truly unreachable;
        // not shadowing a real gh at /usr/bin/gh which would make the test live.
        Path empty = Files.createDirectory(tmp.resolve("empty"));
        try (McpSyncClient client = LaunchedServer.onPath(empty.toString())) {
            CallToolResult result = listIssues(client);

            assertThat(result.isError()).isTrue();
            assertThat(structured(result))
                    .containsEntry("remedy", "ASK_OPERATOR")
                    .containsEntry("stderr", "");
        }
    }

    @Test
    void aFailureThisServerInventedCrossesTheWireIdentically() throws Exception {
        // The first failure whose origin is not GhCli: gh succeeded and returned a pull
        // request, and IssueTools refused it. Worth a place in this thin layer precisely
        // because the origin is new -- the question is whether a ToolFailure built above
        // GhCli produces the same wire shape as one built inside it, and only this layer
        // can see a wire shape at all.
        String fixture = Files.readString(
                Path.of("src/test/resources/gh/issue-view-pull-request.json"));
        Path payload = tmp.resolve("pr.json");
        Files.writeString(payload, fixture);

        try (McpSyncClient client = LaunchedServer.withGh(tmp, "cat " + payload)) {
            CallToolResult result = client.callTool(new CallToolRequest("get_issue",
                    Map.of("owner", "cli", "repo", "cli", "number", 14356)));

            assertThat(result.isError()).isTrue();
            assertThat(structured(result))
                    .containsEntry("remedy", "FIX_REQUEST")
                    .containsEntry("stderr", "");
            assertThat(text(result))
                    .startsWith("#14356 is a pull request, not an issue")
                    .doesNotContain("issue view", "--repo", "--json");
        }
    }

    @Test
    void successCrossesTheWireInTheEnvelopeAndNothingElse() throws Exception {
        String fixture = Files.readString(Path.of("src/test/resources/gh/issue-list.json"));
        Path payload = tmp.resolve("payload.json");
        Files.writeString(payload, fixture);

        try (McpSyncClient client = LaunchedServer.withGh(tmp, "cat " + payload)) {
            CallToolResult result = listIssues(client);

            assertThat(result.isError()).isFalse();
            assertThat(result.structuredContent())
                    .as("success returns text only, the failure contract does not change this")
                    .isNull();
            assertThat(text(result))
                    .startsWith("{\"items\":[")
                    .contains("\"count\":3", "\"truncated\":false");
        }
    }

    @Test
    void aToolOnASecondComponentIsDeclaredAndCallable() throws Exception {
        // list_labels is the first Tool that is not a method on IssueTools, which makes it
        // the first test of a claim ProjectMcpApplication has been making in prose since
        // the Server was scaffolded: "adding a Tool means adding a component -- not editing
        // this class". Discovery happens in the Spring context of a separate process, so
        // this layer is the only one that can watch it happen.
        String fixture = Files.readString(Path.of("src/test/resources/gh/label-list.json"));
        Path payload = tmp.resolve("labels.json");
        Files.writeString(payload, fixture);

        try (McpSyncClient client = LaunchedServer.withGh(tmp, "cat " + payload)) {
            assertThat(client.listTools().tools())
                    .as("the annotation scanner found both components")
                    .extracting(io.modelcontextprotocol.spec.McpSchema.Tool::name)
                    .contains("list_issues", "get_issue", "list_labels");

            CallToolResult result = client.callTool(new CallToolRequest("list_labels",
                    Map.of("owner", "DemianLi", "repo", "project_mcp")));

            assertThat(result.isError()).isFalse();
            assertThat(text(result))
                    .startsWith("{\"items\":[{\"name\":\"accessibility\"")
                    .contains("\"count\":19", "\"truncated\":false");
        }
    }

    @Test
    void theEnvelopeThatGrewCrossesTheWireWithBothExtraKeys() throws Exception {
        // list_issue_comments adds keys to the Envelope. On the wire, null values must
        // arrive as explicit null, not missing keys, so "no next page" stays distinct from
        // "this Server does not page". See docs/design.md#list_issue_comments.
        String fixture = Files.readString(
                Path.of("src/test/resources/gh/comments-last-page.json"));
        Path payload = tmp.resolve("comments.json");
        Files.writeString(payload, fixture);

        try (McpSyncClient client = LaunchedServer.withGh(tmp, "cat " + payload)) {
            assertThat(client.listTools().tools())
                    .as("the annotation scanner found all components")
                    .extracting(io.modelcontextprotocol.spec.McpSchema.Tool::name)
                    .contains("list_issues", "get_issue", "list_labels", "list_issue_comments");

            CallToolResult result = client.callTool(new CallToolRequest("list_issue_comments",
                    Map.of("owner", "cli", "repo", "cli", "number", 14361)));

            assertThat(result.isError()).isFalse();
            assertThat(text(result))
                    .startsWith("{\"items\":[")
                    .contains("\"count\":1", "\"truncated\":false", "\"totalCount\":1")
                    .as("null survives as explicit null, not a missing key")
                    .contains("\"nextCursor\":null");
        }
    }

    @Test
    void aCursorFromTheWrongIssueIsRefusedAcrossTheWireWithoutTouchingGh() throws Exception {
        // The second failure this Server invents rather than inherits, and the first that
        // is refused before `gh` runs at all. The stand-in here exits non-zero with a
        // stderr that would classify as something else entirely, so if the check were
        // happening after the call this assertion could not pass.
        try (McpSyncClient client = LaunchedServer.withGh(tmp, (
                "echo 'GraphQL: Could not resolve to a Repository' >&2\nexit 1"))) {

            CallToolResult result = client.callTool(new CallToolRequest("list_issue_comments",
                    Map.of("owner", "cli", "repo", "cli", "number", 14361,
                            "cursor", "bm90LWZvci10aGlzLWlzc3Vl")));

            assertThat(result.isError()).isTrue();
            assertThat(structured(result))
                    .containsEntry("remedy", "FIX_REQUEST")
                    .as("no gh ran, so there is no stderr to report")
                    .containsEntry("stderr", "");
            assertThat(text(result)).contains("cursor");
        }
    }
    @Test
    void theFirstToolThatWritesCrossesTheWireWithItsHintsAndItsOneKey() throws Exception {
        // Observes annotations and payload shape for the first write Tool. Annotations
        // (readOnlyHint false, destructiveHint and idempotentHint false) reach the wire via
        // Spring AI. Payload is one key only, no Envelope or structuredContent.
        Path id = tmp.resolve("id.json");
        Path added = tmp.resolve("added.json");
        Files.writeString(id, Files.readString(Path.of("src/test/resources/gh/issue-node-id.json")));
        Files.writeString(added,
                Files.readString(Path.of("src/test/resources/gh/add-comment.json")));

        try (McpSyncClient client = LaunchedServer.withGh(tmp, (
                "n=$(cat " + tmp.resolve("count.txt") + " 2>/dev/null || echo 0)\n"
                        + "n=$((n+1)); echo $n > " + tmp.resolve("count.txt") + "\n"
                        + "if [ $n -eq 1 ]; then cat " + id + "; else cat " + added + "; fi"))) {

            var tool = client.listTools().tools().stream()
                    .filter(t -> t.name().equals("add_issue_comment"))
                    .findFirst()
                    .orElseThrow();

            assertThat(tool.annotations().readOnlyHint())
                    .as("the first false in this Server")
                    .isFalse();
            assertThat(tool.annotations().destructiveHint())
                    .as("additive vs destructive is the spec axis")
                    .isFalse();
            assertThat(tool.annotations().idempotentHint())
                    .as("written explicitly and observable here")
                    .isFalse();
            assertThat(tool.annotations().openWorldHint()).isTrue();
            assertThat(tool.annotations().title()).isEqualTo("Add a comment to an issue");

            CallToolResult result = client.callTool(new CallToolRequest("add_issue_comment",
                    Map.of("owner", "DemianLi", "repo", "project-mcp-sandbox",
                            "number", 1, "body", "hello")));

            assertThat(result.isError()).isFalse();
            assertThat(result.structuredContent()).isNull();
            assertThat(text(result))
                    .isEqualTo("{\"url\":\"https://github.com/DemianLi/project-mcp-sandbox/"
                            + "issues/1#issuecomment-5553376090\"}");
        }
    }

    @Test
    void aBlankBodyIsRefusedAcrossTheWireBeforeAnythingCouldBeWritten() throws Exception {
        // The third failure this Server invents rather than inherits, and the first on a
        // write. The stand-in would succeed and answer with a node id, so if the check ran
        // after the call this could not pass -- and on a write "after the call" is the
        // difference between refusing and having already written.
        Path id = tmp.resolve("id.json");
        Files.writeString(id, Files.readString(Path.of("src/test/resources/gh/issue-node-id.json")));

        try (McpSyncClient client = LaunchedServer.withGh(tmp, (
                "echo ran >> " + tmp.resolve("ran.txt") + "\ncat " + id))) {

            CallToolResult result = client.callTool(new CallToolRequest("add_issue_comment",
                    Map.of("owner", "DemianLi", "repo", "project-mcp-sandbox",
                            "number", 1, "body", "   \n ")));

            assertThat(result.isError()).isTrue();
            assertThat(structured(result))
                    .containsEntry("remedy", "FIX_REQUEST")
                    .as("no gh ran, so there is no stderr to report")
                    .containsEntry("stderr", "");
            assertThat(text(result)).contains("blank");
            assertThat(Files.exists(tmp.resolve("ran.txt")))
                    .as("gh was never started")
                    .isFalse();
        }
    }
}

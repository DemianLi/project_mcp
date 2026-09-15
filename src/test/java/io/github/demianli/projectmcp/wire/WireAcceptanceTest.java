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
 * 以子行程執行 Server，驗證它透過 stdio 的回應。
 *
 * <p>只有這一層看得到 wire 上的行為：isError、structuredContent、訊息格式。這些由 callback
 * 層組出 CallToolResult、再由 transport 序列化產生，在 JVM 內看不到。
 *
 * <p>刻意保持精簡，只證明 wire 上的形狀；各 Remedy 的覆蓋放在成本較低的層。
 *
 * <p>離線執行：子行程的 PATH 上只有替身 gh，不會有真正的呼叫離開本機。
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
        // 找不到執行檔時沒有 stderr。PATH 只有空目錄，確保真的找不到 gh；
        // 否則 /usr/bin/gh 上的真正 gh 會讓測試連上 GitHub。
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
        // 失敗來源不在 GhCli：gh 成功回傳一個 pull request，由 IssueTools 拒絕。本測試驗證
        // 在 GhCli 之上建立的 ToolFailure 與在 GhCli 內建立的，在 wire 上形狀相同，而只有
        // 這一層看得到 wire 上的形狀。
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
        // list_labels 不在 IssueTools 上，用來驗證 ProjectMcpApplication 註解中的說法：
        // 「新增 Tool 就是新增一個 component，不必修改這個 class」。Tool 的掃描發生在另一個
        // 行程的 Spring context 裡，只有這一層看得到。
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
        // list_issue_comments 在 Envelope 上多了欄位。wire 上的 null 值必須是明確的 null，
        // 不能省略欄位，「沒有下一頁」才能與「此 Server 不分頁」區分。見
        // docs/design.md#list_issue_comments。
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
        // 在 `gh` 執行前就拒絕的失敗。替身會以非零結束，並輸出會被分類成完全不同結果的
        // stderr；若檢查發生在呼叫之後，這個斷言不可能通過。
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
        // 驗證寫入 Tool 的 annotations 與回應形狀。annotations（readOnlyHint、
        // destructiveHint、idempotentHint 皆為 false）經由 Spring AI 送上 wire。回應只有
        // 一個欄位，沒有 Envelope，也沒有 structuredContent。
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
        // 寫入前就拒絕的失敗。替身會成功並回傳 node id，所以若檢查發生在呼叫之後，本測試
        // 不可能通過；對寫入而言，「呼叫之後」就是拒絕與已經寫入的差別。
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

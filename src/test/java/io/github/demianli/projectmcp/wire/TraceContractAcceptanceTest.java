package io.github.demianli.projectmcp.wire;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/**
 * 驗證 Server 寫進 log 的內容，以及不得寫入的內容。
 *
 * <p>兩類斷言：一類保護格式（每次呼叫寫一行、含必要欄位），一類保護邊界（Server 寫進 log
 * 的都不是 GitHub 內容）。第二類若失守，log 就成了協定之外的一份使用者內容副本。GhCli 會
 * 原樣記錄 gh 的 stderr；若 GitHub 拒絕時引用留言內文，內容會經由遮蔽處理碰不到的路徑進入
 * 檔案，這是 docs/design.md#logging 記載的限制，因此本測試只斷言 Server 自己寫的內容。
 * log 導向獨立檔案，因為在共用檔案上斷言「不存在」，結果會取決於過去的執行。
 */
class TraceContractAcceptanceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 放在此 Server 讀回的 issue 中。 */
    private static final String READ_CANARY = "canary-read-8f21ac";

    /** 放在 Client 要求此 Server 寫入的留言中。 */
    private static final String WRITE_CANARY = "canary-write-3d90be";

    @TempDir
    Path tmp;

    @Test
    void aSuccessfulReadLeavesOneLineOfShapeAndNoContent() throws Exception {
        Path logFile = tmp.resolve("trace.log");
        Path payload = tmp.resolve("issue.json");
        Files.writeString(payload, """
                {"number":7,"title":"T","state":"OPEN","stateReason":null,
                 "body":"%s","labels":[],"assignees":[],
                 "author":{"login":"someone","is_bot":false,"name":""},
                 "url":"https://github.com/DemianLi/project-mcp-sandbox/issues/7",
                 "createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z",
                 "closedAt":null}
                """.formatted(READ_CANARY));

        try (McpSyncClient client =
                LaunchedServer.withGhLoggingTo(tmp, "cat " + payload, logFile)) {
            CallToolResult result = client.callTool(new CallToolRequest("get_issue",
                    Map.of("owner", "DemianLi", "repo", "project-mcp-sandbox", "number", 7)));

            assertThat(result.isError()).isFalse();
            assertThat(text(result))
                    .as("the Client is the one reader that gets the content")
                    .contains(READ_CANARY);
        }

        Map<String, Object> trace = onlyTraceLine(logFile, "get_issue ok");
        assertThat(trace)
                .as("shape of the call: tool, outcome, repo, callId, durationMs, resultBytes")
                .containsEntry("tool", "get_issue")
                .containsEntry("outcome", "ok")
                .containsEntry("repo", "DemianLi/project-mcp-sandbox")
                .containsKeys("callId", "durationMs", "resultBytes");
        assertThat(trace)
                .as("Remedy only on failures")
                .doesNotContainKey("remedy");

        assertThat(Files.readString(logFile))
                .as("the issue body reached the Client and stopped there")
                .doesNotContain(READ_CANARY);
    }

    @Test
    void aWriteThatFailsAtTheMutationLeavesTheCommentOutOfTheFile() throws Exception {
        Path logFile = tmp.resolve("trace.log");

        // 查 id 成功、mutation 失敗，所以 GhCli 記下的 argv 是以 `-f body=...` 結尾的那一個。
        // 若替身在第一次呼叫就失敗，測試會通過，但洩漏仍然存在。
        try (McpSyncClient client = LaunchedServer.withGhLoggingTo(tmp, """
                case "$*" in
                  *addComment*) echo "gh: refused" >&2; exit 1 ;;
                  *graphql*) echo '{"data":{"repository":{"issue":{"id":"I_abc"}}}}'; exit 0 ;;
                esac
                exit 1""", logFile)) {

            CallToolResult result = client.callTool(new CallToolRequest("add_issue_comment",
                    Map.of("owner", "DemianLi", "repo", "project-mcp-sandbox",
                            "number", 1, "body", WRITE_CANARY)));

            assertThat(result.isError()).isTrue();
        }

        String log = Files.readString(logFile);
        assertThat(log)
                .as("the comment a Client sent is content, and content does not enter the log")
                .doesNotContain(WRITE_CANARY);
        assertThat(log)
                .as("its length survives, because a blank body and a huge one fail differently")
                .contains("body=<" + WRITE_CANARY.length() + " chars>");
        assertThat(log)
                .as("everything else in the argv is shape and stays legible")
                .contains("subjectId=I_abc")
                .contains("addComment(input:");

        Map<String, Object> trace = onlyTraceLine(log, "add_issue_comment failed");
        assertThat(trace)
                .containsEntry("outcome", "error")
                // mutation 以非零結束且 stderr 無法辨識，因此分類為 UNKNOWN：無從得知
                // 失敗前寫入是否已成功。見 docs/design.md#writes。
                .containsEntry("remedy", "UNKNOWN")
                .containsKey("callId");
        assertThat(argvLine(log).get("callId"))
                .as("the two lines a failed write produces are joined by the call they "
                        + "belong to -- under a pooled dispatcher they are not guaranteed "
                        + "to be adjacent")
                .isEqualTo(trace.get("callId"));
    }

    private static Map<String, Object> onlyTraceLine(Path logFile, String message)
            throws Exception {
        return onlyTraceLine(Files.readString(logFile), message);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> onlyTraceLine(String log, String message) {
        List<Map<String, Object>> found = log.lines()
                .filter(line -> !line.isBlank())
                .map(line -> (Map<String, Object>) JSON.readValue(line, Map.class))
                .filter(entry -> message.equals(entry.get("message")))
                .toList();
        assertThat(found).as("one call, one trace line").hasSize(1);
        return found.get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> argvLine(String log) {
        List<Map<String, Object>> found = log.lines()
                .filter(line -> !line.isBlank())
                .map(line -> (Map<String, Object>) JSON.readValue(line, Map.class))
                .filter(entry -> String.valueOf(entry.get("message")).startsWith("`gh "))
                .toList();
        assertThat(found).as("one failed gh call, one argv line").hasSize(1);
        return found.get(0);
    }

    private static String text(CallToolResult result) {
        return result.content().stream()
                .map(Object::toString)
                .reduce("", String::concat);
    }
}

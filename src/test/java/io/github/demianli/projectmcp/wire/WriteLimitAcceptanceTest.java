package io.github.demianli.projectmcp.wire;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 驗證寫入上限在同一個執行中的 Server 內跨呼叫生效。
 *
 * <p>單元測試驗證限流視窗；本測試證明 Server 的所有呼叫共用同一個 limiter，而不是每次
 * 呼叫各建一個。
 */
class WriteLimitAcceptanceTest {

    @TempDir Path tmp;

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(CallToolResult result) {
        return (Map<String, Object>) result.structuredContent();
    }

    @Test
    void theEightyFirstWriteInAMinuteIsRefusedAndStartsNoGh() throws Exception {
        Path id = tmp.resolve("id.json");
        Path added = tmp.resolve("added.json");
        Path count = tmp.resolve("count.txt");
        Files.writeString(id, Files.readString(Path.of("src/test/resources/gh/issue-node-id.json")));
        Files.writeString(added, Files.readString(Path.of("src/test/resources/gh/add-comment.json")));

        // 每次寫入呼叫 gh 兩次：奇數次是查 id，偶數次是 mutation。
        String standIn = "n=$(cat " + count + " 2>/dev/null || echo 0)\n"
                + "n=$((n+1)); echo $n > " + count + "\n"
                + "if [ $((n % 2)) -eq 1 ]; then cat " + id + "; else cat " + added + "; fi";

        Map<String, Object> arguments = ToolCalls.forTool("add_issue_comment");

        try (McpSyncClient client = LaunchedServer.withGh(tmp, standIn)) {
            for (int i = 1; i <= 80; i++) {
                CallToolResult ok = client.callTool(new CallToolRequest("add_issue_comment", arguments));
                assertThat(ok.isError()).as("write %d", i).isFalse();
            }

            CallToolResult refused =
                    client.callTool(new CallToolRequest("add_issue_comment", arguments));

            assertThat(refused.isError()).isTrue();
            assertThat(structured(refused)).containsEntry("remedy", "RETRY");
            assertThat(structured(refused)).containsKey("retryAfterSeconds");
        }

        assertThat(Files.readString(count).trim())
                .as("80 writes made 160 gh calls, and the refused one made none")
                .isEqualTo("160");
    }
}

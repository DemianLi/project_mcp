package io.github.demianli.projectmcp.wire;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 驗證每個 Tool 的失敗結果都完整帶有 Remedy 與 stderr。
 *
 * <p>失敗契約適用於整個 Server（見 docs/design.md#failure-contract）。每個 Tool 都必須捕捉
 * ToolFailure 並經由 ToolResults 回傳。Spring AI 的 callback 遇到任何 RuntimeException 都會
 * 產生 isError: true 與文字訊息，但沒有 structuredContent；Tool 若讓 ToolFailure 漏出去，
 * Remedy 會悄悄消失，結果看起來卻仍是格式正確的錯誤。本測試不靠記得有哪些 Tool，而是對
 * 每個已宣告的 Tool 斷言。
 *
 * <p>成本低：一個 Server、一個一律以相同方式失敗的替身、每個 Tool 呼叫一次，不涉及逾時。
 */
class FailureContractAcceptanceTest {

    @TempDir Path tmp;

    /**
     * 每個 Tool 的路徑都以相同方式碰到的 stderr。
     *
     * <p>它會被分類為 {@code FIX_REQUEST}，但分類成哪個 Remedy 由 {@code GhCliFailureTest}
     * 驗證，不在此處。這裡在意的是無論分類結果為何，結構化的那一半都有帶上，所以下方斷言
     * 讀回 stderr，而不是釘住 Remedy 的值。
     */
    private static final String STDERR =
            "GraphQL: Could not resolve to a Repository with the name 'DemianLi/nope'. "
                    + "(repository)";

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(CallToolResult result) {
        return (Map<String, Object>) result.structuredContent();
    }

    @Test
    void everyToolReportsAFailingGhWithItsRemedyIntact() throws Exception {
        try (McpSyncClient client = LaunchedServer.withGh(tmp,
                "cat >&2 <<'STDERR'\n" + STDERR + "\nSTDERR\nexit 1")) {

            List<Tool> tools = client.listTools().tools();

            assertThat(tools)
                    .as("an empty Tool list would make everything below pass without "
                            + "asserting anything")
                    .isNotEmpty();

            for (Tool tool : tools) {
                Map<String, Object> arguments = ToolCalls.forTool(tool.name());

                assertThat(arguments)
                        .as("`%s` is declared, so this test needs a call that reaches gh -- "
                                + "add it to ToolCalls", tool.name())
                        .isNotNull();

                CallToolResult result =
                        client.callTool(new CallToolRequest(tool.name(), arguments));

                assertThat(result.isError())
                        .as("`%s` could not do its job", tool.name())
                        .isTrue();

                // 關鍵斷言。Spring AI 的 fallback 會讓這裡是 null，所以讓 ToolFailure 漏出去
                // 的 Tool 會在這裡失敗，而不是在上面的 isError。
                assertThat(structured(result))
                        .as("`%s` must report its failure through ToolResults, not by "
                                + "letting it escape -- an escaped ToolFailure still answers "
                                + "isError, with the Remedy and the stderr gone", tool.name())
                        .isNotNull()
                        .containsKey("remedy")
                        .containsEntry("stderr", STDERR);
            }
        }
    }
}

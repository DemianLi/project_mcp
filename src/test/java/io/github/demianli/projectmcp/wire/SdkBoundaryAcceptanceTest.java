package io.github.demianli.projectmcp.wire;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 驗證兩件由 SDK 決定、src/main/java 中沒有對應程式碼的行為：capabilities 與 schema
 * 驗證失敗。
 *
 * <p>這些測試把 SDK 目前的行為釘住作為警報線。SDK 是有自己路線圖的相依套件，任一行為
 * 改變，本 Server 的契約都會悄悄跟著改變。見 docs/design.md#identity-and-permissions。
 */
class SdkBoundaryAcceptanceTest {

    @TempDir Path tmp;

    /**
     * 驗證失敗契約的缺口：SDK 在 dispatch 前做 schema 驗證。
     *
     * <p>ToolInputValidator 在 dispatch 前依 inputSchema 驗證參數，產生只有 content 與
     * isError 的 CallToolResult，沒有 Remedy。Tool 從未執行，因此不會建立 ToolFailure。
     * 本測試把目前行為當作警報線：若 SDK 改為在此填入 structuredContent，缺口就自動補上。
     */
    @Test
    void aCallTheSchemaRejectsCarriesNoRemedy() throws Exception {
        try (McpSyncClient client = LaunchedServer.withGh(tmp, "exit 1")) {

            CallToolResult result = client.callTool(new CallToolRequest(
                    "get_issue", Map.of("owner", "DemianLi")));   // 缺少 `repo` 與 `number`

            assertThat(result.isError())
                    .as("the SDK does refuse it, and refuses it as a Tool result rather "
                            + "than a protocol error")
                    .isTrue();

            assertThat(result.structuredContent())
                    .as("the failure contract starts at the Tool method; schema rejection "
                            + "is answered before dispatch, carrying no Remedy. This null "
                            + "signals the SDK's current behavior; if it changes, the hole "
                            + "may close")
                    .isNull();

            assertThat(result.content())
                    .as("whatever else is true, the Client is told something")
                    .isNotEmpty();
        }
    }

    /**
     * 驗證 Server 宣告的 capabilities。
     *
     * <p>Spring AI 預設開啟 resources、prompts 與 completions；本 Server 只提供 Tools，由
     * application.yml 的三行關閉它們。logging 無法關閉但未使用，本測試記下這一點。
     */
    @Test
    void onlyTheImplementedCapabilitiesAreDeclared() throws Exception {
        try (McpSyncClient client = LaunchedServer.withGh(tmp, "exit 1")) {

            ServerCapabilities capabilities = client.getServerCapabilities();

            assertThat(capabilities.tools())
                    .as("five Tools, and Tools are the whole surface")
                    .isNotNull();

            assertThat(capabilities.resources())
                    .as("no Resources declared")
                    .isNull();
            assertThat(capabilities.prompts())
                    .as("no Prompts are registered")
                    .isNull();
            assertThat(capabilities.completions())
                    .as("nothing here completes anything")
                    .isNull();

            assertThat(capabilities.logging())
                    .as("declared and unused, and it cannot be switched off from "
                            + "application.yml -- see the comment there")
                    .isNotNull();
        }
    }
}

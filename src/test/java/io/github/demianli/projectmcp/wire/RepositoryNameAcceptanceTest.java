package io.github.demianli.projectmcp.wire;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 驗證沒有任何 Tool 會讓 owner 或 repo 中的斜線傳到 gh。
 *
 * <p>gh 的 --repo 接受 [HOST/]OWNER/REPO，斜線可能把請求導向別處。拒絕必須是 FIX_REQUEST，
 * 以免 Client 用同樣的惡意輸入重試。測試走訪每個 Tool 而不是手寫清單，新增的 Tool 一寫出來
 * 就被涵蓋。替身一旦執行就會寫下標記，藉此證明 gh 從未啟動。
 */
class RepositoryNameAcceptanceTest {

    @TempDir Path tmp;

    /** 可能把 gh 請求導向別處的 host:port/owner 形狀。 */
    private static final String HOST_SHAPED_OWNER = "127.0.0.1:8099/a";

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(CallToolResult result) {
        return (Map<String, Object>) result.structuredContent();
    }

    @Test
    void noToolLetsASlashReachGh() throws Exception {
        Path started = tmp.resolve("gh-was-started");

        try (McpSyncClient client = LaunchedServer.withGh(tmp,
                "echo ran > " + started + "\necho '[]'")) {

            List<Tool> tools = client.listTools().tools();
            assertThat(tools).isNotEmpty();

            for (Tool tool : tools) {
                Map<String, Object> arguments = ToolCalls.forTool(tool.name());
                assertThat(arguments)
                        .as("`%s` is declared but ToolCalls has no call for it", tool.name())
                        .isNotNull();

                for (String parameter : List.of("owner", "repo")) {
                    Map<String, Object> poisoned = new HashMap<>(arguments);
                    poisoned.put(parameter, HOST_SHAPED_OWNER);

                    CallToolResult result =
                            client.callTool(new CallToolRequest(tool.name(), poisoned));

                    assertThat(result.isError())
                            .as("`%s` accepted a `%s` containing a slash", tool.name(),
                                    parameter)
                            .isTrue();

                    assertThat(structured(result))
                            .as("`%s` must refuse a slashed `%s` with FIX_REQUEST -- RETRY "
                                    + "is what shipped, and it sends the Client back to the "
                                    + "same host", tool.name(), parameter)
                            .isNotNull()
                            .containsEntry("remedy", "FIX_REQUEST");

                    assertThat((String) structured(result).get("message"))
                            .as("the refusal has to name the parameter to be actionable")
                            .contains("`" + parameter + "`");
                }
            }

            assertThat(Files.exists(started))
                    .as("gh was started at least once, so a request left this machine "
                            + "before the parameter was refused")
                    .isFalse();
        }
    }
}

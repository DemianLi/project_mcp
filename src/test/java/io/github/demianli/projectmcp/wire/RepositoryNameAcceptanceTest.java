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
 * Tests that no Tool lets a slash in owner or repo reach gh.
 *
 * <p>gh's --repo accepts [HOST/]OWNER/REPO, so a slash can redirect the request. The refusal
 * must be FIX_REQUEST to prevent the Client from retrying the same malicious input. Every
 * Tool is tested, not a hand-written list, so the sixth Tool is covered by being written, not
 * remembered. The stand-in writes a marker when executed, proving gh never started.
 */
class RepositoryNameAcceptanceTest {

    @TempDir Path tmp;

    /** A host:port/owner shape that could redirect the gh request. */
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

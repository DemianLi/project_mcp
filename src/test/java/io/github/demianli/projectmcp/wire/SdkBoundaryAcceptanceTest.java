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
 * Tests two things the SDK decides without code in src/main/java: capabilities and schema
 * validation failures.
 *
 * <p>These tests pin current SDK behavior as a tripwire. The SDK is a dependency with its
 * own roadmap; if either behavior changes, this Server's contract changes silently. See
 * docs/design.md#identity-and-permissions.
 */
class SdkBoundaryAcceptanceTest {

    @TempDir Path tmp;

    /**
     * Tests the failure contract gap: SDK schema validation before dispatch.
     *
     * <p>ToolInputValidator validates arguments against inputSchema before dispatch, building
     * a CallToolResult with content and isError only — no Remedy. A Tool never runs, so no
     * ToolFailure is constructed. This test asserts the current behavior as a tripwire: if
     * the SDK grows a way to populate structuredContent here, the hole closes automatically.
     */
    @Test
    void aCallTheSchemaRejectsCarriesNoRemedy() throws Exception {
        try (McpSyncClient client = LaunchedServer.withGh(tmp, "exit 1")) {

            CallToolResult result = client.callTool(new CallToolRequest(
                    "get_issue", Map.of("owner", "DemianLi")));   // `repo` and `number` missing

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
     * Tests the declared Server capabilities.
     *
     * <p>Spring AI defaults to resources, prompts and completions on. Three lines in
     * application.yml turn them off because this Server is Tools only. logging is
     * undisabled but unused, which this test documents.
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

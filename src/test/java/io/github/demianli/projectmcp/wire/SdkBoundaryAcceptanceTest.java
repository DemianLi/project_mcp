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
 * Acceptance layer: the two things the SDK decides on this Server's behalf.
 *
 * <p>Every other test here asserts something this repo wrote. These two assert what
 * arrives at a Client without any code in {@code src/main/java} having produced it — the
 * capability set, and the shape of a call the SDK refuses before a Tool method runs. Both
 * were found by driving a built jar with the MCP Inspector and with hand-written JSON-RPC,
 * and neither would have been found by reading this repo's source, because the behaviour
 * is not in it.
 *
 * <p>Both assertions pin a <em>current</em> fact rather than a desired one. That is the
 * point: the SDK is a dependency with a roadmap, and the day one of these changes is the
 * day this Server's contract changes without a commit. See ADR-0011.
 */
class SdkBoundaryAcceptanceTest {

    @TempDir Path tmp;

    /**
     * The hole in the failure contract, held open on purpose.
     *
     * <p>{@code io.modelcontextprotocol.util.ToolInputValidator} validates arguments against
     * {@code inputSchema} in {@code McpAsyncServer} <em>before</em> dispatch, and builds its
     * own {@code CallToolResult} with {@code content} and {@code isError} and nothing else.
     * So a call the schema rejects never reaches {@code ToolResults}, and comes back with no
     * Remedy — the one thing ADR-0002 promises every failure carries.
     *
     * <p>Two escapes were measured and neither works. {@code validateToolInputs(false)}
     * through an {@code McpSyncServerCustomizer} hands the arguments to Spring AI's binder
     * instead, which answers a missing {@code int} with
     * {@code java.lang.NullPointerException: Cannot invoke "java.lang.Number.intValue()"} —
     * still no {@code structuredContent}, and now leaking JVM internals. A custom
     * {@code JsonSchemaValidator} controls the message text but not the result's shape,
     * because {@code ToolInputValidator} builds it.
     *
     * <p>Which is why this asserts {@code structuredContent} is <strong>null</strong>. Read
     * it as a tripwire, not an endorsement: when the SDK grows a seam here, this test fails
     * and someone gets to close the hole.
     *
     * <p>No {@code gh} is needed — nothing reaches it.
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
                    .as("the failure contract starts at the Tool method body; a schema "
                            + "rejection is answered before that and carries no Remedy. If "
                            + "this is no longer null, the SDK has changed and ADR-0011's "
                            + "reasoning needs rereading -- the hole may now be closable")
                    .isNull();

            assertThat(result.content())
                    .as("whatever else is true, the Client is told something")
                    .isNotEmpty();
        }
    }

    /**
     * What this Server tells a Client it can do.
     *
     * <p>Spring AI turns resources, prompts and completions on by default. They were being
     * advertised in {@code InitializeResult} while every corresponding list came back empty,
     * so a Client probing this Server was told it had a Resource face and a Prompt face it
     * does not have — ADR-0004 decided against both. Three lines in {@code application.yml}
     * turn them off; this is what keeps them off.
     *
     * <p>{@code logging} stays. There is no property for it, and removing it would mean
     * replacing the whole capability set through a customizer bean for a capability nothing
     * asks about. It is declared and unused, and asserting it here says so out loud rather
     * than leaving the next reader to discover it on the wire.
     */
    @Test
    void onlyTheImplementedCapabilitiesAreDeclared() throws Exception {
        try (McpSyncClient client = LaunchedServer.withGh(tmp, "exit 1")) {

            ServerCapabilities capabilities = client.getServerCapabilities();

            assertThat(capabilities.tools())
                    .as("five Tools, and Tools are the whole surface")
                    .isNotNull();

            assertThat(capabilities.resources())
                    .as("ADR-0004: no Resources, so nothing should say otherwise")
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

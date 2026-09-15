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
 * Tests that every Tool returns failure results with Remedy and stderr intact.
 *
 * <p>The failure contract is Server-wide (see docs/design.md#failure-contract). Every Tool
 * must catch ToolFailure and return it via ToolResults. Spring AI's callback produces
 * isError: true and text message for any RuntimeException, but does not produce
 * structuredContent. If a Tool lets ToolFailure escape, the Remedy vanishes silently while
 * the result still looks like a well-formed error. This test drives every Tool to catch that
 * mistake, not by remembering which ones were written, but by asserting on every declared
 * Tool.
 *
 * <p>Cheap: one Server, one stand-in that fails identically, one call per Tool. No timeouts.
 */
class FailureContractAcceptanceTest {

    @TempDir Path tmp;

    /**
     * One stderr that every Tool's route reaches the same way.
     *
     * <p>It classifies — {@code FIX_REQUEST} — but which Remedy it lands on is
     * {@code GhCliFailureTest}'s subject, not this one's. What matters here is that whatever
     * was decided arrives with the structured half attached, which is why the assertion below
     * reads the stderr back rather than pinning the Remedy's value.
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

                // The half with teeth. Spring AI's fallback leaves this null, so a Tool that
                // let the ToolFailure escape lands here rather than on isError above.
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

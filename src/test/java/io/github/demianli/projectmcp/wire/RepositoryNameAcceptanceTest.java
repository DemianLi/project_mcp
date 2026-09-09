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
 * Acceptance layer: no Tool lets a slash in {@code owner} or {@code repo} reach {@code gh}.
 *
 * <p><strong>The failure this stands on.</strong> {@code gh}'s {@code --repo} takes
 * {@code [HOST/]OWNER/REPO}, so before issue #37 an {@code owner} of
 * {@code 127.0.0.1:8099/a} sent this Server to {@code https://127.0.0.1:8099/api/graphql} —
 * measured through this same wire, against the packaged 0.1.0. The caller chose where the
 * Server made its next request, and in the deployment shape this Server is built for that
 * caller is a model that has just read someone else's issue text.
 *
 * <p><strong>Two assertions, and the second is the one that was actually wrong.</strong>
 * A Remedy of {@code FIX_REQUEST} is the contract; what shipped was {@code RETRY} with "the
 * network looks unavailable", because {@code gh}'s {@code dial tcp … connection refused}
 * matches {@code GhStderr}'s network row. ADR-0002 calls a confident wrong Remedy its worst
 * category, and it is worse than the redirect: a Client told to retry sends the same request
 * to the same host again. So this test pins the Remedy, not merely that something failed.
 *
 * <p><strong>And that {@code gh} never started.</strong> The stand-in writes a file the
 * moment it runs, and the file must not exist — a guard that refused after spawning the
 * subprocess would satisfy every assertion about the response while the request still left
 * the machine.
 *
 * <p>Every Tool is driven from {@code listTools()} rather than a hand-written list, for the
 * reason {@link FailureContractAcceptanceTest} gives: the sixth Tool should be covered by
 * having been written, not by having been remembered. Two Tools compose {@code --repo} and
 * three pass the halves as GraphQL variables where a slash is inert; the rule is the same for
 * all five on purpose, so that one bad parameter gets one answer whichever Tool receives it.
 */
class RepositoryNameAcceptanceTest {

    @TempDir Path tmp;

    /** The shape from #37: a host, a port, and an owner behind it. */
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

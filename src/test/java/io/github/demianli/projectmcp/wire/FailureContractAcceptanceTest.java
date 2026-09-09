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
 * Acceptance layer: every Tool reports a failing {@code gh} with its Remedy intact.
 *
 * <p>ADR-0002 makes the failure contract Server-wide, and {@code ToolResults} says so in
 * prose — but until now only {@code list_issues} was watched keeping it. The other four
 * inherited the contract by their authors writing it out, and the Acceptance layer took
 * their word for it.
 *
 * <p><strong>What has teeth here, and what does not.</strong> Not {@code isError}: a
 * {@code ToolFailure} that escapes a Tool method is a plain {@code RuntimeException}, and
 * Spring AI's own callback answers it with {@code isError: true} and a text message. An
 * assertion on {@code isError} alone would pass while the contract was broken. What Spring
 * AI does <em>not</em> produce is {@code structuredContent} — so the Remedy and the stderr,
 * the half ADR-0002 calls authoritative, vanish while the result still looks like a
 * well-formed refusal. That is the assertion below, and it was arrived at by measurement:
 * removing a {@code catch} from {@code list_issues} failed on {@code structuredContent} and
 * not on {@code isError}.
 *
 * <p><strong>The mistake it catches.</strong> Since {@code ToolResults} narrowed to
 * {@code ToolResults.attempt}, forgetting to catch no longer compiles. What still compiles
 * is doing the work outside the lambda —
 *
 * <pre>{@code
 * String out = gh.run(args);                       // outside: escapes to Spring AI
 * return ToolResults.attempt("list_issues", owner, repo, () -> map(out));
 * }</pre>
 *
 * <p>— which is a natural enough shape to reach for, produces a result that reads as correct,
 * and silently drops the Remedy. Every Tool is driven here rather than a chosen one, so the
 * fifth and sixth Tool are covered by having been written, not by having been remembered.
 *
 * <p>Cheap by construction: one Server, one stand-in that fails the same way for everyone,
 * one call per Tool. None of the 30 seconds {@link WritePartitionAcceptanceTest} has to pay
 * — a non-zero exit is answered at once.
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

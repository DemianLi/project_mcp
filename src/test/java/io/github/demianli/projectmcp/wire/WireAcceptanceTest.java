package io.github.demianli.projectmcp.wire;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.demianli.projectmcp.gh.FakeGh;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Acceptance layer: the Server, launched as a Client launches it, answering over Stdio.
 *
 * <p>This is the only layer that can see what this suite is for. {@code isError},
 * {@code structuredContent} and the message duplication are produced by the callback layer
 * that builds a {@code CallToolResult} and by the transport that serialises it — none of
 * them exists below the wire, so no in-JVM test can observe them.
 *
 * <p>Kept thin on purpose: it proves the shape, and coverage of every Remedy lives in the
 * cheaper layer next door.
 *
 * <p>Offline by construction. The child process gets a {@code PATH} containing one thing —
 * a stand-in {@code gh} — so the real binary is unreachable even if it is installed, and no
 * request leaves the machine.
 */
class WireAcceptanceTest {

    @TempDir Path tmp;

    /**
     * Launches the Server the way a Client would.
     *
     * <p>Started from the test's own classpath rather than the packaged jar: {@code mvn test}
     * runs before {@code package}, so requiring the jar would make the suite depend on a
     * build step that has not happened yet. Everything that matters here is identical —
     * same main class, same Spring context, same Stdio transport, a genuinely separate
     * process, and a real JSON-RPC conversation across a pipe.
     */
    private McpSyncClient serverWith(String ghScriptDir) {
        var params = ServerParameters.builder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString())
                .args("-cp", System.getProperty("java.class.path"),
                        "io.github.demianli.projectmcp.ProjectMcpApplication")
                // The stand-in comes first and the real gh is nowhere on this PATH --
                // /usr/bin and /bin carry no gh, only the shell utilities the stand-in
                // itself needs. Nothing here can reach the network.
                .env(Map.of("PATH", ghScriptDir + ":/usr/bin:/bin"))
                .build();

        var client = McpClient.sync(new StdioClientTransport(params, McpJsonDefaults.getMapper()))
                .requestTimeout(Duration.ofSeconds(30))
                .build();
        client.initialize();
        return client;
    }

    private static CallToolResult listIssues(McpSyncClient client) {
        return client.callTool(new CallToolRequest("list_issues",
                Map.of("owner", "DemianLi", "repo", "project_mcp")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(CallToolResult result) {
        return (Map<String, Object>) result.structuredContent();
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().get(0)).text();
    }

    private String dirWithFakeGh(String body) throws IOException {
        FakeGh.writing(tmp, body);
        return tmp.toString();
    }

    @Test
    void aFailureCrossesTheWireAsAnErrorResultWithBothHalves() throws Exception {
        String stderr = "GraphQL: Could not resolve to a Repository with the name "
                + "'DemianLi/project_mcp'. (repository)";
        try (McpSyncClient client = serverWith(dirWithFakeGh(
                "cat >&2 <<'STDERR'\n" + stderr + "\nSTDERR\nexit 1"))) {

            CallToolResult result = listIssues(client);

            assertThat(result.isError()).isTrue();

            Map<String, Object> structured = structured(result);
            assertThat(structured)
                    .as("structuredContent survives the wire -- an error result skips "
                            + "output-schema validation, so it is forwarded untouched")
                    .containsEntry("remedy", "FIX_REQUEST")
                    .containsEntry("stderr", stderr);

            String message = (String) structured.get("message");
            assertThat(text(result))
                    .as("a human reading a Client that renders only text still sees a sentence")
                    .startsWith(message)
                    .contains(stderr);
            assertThat(text(result).split(java.util.regex.Pattern.quote(message), -1))
                    .as("the sentence appears once. Throwing a RuntimeException put it twice")
                    .hasSize(2);
            assertThat(text(result))
                    .as("the gh argv stays out of what a Client can see")
                    .doesNotContain("issue list", "--repo", "--json");
        }
    }

    @Test
    void anAbsentGhCrossesTheWireToo() throws Exception {
        // The failure with no stderr to classify, and the one that never reaches a non-zero
        // exit. An empty directory on PATH is exactly how a real deployment gets this.
        Path empty = Files.createDirectory(tmp.resolve("empty"));
        try (McpSyncClient client = serverWith(empty.toString())) {
            CallToolResult result = listIssues(client);

            assertThat(result.isError()).isTrue();
            assertThat(structured(result))
                    .containsEntry("remedy", "ASK_OPERATOR")
                    .containsEntry("stderr", "");
        }
    }

    @Test
    void successCrossesTheWireInTheEnvelopeAndNothingElse() throws Exception {
        String fixture = Files.readString(Path.of("src/test/resources/gh/issue-list.json"));
        Path payload = tmp.resolve("payload.json");
        Files.writeString(payload, fixture);

        try (McpSyncClient client = serverWith(dirWithFakeGh("cat " + payload))) {
            CallToolResult result = listIssues(client);

            assertThat(result.isError()).isFalse();
            assertThat(result.structuredContent())
                    .as("ADR-0001 chose TEXT mode; the success path is unchanged by the "
                            + "failure contract")
                    .isNull();
            assertThat(text(result))
                    .startsWith("{\"items\":[")
                    .contains("\"count\":3", "\"truncated\":false");
        }
    }
}

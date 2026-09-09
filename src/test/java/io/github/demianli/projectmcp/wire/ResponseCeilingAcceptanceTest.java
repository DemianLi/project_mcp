package io.github.demianli.projectmcp.wire;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/**
 * What happens when one response is too big, on both sides of the line.
 *
 * <p>Above the ceiling the call is refused with a Remedy and the Server carries on. Above
 * what the <em>heap</em> can hold — which the ceiling exists to keep out of reach — there is
 * no answer to give, and the Server stops being one rather than staying up and mute.
 *
 * <p><strong>Why the second test runs a real Server out of memory.</strong> Before this,
 * that case produced no response of any kind, no line in the log, and a process that was
 * still holding the pipe minutes after its stdin had closed — measured, and the reason
 * ADR-0015 exists. None of that is visible to a test that stubs the failure; the branch is
 * about what a JVM does after an {@code OutOfMemoryError}, so the only honest provocation is
 * an {@code OutOfMemoryError}.
 */
class ResponseCeilingAcceptanceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path tmp;

    /** A `gh` that prints one issue whose body is {@code bodyBytes} of filler. */
    private static String ghEmitting(int bodyBytes) {
        return "printf '{\"number\":7,\"title\":\"T\",\"state\":\"OPEN\",\"body\":\"'\n"
                + "head -c " + bodyBytes + " /dev/zero | tr '\\0' 'x'\n"
                + "printf '\",\"labels\":[],\"assignees\":[],"
                + "\"author\":{\"login\":\"a\",\"is_bot\":false,\"name\":\"\"},"
                + "\"url\":\"https://github.com/o/r/issues/7\","
                + "\"createdAt\":\"2026-01-01T00:00:00Z\","
                + "\"updatedAt\":\"2026-01-01T00:00:00Z\"}'";
    }

    private static CallToolResult getIssue(McpSyncClient client) {
        return client.callTool(new CallToolRequest("get_issue",
                Map.of("owner", "o", "repo", "r", "number", 7)));
    }

    @Test
    void aResponseOverTheCeilingIsRefusedAndTheServerLivesOn() throws Exception {
        // 9 MB against an 8 MB ceiling. Deliberately just over: a test that sent 100 MB
        // would pass on a machine where the ceiling did nothing and the heap did the work.
        try (McpSyncClient client = LaunchedServer.withGh(tmp, ghEmitting(9 * 1024 * 1024))) {
            CallToolResult refused = getIssue(client);

            assertThat(refused.isError()).isTrue();
            assertThat(structured(refused))
                    .as("FIX_REQUEST, not UNKNOWN: this Server invented this failure, named "
                            + "it and counted the bytes")
                    .containsEntry("remedy", "FIX_REQUEST")
                    .containsEntry("stderr", "");
            // Asserted as a relation rather than a literal: the exact total is the body
            // plus whatever the stand-in wraps it in, and pinning that arithmetic would make
            // this test about the fixture.
            String sentence = text(refused);
            assertThat(sentence)
                    .as("the ceiling is named, because a refusal without the number it "
                            + "enforces cannot be acted on")
                    .contains(String.valueOf(8 * 1024 * 1024));
            long reported = Long.parseLong(
                    sentence.replaceAll("(?s).*returned (\\d+) bytes.*", "$1"));
            assertThat(reported)
                    .as("and the size that was refused, which is over the body it carried")
                    .isGreaterThan(9L * 1024 * 1024)
                    .isLessThan(9L * 1024 * 1024 + 4096);

            // The refusal is not fatal to anything. A second call on the same connection is
            // the difference between a ceiling and a crash.
            assertThat(refused.isError()).isTrue();
            assertThat(client.listTools().tools()).hasSize(5);
        }
    }

    @Test
    void aServerThatRunsOutOfMemoryStopsBeingOneInsteadOfGoingQuiet() throws Exception {
        Path logFile = tmp.resolve("fatal.log");

        // 7 MB is under the ceiling, so the ceiling lets it through; 32 MB of heap is not
        // enough to decode it, parse it, map it and serialise it back. That gap is where the
        // fatal branch lives.
        assertThatThrownBy(() -> {
            try (McpSyncClient client = LaunchedServer.withGhAndHeap(
                    tmp, ghEmitting(7 * 1024 * 1024), "32m", logFile)) {
                getIssue(client);
            }
        })
                .as("the Server is gone, so the call cannot come back -- which is the point: "
                        + "a Client can restart a Server that died, and can only time out "
                        + "against one that is up and will never answer")
                .isNotNull();

        List<Map<String, Object>> trace = traceLines(logFile);
        assertThat(trace)
                .as("the line ADR-0013 promised, on the one path that used to leave none")
                .hasSize(1);
        assertThat(trace.get(0))
                .containsEntry("tool", "get_issue")
                .containsEntry("outcome", "fatal")
                .containsKey("callId")
                .containsKey("durationMs");
        assertThat(Files.readString(logFile))
                .as("and what killed it, by name")
                .contains("OutOfMemoryError");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> traceLines(Path logFile) throws Exception {
        return Files.readString(logFile).lines()
                .filter(line -> !line.isBlank())
                .map(line -> (Map<String, Object>) JSON.readValue(line, Map.class))
                .filter(entry -> entry.containsKey("tool"))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(CallToolResult result) {
        return (Map<String, Object>) result.structuredContent();
    }

    private static String text(CallToolResult result) {
        return result.content().stream().map(Object::toString).reduce("", String::concat);
    }
}

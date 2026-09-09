package io.github.demianli.projectmcp.wire;

import static org.assertj.core.api.Assertions.assertThat;

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
 * What a call leaves behind, and what it must not.
 *
 * <p>Two assertions with very different stakes. The first — that a call writes one line
 * carrying the fields ADR-0013 lists — protects a format: get it wrong and a reader is
 * inconvenienced. The second — that nothing this Server <em>puts</em> into the file is
 * content — protects a boundary: get it wrong and this Server's log becomes a copy of GitHub
 * content sitting outside the protocol, on a disk nothing here ever cleans.
 *
 * <p><strong>What this cannot assert.</strong> {@code GhCli} logs {@code gh}'s stderr
 * verbatim, and the stand-in's stderr is written by this test. If GitHub ever answers a
 * rejected write by quoting the body back, content reaches the file by a route no redaction
 * here touches — and eliding stderr instead would leave a failure with no evidence at all.
 * The claim is therefore about what this Server writes, not about every byte in the file.
 * Named as a limitation in ADR-0013 rather than left to be discovered.
 *
 * <p><strong>The second one has already been broken once.</strong> Before ADR-0013,
 * {@code GhCli} logged the argv of a failed call verbatim, and an {@code add_issue_comment}
 * whose <em>mutation</em> failed put the whole comment in the file — the lookup failing was
 * not enough, which is why five Tools' worth of green tests never showed it. That is the
 * regression this test exists to catch, and it is why the write half drives the failure into
 * the second call rather than the first.
 *
 * <p>Read from a redirected file rather than {@code logs/project-mcp.log}: asserting that a
 * string is <em>absent</em> from a file every other run appends to would pass or fail on
 * history rather than on this call.
 */
class TraceContractAcceptanceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** In the issue this Server reads back. */
    private static final String READ_CANARY = "canary-read-8f21ac";

    /** In the comment a Client asks this Server to write. */
    private static final String WRITE_CANARY = "canary-write-3d90be";

    @TempDir
    Path tmp;

    @Test
    void aSuccessfulReadLeavesOneLineOfShapeAndNoContent() throws Exception {
        Path logFile = tmp.resolve("trace.log");
        Path payload = tmp.resolve("issue.json");
        Files.writeString(payload, """
                {"number":7,"title":"T","state":"OPEN","stateReason":null,
                 "body":"%s","labels":[],"assignees":[],
                 "author":{"login":"someone","is_bot":false,"name":""},
                 "url":"https://github.com/DemianLi/project-mcp-sandbox/issues/7",
                 "createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z",
                 "closedAt":null}
                """.formatted(READ_CANARY));

        try (McpSyncClient client =
                LaunchedServer.withGhLoggingTo(tmp, "cat " + payload, logFile)) {
            CallToolResult result = client.callTool(new CallToolRequest("get_issue",
                    Map.of("owner", "DemianLi", "repo", "project-mcp-sandbox", "number", 7)));

            assertThat(result.isError()).isFalse();
            assertThat(text(result))
                    .as("the Client is the one reader that gets the content")
                    .contains(READ_CANARY);
        }

        Map<String, Object> trace = onlyTraceLine(logFile, "get_issue ok");
        assertThat(trace)
                .as("the shape of the call, and ADR-0013 fixes which fields say it")
                .containsEntry("tool", "get_issue")
                .containsEntry("outcome", "ok")
                .containsEntry("repo", "DemianLi/project-mcp-sandbox")
                .containsKeys("callId", "durationMs", "resultBytes");
        assertThat(trace)
                .as("a Remedy belongs to a failure and nothing else")
                .doesNotContainKey("remedy");

        assertThat(Files.readString(logFile))
                .as("the issue body reached the Client and stopped there")
                .doesNotContain(READ_CANARY);
    }

    @Test
    void aWriteThatFailsAtTheMutationLeavesTheCommentOutOfTheFile() throws Exception {
        Path logFile = tmp.resolve("trace.log");

        // The lookup succeeds and the mutation does not, so the argv GhCli logs is the one
        // ending in `-f body=...`. A stand-in that failed on the first call would leave this
        // test green with the leak still open.
        try (McpSyncClient client = LaunchedServer.withGhLoggingTo(tmp, """
                case "$*" in
                  *addComment*) echo "gh: refused" >&2; exit 1 ;;
                  *graphql*) echo '{"data":{"repository":{"issue":{"id":"I_abc"}}}}'; exit 0 ;;
                esac
                exit 1""", logFile)) {

            CallToolResult result = client.callTool(new CallToolRequest("add_issue_comment",
                    Map.of("owner", "DemianLi", "repo", "project-mcp-sandbox",
                            "number", 1, "body", WRITE_CANARY)));

            assertThat(result.isError()).isTrue();
        }

        String log = Files.readString(logFile);
        assertThat(log)
                .as("the comment a Client sent is content, and content does not enter the log")
                .doesNotContain(WRITE_CANARY);
        assertThat(log)
                .as("its length survives, because a blank body and a huge one fail differently")
                .contains("body=<" + WRITE_CANARY.length() + " chars>");
        assertThat(log)
                .as("everything else in the argv is shape and stays legible")
                .contains("subjectId=I_abc")
                .contains("addComment(input:");

        Map<String, Object> trace = onlyTraceLine(log, "add_issue_comment failed");
        assertThat(trace)
                .containsEntry("outcome", "error")
                // UNKNOWN, not CHECK_BEFORE_RETRY. ADR-0008 reclassifies the three exits
                // where a write was *abandoned* before its result could be read; this one
                // exited cleanly and non-zero with stderr nothing in GhStderr matches, and
                // that floors at UNKNOWN whether it wrote or read.
                .containsEntry("remedy", "UNKNOWN")
                .containsKey("callId");
        assertThat(argvLine(log).get("callId"))
                .as("the two lines a failed write produces are joined by the call they "
                        + "belong to -- under a pooled dispatcher they are not guaranteed "
                        + "to be adjacent")
                .isEqualTo(trace.get("callId"));
    }

    private static Map<String, Object> onlyTraceLine(Path logFile, String message)
            throws Exception {
        return onlyTraceLine(Files.readString(logFile), message);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> onlyTraceLine(String log, String message) {
        List<Map<String, Object>> found = log.lines()
                .filter(line -> !line.isBlank())
                .map(line -> (Map<String, Object>) JSON.readValue(line, Map.class))
                .filter(entry -> message.equals(entry.get("message")))
                .toList();
        assertThat(found).as("one call, one trace line").hasSize(1);
        return found.get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> argvLine(String log) {
        List<Map<String, Object>> found = log.lines()
                .filter(line -> !line.isBlank())
                .map(line -> (Map<String, Object>) JSON.readValue(line, Map.class))
                .filter(entry -> String.valueOf(entry.get("message")).startsWith("`gh "))
                .toList();
        assertThat(found).as("one failed gh call, one argv line").hasSize(1);
        return found.get(0);
    }

    private static String text(CallToolResult result) {
        return result.content().stream()
                .map(Object::toString)
                .reduce("", String::concat);
    }
}

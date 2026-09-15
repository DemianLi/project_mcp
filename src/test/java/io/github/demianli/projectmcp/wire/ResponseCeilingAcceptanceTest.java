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
 * 驗證回應超過大小上限時的行為。
 *
 * <p>超過上限時，該次呼叫以 Remedy 拒絕，Server 繼續運作。超過 heap 所能容納的量時（上限讓
 * 這種情況碰不到），不會有回應，Server 也不再運作。要測 OutOfMemoryError 只能真的觸發一次，
 * 所以第二個測試讓 Server 耗盡記憶體。見 docs/design.md#bounds。
 */
class ResponseCeilingAcceptanceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path tmp;

    /** 印出一個 issue 的 `gh`，其 body 是 {@code bodyBytes} 位元組的填充字元。 */
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
        // 9 MB 對 8 MB 上限，刻意只超過一點：若送 100 MB，即使上限失效、改由 heap 耗盡
        // 擋下，測試也會通過。
        try (McpSyncClient client = LaunchedServer.withGh(tmp, ghEmitting(9 * 1024 * 1024))) {
            CallToolResult refused = getIssue(client);

            assertThat(refused.isError()).isTrue();
            assertThat(structured(refused))
                    .as("FIX_REQUEST, not UNKNOWN: this Server invented this failure, named "
                            + "it and counted the bytes")
                    .containsEntry("remedy", "FIX_REQUEST")
                    .containsEntry("stderr", "");
            // 以關係而非字面值斷言：確切總數是 body 加上替身包裝的內容，
            // 釘住這個算式會讓測試變成在測 fixture。
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

            // 拒絕不會讓任何東西掛掉。同一連線上還能再呼叫，就是上限與崩潰的差別。
            assertThat(refused.isError()).isTrue();
            assertThat(client.listTools().tools()).hasSize(5);
        }
    }

    @Test
    void aServerThatRunsOutOfMemoryStopsBeingOneInsteadOfGoingQuiet() throws Exception {
        Path logFile = tmp.resolve("fatal.log");

        // 7 MB 低於上限，會被放行；但 32 MB 的 heap 不足以解碼、解析、映射再序列化回去。
        // fatal 分支就在這個落差裡。
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
                .as("one trace line logs the fatal event")
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

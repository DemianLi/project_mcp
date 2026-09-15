package io.github.demianli.projectmcp.wire;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 驗證每個宣告會寫入的 Tool，實際都走寫入路徑。
 *
 * <p>寫入宣告（wire 上的 readOnlyHint = false）與寫入實作（GhCli.runWrite）位在程式的
 * 不同地方。兩者各自有測試，但兩者之間的落差沒有：Tool 可能宣告 false 卻呼叫 run()，
 * 悄悄違反契約。本測試讓每個寫入 Tool 撞上寫入路徑的逾時，以抓出這種落差。見
 * docs/design.md#writes。
 *
 * <p>本測試不維護寫入 Tool 清單，而是從 Server 讀 readOnlyHint；沒有 annotations 的 Tool
 * 依規格預設視為寫入。每個寫入 Tool 要花 30 秒，因為 CHECK_BEFORE_RETRY 必須真的等到
 * GhCli 逾時才會出現，沒有更便宜的方式走到這條路徑。
 */
class WritePartitionAcceptanceTest {

    @TempDir Path tmp;

    /**
     * 比 {@code GhCli.TIMEOUT_SECONDS} 更長。
     *
     * <p>若用 {@link LaunchedServer#DEFAULT_REQUEST_TIMEOUT}，兩個時限同時到期，誰先到是
     * 競態；Client 必須等得比較久，Server 的回應才送得到。
     */
    private static final Duration OUTWAITS_THE_GH_TIMEOUT = Duration.ofSeconds(60);

    /**
     * 替身對照表：讓每個寫入 Tool 的寫入呼叫卡住，而且只卡寫入呼叫。
     *
     * <p>必須逐個 Tool 定義：替身得知道 Tool 呼叫 gh 幾次、哪一次是寫入。搭配的參數放在
     * {@link ToolCalls}，失敗契約測試也會用到；這一半只有寫入測試需要。
     *
     * <p>兩張表都不決定哪些 Tool 是寫入：那來自 wire，兩張表只說明如何驅動其中每個
     * Tool，任一表缺少某個 Tool 時測試失敗而非略過。因此新增寫入 Tool 卻沒走
     * {@code runWrite} 時，不必有人記得這個檔案，測試就會失敗。
     */
    private String standInFor(String tool, Path dir) throws IOException {
        return switch (tool) {
            case "add_issue_comment" -> {
                Path id = dir.resolve("id.json");
                Files.writeString(id,
                        Files.readString(Path.of("src/test/resources/gh/issue-node-id.json")));
                Path count = dir.resolve("count.txt");

                // 第一次呼叫是查 id，走 `run`；讓它卡住會得到 RETRY，使測試因錯誤的原因
                // 失敗。只有第二次呼叫是寫入，所以只讓第二次卡住。
                yield "n=$(cat " + count + " 2>/dev/null || echo 0)\n"
                        + "n=$((n+1)); echo $n > " + count + "\n"
                        + "if [ $n -eq 1 ]; then cat " + id + "; else sleep 35; fi";
            }
            default -> null;
        };
    }

    /**
     * Tool 是否宣告會寫入。
     *
     * <p>只要不是明確的 {@code readOnlyHint = true} 都算。規格中此欄位預設為 false，什麼都
     * 沒宣告的 Tool 等於告訴 Client 它會寫入；若這裡另作解讀，本測試要抓的錯誤就會因為
     * 「沒宣告」而漏網。
     */
    private static boolean declaresAWrite(Tool tool) {
        return tool.annotations() == null
                || !Boolean.TRUE.equals(tool.annotations().readOnlyHint());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(CallToolResult result) {
        return (Map<String, Object>) result.structuredContent();
    }

    @Test
    void everyToolThatDeclaresAWriteIsRoutedAsOne() throws Exception {
        List<String> partition;

        // 一旦被執行就失敗的替身：列出 Tools 不會呼叫 gh，若替身能成功，
        // 就會掩蓋這一步不該發出的呼叫。
        Path probe = Files.createDirectory(tmp.resolve("probe"));
        try (McpSyncClient client = LaunchedServer.withGh(probe, "exit 1")) {
            partition = client.listTools().tools().stream()
                    .filter(WritePartitionAcceptanceTest::declaresAWrite)
                    .map(Tool::name)
                    .toList();
        }

        assertThat(partition)
                .as("the partition comes off readOnlyHint, and an empty one would make "
                        + "everything below pass without asserting anything")
                .isNotEmpty();

        for (String tool : partition) {
            Path dir = Files.createDirectory(tmp.resolve(tool));
            String standIn = standInFor(tool, dir);
            Map<String, Object> arguments = ToolCalls.forTool(tool);

            assertThat(standIn)
                    .as("`%s` declares that it writes, so this test needs to know which of "
                            + "its calls to hang -- add it to standInFor", tool)
                    .isNotNull();
            assertThat(arguments)
                    .as("`%s` declares that it writes, so this test needs a call that "
                            + "reaches that write -- add it to ToolCalls", tool)
                    .isNotNull();

            try (McpSyncClient client =
                         LaunchedServer.withGh(dir, standIn, OUTWAITS_THE_GH_TIMEOUT)) {

                CallToolResult result =
                        client.callTool(new CallToolRequest(tool, arguments));

                assertThat(result.isError())
                        .as("`%s` was abandoned before its result could be read", tool)
                        .isTrue();

                // 只斷言 Remedy。訊息句子直接寫出 `list_issue_comments`，
                // 措辭不屬於契約，因此不在這裡斷言。
                assertThat(structured(result))
                        .as("`%s` declares that it writes, so an abandoned call must tell "
                                + "its caller to check whether it landed -- not to retry. "
                                + "A Tool reaching gh through `run` instead of `runWrite` "
                                + "lands on RETRY here.", tool)
                        .containsEntry("remedy", "CHECK_BEFORE_RETRY");
            }
        }
    }
}

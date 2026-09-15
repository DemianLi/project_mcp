package io.github.demianli.projectmcp.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.demianli.projectmcp.gh.FakeGh;
import io.github.demianli.projectmcp.gh.GhCli;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 驗證 {@code list_labels} 的回應形狀，並從 argv 驗證 sort 與 search 互斥。
 *
 * <p>在 GitHub 的 API 中 sort 與 search 互斥；Server 從不同時送出兩者，測試透過檢查
 * argv 驗證這一點。fixture 都是 {@code gh label list} 的真實輸出。
 */
class ListLabelsTest {

    @TempDir Path tmp;

    /** 記下參數後印出 fixture 的替身 {@code gh}。 */
    private LabelTools toolsReturning(String fixture) throws IOException {
        Path payload = tmp.resolve("payload.json");
        Files.writeString(payload, Files.readString(Path.of("src/test/resources/gh/" + fixture)));
        String script = FakeGh.writing(tmp,
                "printf '%s\\n' \"$@\" > " + tmp.resolve("argv.txt") + "\n"
                        + "cat " + payload);
        return new LabelTools(new GhCli(script, 30), new LabelMapper());
    }

    /** 替身收到的參數，每個元素一個參數。 */
    private List<String> argv() throws IOException {
        return Files.readAllLines(tmp.resolve("argv.txt"));
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().get(0)).text();
    }

    @Test
    void labelsArriveInAnEnvelopeOfTwoFieldObjects() throws Exception {
        CallToolResult result = toolsReturning("label-list.json")
                .listLabels("DemianLi", "project_mcp", 100, null);

        assertThat(result.isError()).isFalse();
        assertThat(text(result))
                .as("standard envelope with items, count, truncated")
                .startsWith("{\"items\":[")
                .contains("\"count\":19")
                .contains("\"truncated\":false");

        assertThat(text(result))
                .as("two fields: name and description")
                .startsWith("{\"items\":[{\"name\":\"accessibility\",\"description\":"
                        + "\"Barrier affecting people with disabilities\"}")
                .doesNotContain("\"color\"", "\"id\"", "\"isDefault\"",
                        "\"createdAt\"", "\"updatedAt\"", "\"url\"");
    }

    @Test
    void withoutASearchTheSortFlagsAreSentAndTheOrderIsAGuarantee() throws Exception {
        toolsReturning("label-list.json").listLabels("DemianLi", "project_mcp", null, null);

        assertThat(argv()).containsSequence("--sort", "name").containsSequence("--order", "asc");
        assertThat(argv())
                .as("nothing asks gh to search when the Client did not")
                .doesNotContain("--search");
    }

    @Test
    void aSearchDropsTheSortFlagsBecauseGhRefusesBothTogether() throws Exception {
        // gh 禁止 --sort 與 --search 並用；搜尋時 Server 省略 sort。
        toolsReturning("label-list.json").listLabels("DemianLi", "project_mcp", null, "wayfinder");

        assertThat(argv()).containsSequence("--search", "wayfinder");
        assertThat(argv()).doesNotContain("--sort", "--order");
    }

    @Test
    void aBlankSearchIsOmittedRatherThanSentAsAnEmptyValue() throws Exception {
        // 空白的搜尋字串視同沒有搜尋，保留 sort 旗標。
        toolsReturning("label-list.json").listLabels("DemianLi", "project_mcp", null, "   ");

        assertThat(argv()).doesNotContain("--search");
        assertThat(argv()).containsSequence("--sort", "name");
    }

    @Test
    void oneSpareIsAskedForSoTruncatedMeansMoreExist() throws Exception {
        // fixture 是真實的 `--limit 6` 輸出：limit 為 5 時取回六列。
        CallToolResult result = toolsReturning("label-list-with-spare.json")
                .listLabels("DemianLi", "project_mcp", 5, null);

        assertThat(argv())
                .as("gh is asked for limit + 1")
                .containsSequence("--limit", "6");
        assertThat(text(result))
                .contains("\"count\":5")
                .contains("\"truncated\":true")
                .as("the spare is dropped, not reported")
                .doesNotContain("\"good first issue\"");
    }

    @Test
    void anAbsentLimitDefaultsAndAnAbsurdOneIsClamped() throws Exception {
        toolsReturning("label-list.json").listLabels("DemianLi", "project_mcp", null, null);
        assertThat(argv()).as("default 30, plus the spare").containsSequence("--limit", "31");

        toolsReturning("label-list.json").listLabels("DemianLi", "project_mcp", 5000, null);
        assertThat(argv()).as("capped at 100, plus the spare").containsSequence("--limit", "101");

        toolsReturning("label-list.json").listLabels("DemianLi", "project_mcp", 0, null);
        assertThat(argv())
                .as("clamped up rather than passed on -- gh rejects --limit 0 outright")
                .containsSequence("--limit", "2");
    }

    @Test
    void aLabelWithoutADescriptionKeepsTheKey() throws Exception {
        // 空的 description 保留在回應中；省略會造成歧義。
        CallToolResult result = toolsReturning("label-list-empty-description.json")
                .listLabels("cli", "cli", 30, "blocked");

        assertThat(text(result)).contains("{\"name\":\"blocked\",\"description\":\"\"}");
    }

    @Test
    void aGhFailureTravelsTheOrdinaryWay() throws Exception {
        // 失敗經由標準路徑分類為 Remedy。
        var tools = new LabelTools(
                new GhCli(FakeGh.failing(tmp, "GraphQL: Could not resolve to a Repository "
                        + "with the name 'DemianLi/nope'. (repository)"), 30),
                new LabelMapper());

        CallToolResult result = tools.listLabels("DemianLi", "nope", null, null);

        assertThat(result.isError()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> structured = (Map<String, Object>) result.structuredContent();
        assertThat(structured).containsEntry("remedy", "FIX_REQUEST");
    }
}

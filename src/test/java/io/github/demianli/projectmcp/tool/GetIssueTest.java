package io.github.demianli.projectmcp.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.demianli.projectmcp.gh.FakeGh;
import io.github.demianli.projectmcp.gh.GhCli;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 驗證 {@code get_issue} 的回應形狀，包括拒絕 pull request 編號。
 *
 * <p>fixture 都是 {@code gh issue view --json} 的真實輸出。
 */
class GetIssueTest {

    @TempDir Path tmp;

    private CallToolResult get(String fixture, int number) throws IOException {
        Path payload = tmp.resolve("payload.json");
        Files.writeString(payload, Files.readString(Path.of("src/test/resources/gh/" + fixture)));
        var tools = new IssueTools(new GhCli(FakeGh.writing(tmp, "cat " + payload), 30),
                new IssueMapper());
        return tools.getIssue("DemianLi", "project_mcp", number);
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().get(0)).text();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(CallToolResult result) {
        return (Map<String, Object>) result.structuredContent();
    }

    @Test
    void aClosedIssueCarriesTwelveFieldsAndNoEnvelope() throws Exception {
        CallToolResult result = get("issue-view.json", 13);

        assertThat(result.isError()).isFalse();
        assertThat(result.structuredContent())
                .as("response is text-only, not structured")
                .isNull();

        assertThat(text(result))
                .as("a bare object, not an Envelope: a single read has no items/count/truncated")
                .startsWith("{\"number\":13,")
                .doesNotContain("\"items\"", "\"count\"", "\"truncated\"");

        assertThat(text(result))
                .contains("\"state\":\"CLOSED\"")
                .contains("\"stateReason\":\"COMPLETED\"")
                .contains("\"closedAt\":\"")
                .as("labels, assignees and author are all flattened to strings")
                .contains("\"labels\":[\"wayfinder:grilling\"]")
                .contains("\"assignees\":[\"DemianLi\"]")
                .contains("\"author\":\"DemianLi\"");

        assertThat(text(result))
                .as("body is the whole reason this Tool exists")
                .contains("\"body\":\"## Question");
    }

    @Test
    void anOpenIssueKeepsGhsOwnSpellingOfAbsence() throws Exception {
        // gh 回報 closedAt 為 null、stateReason 為 ""，兩者都原樣傳遞。
        CallToolResult result = get("issue-view-open.json", 15);

        assertThat(text(result))
                .contains("\"closedAt\":null")
                .contains("\"stateReason\":\"\"")
                .contains("\"state\":\"OPEN\"")
                .as("an unassigned issue is an empty array, not a null")
                .contains("\"assignees\":[]");
    }

    @Test
    void commentsAreNotInThePayloadAtAll() throws Exception {
        // 不只是空的：這個欄位從未被要求，所以不存在一個會被讀成「這個 issue 沒有留言」
        // 的 key。
        assertThat(text(get("issue-view.json", 13))).doesNotContain("\"comments\"");
    }

    @Test
    void aPullRequestNumberIsRejected() throws Exception {
        // fixture 是透過 `gh issue view` 看到的真實 pull request：gh 成功並回應了。沒有任何
        // 東西失敗，是本 Server 判定這個回應不可接受。
        CallToolResult result = get("issue-view-pull-request.json", 14356);

        assertThat(result.isError()).isTrue();

        Map<String, Object> structured = structured(result);
        assertThat(structured)
                .containsEntry("remedy", "FIX_REQUEST")
                .as("gh wrote nothing to stderr, because gh did not fail")
                .containsEntry("stderr", "");

        assertThat((String) structured.get("message"))
                .as("built per call: the number asked for, and the URL out of the payload")
                .startsWith("#14356 is a pull request, not an issue")
                .endsWith("https://github.com/cli/cli/pull/14356");

        assertThat(text(result))
                .as("with no stderr the text half is the sentence alone, unpadded")
                .isEqualTo((String) structured.get("message"));

        assertThat(text(result))
                .as("none of the pull request's own data leaks out with the refusal")
                .doesNotContain("\"title\"", "\"body\"");
    }

    @Test
    void aGhFailureStillTravelsTheOrdinaryWay() throws Exception {
        // 另一種來源：get_issue 原封不動沿用 GhCli 的分類，包括只有 get_issue 會遇到的情況。
        var tools = new IssueTools(
                new GhCli(FakeGh.failing(tmp, "GraphQL: Could not resolve to an issue or pull "
                        + "request with the number of 9999. (repository.issue)"), 30),
                new IssueMapper());
        CallToolResult result = tools.getIssue("DemianLi", "project_mcp", 9999);

        assertThat(result.isError()).isTrue();
        assertThat(structured(result)).containsEntry("remedy", "FIX_REQUEST");
        assertThat((String) structured(result).get("stderr")).contains("number of 9999");
    }
}

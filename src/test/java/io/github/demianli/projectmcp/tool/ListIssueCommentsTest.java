package io.github.demianli.projectmcp.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.demianli.projectmcp.gh.FakeGh;
import io.github.demianli.projectmcp.gh.GhCli;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 驗證 {@code list_issue_comments} 的回應與 argv，包括 cursor 的處理。
 *
 * <p>argv 斷言驗證成功回應中看不到的關鍵決定：以 {@code last:} 而非 {@code first:}
 * 開始、在上限時不多要一列，以及送出前先拆開 cursor。fixture 都是
 * {@code gh api graphql} 的真實輸出。
 */
class ListIssueCommentsTest {

    @TempDir Path tmp;

    private CommentTools toolsReturning(String fixture) throws IOException {
        Path payload = tmp.resolve("payload.json");
        Files.writeString(payload, Files.readString(Path.of("src/test/resources/gh/" + fixture)));
        String script = FakeGh.writing(tmp,
                "printf '%s\\n' \"$@\" > " + tmp.resolve("argv.txt") + "\n"
                        + "cat " + payload);
        return new CommentTools(new GhCli(script, 30), new CommentMapper(), new WriteLimiter());
    }

    /** 替身收到的參數，每個元素一個參數。 */
    private List<String> argv() throws IOException {
        return Files.readAllLines(tmp.resolve("argv.txt"));
    }

    /** 替身是否被執行過。 */
    private boolean ghWasCalled() {
        return Files.exists(tmp.resolve("argv.txt"));
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().get(0)).text();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(CallToolResult result) {
        return (Map<String, Object>) result.structuredContent();
    }

    @Test
    void commentsArriveInAnEnvelopeOfFiveKeysHoldingFiveFieldObjects() throws Exception {
        CallToolResult result = toolsReturning("comments-page.json")
                .listIssueComments("cli", "cli", 13840, 3, null);

        assertThat(result.isError()).isFalse();
        assertThat(text(result))
                .as("standard three envelope keys, plus totalCount and nextCursor")
                .startsWith("{\"items\":[")
                .contains("\"count\":3", "\"truncated\":true", "\"totalCount\":143")
                .contains("\"nextCursor\":\"");

        assertThat(text(result))
                .as("five fields per comment, and nothing the query did not ask for")
                .contains("\"author\":\"", "\"authorAssociation\":\"", "\"createdAt\":\"",
                        "\"body\":\"", "\"url\":\"")
                .doesNotContain("\"updatedAt\"", "\"databaseId\"", "\"isMinimized\"",
                        "\"reactionGroups\"", "\"bodyText\"", "\"viewerDidAuthor\"");
    }

    @Test
    void theWindowOpensOnTheNewestCommentsAndNoSpareIsAskedFor() throws Exception {
        toolsReturning("comments-page.json").listIssueComments("cli", "cli", 13840, 30, null);

        assertThat(argv())
                .as("uses last:, not first: — opens at newest end")
                .contains("last=30")
                .doesNotContain("first=30", "first=31");
        assertThat(argv())
                .as("no spare row: at the cap the spare would be the 101 that hard-errors")
                .doesNotContain("last=31");
        assertThat(argv())
                .as("nothing asks to continue when the Client did not")
                .noneMatch(a -> a.startsWith("before="));
    }

    @Test
    void anAbsentLimitDefaultsAndAnAbsurdOneIsClampedWithoutASpare() throws Exception {
        toolsReturning("comments-page.json").listIssueComments("cli", "cli", 13840, null, null);
        assertThat(argv()).as("default 30, and 30 is what gh is asked for").contains("last=30");

        toolsReturning("comments-page.json").listIssueComments("cli", "cli", 13840, 5000, null);
        assertThat(argv())
                .as("capped at 100 -- and 101 would be EXCESSIVE_PAGINATION, not one more row")
                .contains("last=100");

        toolsReturning("comments-page.json").listIssueComments("cli", "cli", 13840, 0, null);
        assertThat(argv()).as("clamped up, as everywhere else").contains("last=1");
    }

    @Test
    void aTerminalPageReportsNoCursorEvenThoughGitHubStillSendsOne() throws Exception {
        // 必須檢查 hasPreviousPage，而不是有沒有 cursor。在最後一頁，GitHub 仍可能送來指向
        // 最後一則留言的 startCursor，但其實沒有下一頁。
        CallToolResult result = toolsReturning("comments-last-page.json")
                .listIssueComments("cli", "cli", 14361, 30, null);

        assertThat(text(result))
                .contains("\"count\":1", "\"truncated\":false", "\"totalCount\":1")
                .contains("\"nextCursor\":null");
    }

    @Test
    void anIssueWithNoCommentsIsAnEmptyEnvelopeAndNotAFailure() throws Exception {
        CallToolResult result = toolsReturning("comments-empty.json")
                .listIssueComments("DemianLi", "project_mcp", 19, 30, null);

        assertThat(result.isError()).isFalse();
        assertThat(text(result)).isEqualTo(
                "{\"items\":[],\"count\":0,\"truncated\":false,\"totalCount\":0,"
                        + "\"nextCursor\":null}");
    }

    @Test
    void theCursorHandedBackIsUnwrappedToGitHubsBeforeItIsSent() throws Exception {
        // 完整往返：從真實回應讀出 cursor、原樣交回，確認送上 argv 的是 GitHub 自己的
        // cursor，而不是本 Server 的包裝。
        String issued = cursorFrom(toolsReturning("comments-page.json")
                .listIssueComments("cli", "cli", 13840, 3, null));

        toolsReturning("comments-page.json").listIssueComments("cli", "cli", 13840, 3, issued);

        String startCursor = startCursorIn("comments-page.json");
        assertThat(argv())
                .as("GitHub's cursor, verbatim, and the wrapper nowhere near it")
                .contains("before=" + startCursor)
                .doesNotContain("before=" + issued);
        assertThat(argv())
                .as("-f, not -F: a cursor is a string whatever it looks like")
                .containsSequence("-f", "before=" + startCursor);
    }

    @Test
    void aCursorFromAnotherIssueIsRefusedBeforeGhIsCalled() throws Exception {
        // 在呼叫 GitHub 前拒絕其他 issue 的 cursor，避免悄悄出錯。
        CommentTools tools = toolsReturning("comments-page.json");
        String elsewhere = Cursors.wrap(new IssueRef("cli", "cli", 13840),
                startCursorIn("comments-page.json"));

        CallToolResult result = tools.listIssueComments("cli", "cli", 14361, 30, elsewhere);

        assertThat(result.isError()).isTrue();
        assertThat(structured(result)).containsEntry("remedy", "FIX_REQUEST");
        assertThat(text(result))
                .as("it names both issues, so the Client can see which one it meant")
                .contains("cli/cli#13840", "cli/cli#14361");
        assertThat(ghWasCalled())
                .as("refused above gh, so a wrong cursor costs nothing")
                .isFalse();
    }

    @Test
    void aCursorFromAnotherRepositoryIsRefusedToo() throws Exception {
        // 驗證 cursor 所屬的 repository，避免不同 repository 的資料悄悄混在一起。
        CallToolResult result = toolsReturning("comments-page.json").listIssueComments(
                "ollama", "ollama", 5000,
                30, Cursors.wrap(new IssueRef("cli", "cli", 13840),
                        startCursorIn("comments-page.json")));

        assertThat(structured(result)).containsEntry("remedy", "FIX_REQUEST");
        assertThat(text(result))
                .as("refused for the reason it looks like -- an unreadable cursor is "
                        + "FIX_REQUEST too, and names neither issue")
                .contains("cli/cli#13840", "ollama/ollama#5000");
        assertThat(ghWasCalled()).isFalse();
    }

    @Test
    void aCursorStaysUsableWhenTheRepositoryIsSpelledInAnotherCase() throws Exception {
        // GitHub 解析 owner 與 repository 名稱時不分大小寫，cursor 的比對也一樣。
        String issued = Cursors.wrap(new IssueRef("cli", "cli", 13840),
                startCursorIn("comments-page.json"));

        CallToolResult result = toolsReturning("comments-page.json")
                .listIssueComments("CLI", "cli", 13840, 3, issued);

        assertThat(result.isError())
                .as("the cursor names the issue this call asks about, spelled differently")
                .isFalse();
        assertThat(argv())
                .as("and it was unwrapped, not merely tolerated")
                .contains("before=" + startCursorIn("comments-page.json"));
    }

    @Test
    void aCursorThatIsNotBase64AndOneWithNoIssueInItAreBothFixRequest() throws Exception {
        CallToolResult garbage = toolsReturning("comments-page.json")
                .listIssueComments("cli", "cli", 13840, 30, "!!! not base64 !!!");
        assertThat(structured(garbage)).containsEntry("remedy", "FIX_REQUEST");

        // 能正常解碼但沒有分隔符：與上一個是不同分支，Remedy 相同，因為 Client 對兩者的
        // 處理方式相同。
        String noSeparator = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("Y3Vyc29yOnYyOpE=".getBytes(StandardCharsets.UTF_8));
        CallToolResult bare = toolsReturning("comments-page.json")
                .listIssueComments("cli", "cli", 13840, 30, noSeparator);
        assertThat(structured(bare)).containsEntry("remedy", "FIX_REQUEST");

        assertThat(ghWasCalled()).isFalse();
    }

    @Test
    void aBlankCursorMeansTheFirstPageRatherThanAnError() throws Exception {
        // Spring AI 原樣轉交 Client 送來的值；把「沒有 cursor」存成空字串的 Client 並沒有犯
        // 值得判為失敗的錯。
        CallToolResult result = toolsReturning("comments-page.json")
                .listIssueComments("cli", "cli", 13840, 3, "   ");

        assertThat(result.isError()).isFalse();
        assertThat(argv()).noneMatch(a -> a.startsWith("before="));
    }

    @Test
    void aDeletedAccountLeavesTheAuthorEmptyRatherThanBreakingTheRow() throws Exception {
        // author 為 null（帳號已刪除）時，mapper 回傳空字串，讓該列仍然有效。
        CallToolResult result = toolsReturning("comments-ghost-author.json")
                .listIssueComments("cli", "cli", 13840, 3, null);

        assertThat(result.isError()).isFalse();
        assertThat(text(result))
                .as("empty, not absent -- a login is never the empty string, so it is "
                        + "unambiguous, and the row keeps its five keys")
                .contains("{\"author\":\"\",\"authorAssociation\":");
    }

    @Test
    void aGhFailureTravelsTheOrdinaryWay() throws Exception {
        // GraphQL 的錯誤與 porcelain 指令的錯誤一樣，會被分類並對應到 Remedy。
        var tools = new CommentTools(
                new GhCli(FakeGh.failing(tmp,
                        "gh: Could not resolve to an Issue with the number of 14362."), 30),
                new CommentMapper(), new WriteLimiter());

        CallToolResult result = tools.listIssueComments("cli", "cli", 14362, 30, null);

        assertThat(result.isError()).isTrue();
        assertThat(structured(result)).containsEntry("remedy", "FIX_REQUEST");
        assertThat(text(result)).contains("may be a pull request");
    }

    /** GitHub 自己的 {@code startCursor}，從 fixture 讀出而不是手抄。 */
    private static String startCursorIn(String fixture) throws IOException {
        String json = Files.readString(Path.of("src/test/resources/gh/" + fixture));
        int at = json.indexOf("\"startCursor\":\"") + "\"startCursor\":\"".length();
        return json.substring(at, json.indexOf('"', at));
    }

    /** 像 Client 一樣，從回應讀回 {@code nextCursor}。 */
    private static String cursorFrom(CallToolResult result) {
        String json = text(result);
        int at = json.indexOf("\"nextCursor\":\"") + "\"nextCursor\":\"".length();
        return json.substring(at, json.indexOf('"', at));
    }
}

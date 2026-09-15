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
 * 驗證 {@code add_issue_comment} 的回應形狀，以及它送往 GitHub 的 argv。
 *
 * <p>argv 上的決定在成功結果中看不到，所以由這一層斷言：查詢使用
 * {@code repository.issue}（擋下 pull request）、所有值都以 {@code -f} 送出（保留像
 * 數字的 body），而且不送 {@code clientMutationId}。
 */
class AddIssueCommentTest {

    @TempDir Path tmp;

    /** 先回應查詢、再回應 mutation 的替身，兩次呼叫都會記錄。 */
    private CommentTools tools() throws IOException {
        return tools(new WriteLimiter());
    }

    private CommentTools tools(WriteLimiter writes) throws IOException {
        Path id = tmp.resolve("id.json");
        Path added = tmp.resolve("added.json");
        Files.writeString(id, Files.readString(Path.of("src/test/resources/gh/issue-node-id.json")));
        Files.writeString(added, Files.readString(Path.of("src/test/resources/gh/add-comment.json")));

        String script = "n=$(cat " + tmp.resolve("count.txt") + " 2>/dev/null || echo 0)\n"
                + "n=$((n+1)); echo $n > " + tmp.resolve("count.txt") + "\n"
                + "printf '%s\\n' \"$@\" > " + tmp.resolve("argv") + "$n.txt\n"
                + "if [ $n -eq 1 ]; then cat " + id + "; else cat " + added + "; fi";
        return new CommentTools(new GhCli(FakeGh.writing(tmp, script), 30), new CommentMapper(),
                writes);
    }

    /** 替身第 n 次被呼叫時收到的參數，每個元素一個參數。 */
    private List<String> argv(int call) throws IOException {
        return Files.readAllLines(tmp.resolve("argv" + call + ".txt"));
    }

    private boolean ghWasCalled() {
        return Files.exists(tmp.resolve("argv1.txt"));
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().get(0)).text();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(CallToolResult result) {
        return (Map<String, Object>) result.structuredContent();
    }

    @Test
    void thePayloadIsTheUrlAndNothingElse() throws Exception {
        CallToolResult result = tools()
                .addIssueComment("DemianLi", "project-mcp-sandbox", 1, "hello");

        assertThat(result.isError()).isFalse();
        assertThat(result.structuredContent())
                .as("response is text-only, not structured")
                .isNull();
        assertThat(text(result))
                .isEqualTo("{\"url\":\"https://github.com/DemianLi/project-mcp-sandbox/"
                        + "issues/1#issuecomment-5553376090\"}");
        assertThat(text(result))
                .as("the four fields the read keeps and this one does not")
                .doesNotContain("\"body\"", "\"author\"", "\"createdAt\"",
                        "\"authorAssociation\"")
                .as("one write is not a list, so there is no Envelope around it")
                .doesNotContain("\"items\"", "\"count\"", "\"truncated\"");
    }

    @Test
    void theLookupIsWhatMakesAPullRequestUnwritable() throws Exception {
        tools().addIssueComment("DemianLi", "project-mcp-sandbox", 1, "hello");

        assertThat(String.join(" ", argv(1)))
                .as("query uses repository.issue(number:) to reject pull requests")
                .contains("repository(owner:$owner, name:$name)")
                .contains("issue(number:$number) { id }");
        assertThat(String.join(" ", argv(1)))
                .as("no mutation on the first call: it is a read, and it reads only an id")
                .doesNotContain("mutation", "addComment", "body");
    }

    @Test
    void everyStringGoesOutUnderMinusFSoANumericBodyStaysAString() throws Exception {
        // 用 -f 讓 "123" 維持字串；-F 會把它轉成 JSON 數字。
        tools().addIssueComment("DemianLi", "project-mcp-sandbox", 1, "123");

        assertThat(argv(2)).containsSequence("-f", "body=123");
        assertThat(argv(2)).containsSequence("-f", "subjectId=I_kwDOUPQgXc8AAAABP10LbA");
        assertThat(argv(1)).containsSequence("-f", "owner=DemianLi");
        assertThat(argv(1)).containsSequence("-f", "name=project-mcp-sandbox");
        assertThat(argv(1))
                .as("the one value that is genuinely an Int! -- and the only -F here")
                .containsSequence("-F", "number=1");
        assertThat(argv(1))
                .as("-F appears exactly once, and not in front of a string")
                .doesNotContainSequence("-F", "owner=DemianLi")
                .doesNotContainSequence("-F", "name=project-mcp-sandbox");
        assertThat(argv(2)).doesNotContain("-F");
    }

    @Test
    void theMutationCarriesNoClientMutationId() throws Exception {
        // clientMutationId 不提供冪等性：相同的 key 與 body 仍會產生兩則留言。
        tools().addIssueComment("DemianLi", "project-mcp-sandbox", 1, "hello");

        assertThat(String.join(" ", argv(2)))
                .contains("addComment(input:{subjectId:$subjectId, body:$body})")
                .doesNotContain("clientMutationId");
    }

    @Test
    void theIdFromCallOneIsWhatCallTwoWritesTo() throws Exception {
        // 兩次呼叫由第一次回應中解析出的值串接。從回傳的 url 看不出 Tool 是否送錯
        // subjectId，因為 url 無論如何都來自替身。
        tools().addIssueComment("DemianLi", "project-mcp-sandbox", 1, "hello");

        String idFromFixture = "I_kwDOUPQgXc8AAAABP10LbA";
        assertThat(Files.readString(Path.of("src/test/resources/gh/issue-node-id.json")))
                .contains(idFromFixture);
        assertThat(argv(2)).contains("subjectId=" + idFromFixture);
    }

    @Test
    void aBlankBodyIsRefusedWithoutCallingGhAtAll() throws Exception {
        // 拒絕空白（而不只是空字串）的 body，避免只有空白字元的留言。
        for (String blank : List.of("", " ", "  ", "\n", "\t\n ")) {
            CommentTools tools = tools();
            CallToolResult result =
                    tools.addIssueComment("DemianLi", "project-mcp-sandbox", 1, blank);

            assertThat(result.isError()).as("body %s", blank.replace("\n", "\\n")).isTrue();
            assertThat(structured(result))
                    .containsEntry("remedy", "FIX_REQUEST")
                    .as("no gh ran, so there is no stderr to report")
                    .containsEntry("stderr", "");
            assertThat(text(result)).contains("blank");
            assertThat(ghWasCalled())
                    .as("refused before the call, not after it -- a blank body has no "
                            + "possible success, and the wasted round trip is held open by "
                            + "a 30-second timeout")
                    .isFalse();
        }
    }

    @Test
    void aWriteOverTheLimitIsRefusedWithoutCallingGhAtAll() throws Exception {
        WriteLimiter full = new WriteLimiter(() -> 0L);
        for (int i = 0; i < WriteLimiter.PER_MINUTE; i++) {
            full.acquire();
        }

        CallToolResult result = tools(full)
                .addIssueComment("DemianLi", "project-mcp-sandbox", 1, "hello");

        assertThat(result.isError()).isTrue();
        assertThat(structured(result))
                .containsEntry("remedy", "RETRY")
                .containsEntry("retryAfterSeconds", 60)
                .containsEntry("stderr", "");
        assertThat(ghWasCalled()).isFalse();
    }

    @Test
    void anInvalidRequestDoesNotUseASlot() throws Exception {
        WriteLimiter writes = new WriteLimiter(() -> 0L);
        CommentTools tools = tools(writes);
        for (int i = 0; i < WriteLimiter.PER_MINUTE; i++) {
            tools.addIssueComment("DemianLi", "project-mcp-sandbox", 1, " ");
        }

        assertThat(tools.addIssueComment("DemianLi", "project-mcp-sandbox", 1, "hello").isError())
                .isFalse();
    }

    @Test
    void aFailureOnTheLookupNeverReachesTheMutation() throws Exception {
        // 查詢失敗時，mutation 永遠不會被呼叫。
        String script = "printf '%s\\n' \"$@\" >> " + tmp.resolve("argv1.txt") + "\n"
                + "echo 'gh: Could not resolve to an Issue with the number of 3.' >&2\n"
                + "exit 1";
        CommentTools tools =
                new CommentTools(new GhCli(FakeGh.writing(tmp, script), 30), new CommentMapper(),
                        new WriteLimiter());

        CallToolResult result =
                tools.addIssueComment("DemianLi", "project-mcp-sandbox", 3, "must not appear");

        assertThat(result.isError()).isTrue();
        assertThat(structured(result)).containsEntry("remedy", "FIX_REQUEST");
        assertThat(text(result)).contains("pull request");
        assertThat(String.join(" ", Files.readAllLines(tmp.resolve("argv1.txt"))))
                .as("the guard is that the write never happens, not that it is undone")
                .doesNotContain("addComment");
    }
}

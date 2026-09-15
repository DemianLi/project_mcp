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
 * Verifies {@code add_issue_comment}'s response shape and the argv it builds to GitHub.
 *
 * <p>The argv decisions are not visible in the success result, so this layer asserts them:
 * that the lookup uses {@code repository.issue} (pull-request guard), that all values go
 * under {@code -f} (preserves numeric bodies), and that no {@code clientMutationId} is sent.
 */
class AddIssueCommentTest {

    @TempDir Path tmp;

    /** Stand-in that answers lookup then mutation, recording both invocations. */
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

    /** What the stand-in was called with on its nth invocation, one argument per element. */
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
        // Using -f preserves "123" as a string; -F would coerce it to a JSON number.
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
        // clientMutationId does not provide idempotency; same key/body produces two comments.
        tools().addIssueComment("DemianLi", "project-mcp-sandbox", 1, "hello");

        assertThat(String.join(" ", argv(2)))
                .contains("addComment(input:{subjectId:$subjectId, body:$body})")
                .doesNotContain("clientMutationId");
    }

    @Test
    void theIdFromCallOneIsWhatCallTwoWritesTo() throws Exception {
        // The two calls are joined by a value parsed out of the first response. Nothing in
        // the returned url would reveal a Tool that sent the wrong subjectId, because the
        // url comes from the stand-in either way.
        tools().addIssueComment("DemianLi", "project-mcp-sandbox", 1, "hello");

        String idFromFixture = "I_kwDOUPQgXc8AAAABP10LbA";
        assertThat(Files.readString(Path.of("src/test/resources/gh/issue-node-id.json")))
                .contains(idFromFixture);
        assertThat(argv(2)).contains("subjectId=" + idFromFixture);
    }

    @Test
    void aBlankBodyIsRefusedWithoutCallingGhAtAll() throws Exception {
        // Blank (not empty) bodies are rejected to prevent whitespace-only comments.
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
        // When lookup fails, the mutation is never invoked.
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

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
 * Coverage layer: {@code add_issue_comment}'s payload, and the two argv it builds.
 *
 * <p>The argv carries more of this Tool's decisions than of any read's, because almost
 * nothing here is visible in the result. That the lookup is {@code repository.issue} and not
 * a bare number is the entire pull-request guard; that every string goes out under
 * {@code -f} and never {@code -F} is what stops a body of {@code "123"} arriving as a JSON
 * number; and that {@code clientMutationId} is absent is a measured decision rather than an
 * omission. A wrong version of any of the three returns a perfectly good {@code url}.
 *
 * <p>What this layer cannot see is which of {@code run} and {@code runWrite} carried each
 * call — they build identical argv, and the difference appears only in a failure that this
 * Tool's own tests do not provoke. {@code GhCliFailureTest} owns that half.
 *
 * <p>Both fixtures are verbatim captures from the real endpoint, taken while resolving #30
 * against the sandbox repository.
 */
class AddIssueCommentTest {

    @TempDir Path tmp;

    /**
     * A stand-in that answers the lookup and then the mutation, recording both argv.
     *
     * <p>Two calls is what makes this different from every other stand-in in the suite: the
     * script counts its own invocations, so the second answer is the mutation's rather than
     * the lookup's repeated.
     */
    private CommentTools tools() throws IOException {
        Path id = tmp.resolve("id.json");
        Path added = tmp.resolve("added.json");
        Files.writeString(id, Files.readString(Path.of("src/test/resources/gh/issue-node-id.json")));
        Files.writeString(added, Files.readString(Path.of("src/test/resources/gh/add-comment.json")));

        String script = "n=$(cat " + tmp.resolve("count.txt") + " 2>/dev/null || echo 0)\n"
                + "n=$((n+1)); echo $n > " + tmp.resolve("count.txt") + "\n"
                + "printf '%s\\n' \"$@\" > " + tmp.resolve("argv") + "$n.txt\n"
                + "if [ $n -eq 1 ]; then cat " + id + "; else cat " + added + "; fi";
        return new CommentTools(new GhCli(FakeGh.writing(tmp, script), 30), new CommentMapper());
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
                .as("ADR-0007 keeps ADR-0001's TEXT-only line rather than amending it")
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
                .as("repository.issue(number:) cannot resolve a pull request's id, and that "
                        + "-- not a check anywhere -- is the guard (ADR-0007)")
                .contains("repository(owner:$owner, name:$name)")
                .contains("issue(number:$number) { id }");
        assertThat(String.join(" ", argv(1)))
                .as("no mutation on the first call: it is a read, and it reads only an id")
                .doesNotContain("mutation", "addComment", "body");
    }

    @Test
    void everyStringGoesOutUnderMinusFSoANumericBodyStaysAString() throws Exception {
        // -F coerces anything that looks numeric. A comment whose whole text is "123" would
        // then reach `body:String!` as a JSON number and fail for no reason a caller could
        // see. Measured against the real endpoint: "123" posts fine under -f.
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
        // It is the obvious idempotency key and is not one: GitHub's schema calls it an
        // identifier for the client performing the mutation, and the same key with the same
        // body twice was measured producing two comments. Sending it would advertise a
        // guarantee that does not exist. See ADR-0008.
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
        // GitHub's predicate is blankness, not emptiness: `--body " "` fails exactly as
        // `--body ""` does. Validating emptiness alone would let whitespace through to the
        // failure this check exists to remove. See ADR-0007.
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
    void aFailureOnTheLookupNeverReachesTheMutation() throws Exception {
        // The pull-request case as a Client meets it: `gh` exits 1 on call one, so call two
        // does not happen. Captured verbatim from the sandbox's pull request #3.
        String script = "printf '%s\\n' \"$@\" >> " + tmp.resolve("argv1.txt") + "\n"
                + "echo 'gh: Could not resolve to an Issue with the number of 3.' >&2\n"
                + "exit 1";
        CommentTools tools =
                new CommentTools(new GhCli(FakeGh.writing(tmp, script), 30), new CommentMapper());

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

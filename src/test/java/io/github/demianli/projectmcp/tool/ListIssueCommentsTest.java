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
 * Coverage layer: {@code list_issue_comments}' payload, the argv it builds, and what it does
 * with a cursor.
 *
 * <p>The argv assertions carry more weight here than in {@link ListLabelsTest}. Three of
 * ADR-0006's decisions are invisible above the argv, and each has a plausible wrong version
 * that returns a perfectly well-formed Envelope: the window opens with {@code last:} and not
 * {@code first:}; no spare row is asked for, because at the cap the spare is the 101 that
 * hard-errors; and a cursor is unwrapped back to GitHub's before being sent. Only the
 * stand-in's record of what it was called with can tell those apart.
 *
 * <p>Every fixture is a verbatim capture of what the real {@code gh api graphql} printed,
 * with one exception noted at its test.
 */
class ListIssueCommentsTest {

    @TempDir Path tmp;

    private CommentTools toolsReturning(String fixture) throws IOException {
        Path payload = tmp.resolve("payload.json");
        Files.writeString(payload, Files.readString(Path.of("src/test/resources/gh/" + fixture)));
        String script = FakeGh.writing(tmp,
                "printf '%s\\n' \"$@\" > " + tmp.resolve("argv.txt") + "\n"
                        + "cat " + payload);
        return new CommentTools(new GhCli(script, 30), new CommentMapper());
    }

    /** What the stand-in was called with, one argument per element. */
    private List<String> argv() throws IOException {
        return Files.readAllLines(tmp.resolve("argv.txt"));
    }

    /** Whether the stand-in ran at all. */
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
                .as("ADR-0001's three keys, in their order, then ADR-0006's two")
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
                .as("last:, not first: -- the window opens at the newest end (ADR-0006)")
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
        // The discriminator, and the one an implementation is most likely to get wrong.
        // cli/cli#14361 has one comment: hasPreviousPage is false, and startCursor is
        // *not* null -- it points at that comment. Reading the cursor's presence instead
        // of the flag would hand a Client a marker whose next response is empty.
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
        // The round trip, end to end: read the cursor out of a real response, hand it
        // straight back, and watch GitHub's own cursor -- not this Server's wrapper --
        // arrive on the argv.
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
        // The measurement this wrapper exists for. Handed straight to GitHub, this cursor
        // returns totalCount 1 with an empty nodes and exits zero -- a Client reads that as
        // "one comment exists and I cannot see it". Rejecting it costs no call at all.
        CommentTools tools = toolsReturning("comments-page.json");
        String elsewhere = Cursors.wrap("cli", "cli", 13840, startCursorIn("comments-page.json"));

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
        // Measured as the more dangerous of the two: the same cursor against
        // ollama/ollama#5000 returned all eight of its comments looking entirely normal,
        // because the id happened to sort after all of them.
        //
        // The fixture named here is never read: the cursor is refused above gh, so the
        // stand-in does not run. It is passed only because toolsReturning builds the Tool.
        CallToolResult result = toolsReturning("comments-page.json").listIssueComments(
                "ollama", "ollama", 5000,
                30, Cursors.wrap("cli", "cli", 13840, startCursorIn("comments-page.json")));

        assertThat(structured(result)).containsEntry("remedy", "FIX_REQUEST");
        assertThat(ghWasCalled()).isFalse();
    }

    @Test
    void aCursorThatIsNotBase64AndOneWithNoIssueInItAreBothFixRequest() throws Exception {
        CallToolResult garbage = toolsReturning("comments-page.json")
                .listIssueComments("cli", "cli", 13840, 30, "!!! not base64 !!!");
        assertThat(structured(garbage)).containsEntry("remedy", "FIX_REQUEST");

        // Decodes cleanly and carries no separator -- a different branch from the above,
        // and the same Remedy, because the Client does the same thing about either.
        String noSeparator = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("Y3Vyc29yOnYyOpE=".getBytes(StandardCharsets.UTF_8));
        CallToolResult bare = toolsReturning("comments-page.json")
                .listIssueComments("cli", "cli", 13840, 30, noSeparator);
        assertThat(structured(bare)).containsEntry("remedy", "FIX_REQUEST");

        assertThat(ghWasCalled()).isFalse();
    }

    @Test
    void aBlankCursorMeansTheFirstPageRatherThanAnError() throws Exception {
        // Spring AI hands through what the Client sent, and a Client that stores "no
        // cursor" as an empty string is not making a mistake worth a failure.
        CallToolResult result = toolsReturning("comments-page.json")
                .listIssueComments("cli", "cli", 13840, 3, "   ");

        assertThat(result.isError()).isFalse();
        assertThat(argv()).noneMatch(a -> a.startsWith("before="));
    }

    @Test
    void aDeletedAccountLeavesTheAuthorEmptyRatherThanBreakingTheRow() throws Exception {
        // The one fixture that is not a verbatim capture: GitHub types `author` as `Actor`
        // and not `Actor!`, so a deleted account reports null, but no such comment turned
        // up in roughly 350 sampled across eleven repositories. This is comments-page.json
        // with the first author nulled, which is exactly what the schema permits.
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
        // Inherited by calling GhCli.run, with no per-Tool code -- but worth proving here
        // because this is the first Tool whose failures come from `gh api` rather than
        // porcelain, and ADR-0005 added a branch to classify() for exactly that.
        var tools = new CommentTools(
                new GhCli(FakeGh.failing(tmp,
                        "gh: Could not resolve to an Issue with the number of 14362."), 30),
                new CommentMapper());

        CallToolResult result = tools.listIssueComments("cli", "cli", 14362, 30, null);

        assertThat(result.isError()).isTrue();
        assertThat(structured(result)).containsEntry("remedy", "FIX_REQUEST");
        assertThat(text(result)).contains("may be a pull request");
    }

    /** GitHub's own {@code startCursor}, read out of the fixture rather than transcribed. */
    private static String startCursorIn(String fixture) throws IOException {
        String json = Files.readString(Path.of("src/test/resources/gh/" + fixture));
        int at = json.indexOf("\"startCursor\":\"") + "\"startCursor\":\"".length();
        return json.substring(at, json.indexOf('"', at));
    }

    /** Reads the {@code nextCursor} back out of a response, the way a Client would. */
    private static String cursorFrom(CallToolResult result) {
        String json = text(result);
        int at = json.indexOf("\"nextCursor\":\"") + "\"nextCursor\":\"".length();
        return json.substring(at, json.indexOf('"', at));
    }
}

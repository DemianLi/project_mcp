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
 * Acceptance layer: every Tool that declares it writes actually takes the write route.
 *
 * <p><strong>The gap this closes.</strong> A Tool being a write is stated in two places that
 * never meet. It is declared to a Client as {@code readOnlyHint = false}, and it is acted on
 * by the Tool choosing {@code GhCli.runWrite} over {@code GhCli.run}. Both halves were
 * already tested and the line between them was not: {@link WireAcceptanceTest} reads
 * {@code add_issue_comment}'s four hints off the wire, and {@code GhCliFailureTest} drives
 * {@code runWrite} directly with an argv of {@code query=x} that belongs to no Tool at all.
 * {@code AddIssueCommentTest} says outright that it cannot see which of the two carried each
 * call. So a write Tool declaring {@code readOnlyHint = false} and calling {@code run} passed
 * the whole suite, and told a Client to retry at the one moment a comment may already exist
 * — ADR-0008's contract gone, silently.
 *
 * <p><strong>Why here.</strong> Annotations are like {@code isError}: they have no existence
 * below the wire, so nothing under it can read the partition. This layer is thin on purpose
 * and this test does not change that — it proves a shape, the shape of a write Tool's
 * failure, which is the thing this layer is for. Coverage of each Remedy still lives next
 * door.
 *
 * <p><strong>The partition is read, not listed.</strong> `CONTEXT.md` says which Tools count
 * as writes is the {@code readOnlyHint} field, "not a second list kept alongside", so this
 * test keeps no list of write Tools. It asks the Server, and treats anything that does not
 * explicitly declare {@code readOnlyHint = true} as a write — the spec's own default, and
 * the direction that fails safe: a Tool added with no annotations at all lands in the
 * partition and demands an entry in the tables below rather than slipping past.
 *
 * <p>What the reading cannot catch on its own is the partition being silently emptied —
 * {@code add_issue_comment} mislabelled {@code readOnlyHint = true} would simply be skipped.
 * Two things already standing stop that: {@link WireAcceptanceTest} asserts that Tool's hint
 * by hand, and the emptiness assertion below refuses a vacuous pass. Neither is a list of
 * which Tools write.
 *
 * <p><strong>The 30 seconds.</strong> Driving a Tool into {@code CHECK_BEFORE_RETRY} costs a
 * real {@code GhCli} timeout, and there is no cheaper way in: of the three exits that carry
 * that Remedy, the other two are thread states inside the Server process that nothing out
 * here can reach, and the budget is 30 seconds fixed — Spring builds {@code GhCli} through
 * its no-argument constructor and no property exists to shorten it. Making one would mean
 * designing this Server's first operator-facing configuration key, which ADR-0009 put out of
 * scope. So the wall clock is the price of this test, once per write Tool. It is worth it at
 * one write Tool. If the day comes that it is not, the thing to revisit is the fixed budget,
 * not this assertion.
 */
class WritePartitionAcceptanceTest {

    @TempDir Path tmp;

    /**
     * Long enough to outlast {@code GhCli.TIMEOUT_SECONDS}.
     *
     * <p>At {@link LaunchedServer#DEFAULT_REQUEST_TIMEOUT} the two budgets expire together
     * and which lands first is a race — the Client has to be the patient one for the
     * Server's answer to arrive at all.
     */
    private static final Duration OUTWAITS_THE_GH_TIMEOUT = Duration.ofSeconds(60);

    /**
     * The stand-in table: how to make each write Tool's write, and only its write, hang.
     *
     * <p>Per-Tool by necessity, not by preference — a stand-in has to know how many calls the
     * Tool makes and which of them is the write. The arguments that go with it live in
     * {@link ToolCalls}, which the failure-contract tests need too; this half is the one only
     * a write test wants.
     *
     * <p>Neither table is the partition. The partition comes off the wire; these say how to
     * drive each of its members, and a member missing from either fails the test rather than
     * being skipped. That is the whole mechanism: a write Tool added without
     * {@code runWrite} goes red without anyone having to remember this file exists.
     */
    private String standInFor(String tool, Path dir) throws IOException {
        return switch (tool) {
            case "add_issue_comment" -> {
                Path id = dir.resolve("id.json");
                Files.writeString(id,
                        Files.readString(Path.of("src/test/resources/gh/issue-node-id.json")));
                Path count = dir.resolve("count.txt");

                // Call one is the id lookup and goes through `run`; hanging it would produce
                // RETRY and fail this test for entirely the wrong reason. Only call two is
                // the write, so only call two hangs.
                yield "n=$(cat " + count + " 2>/dev/null || echo 0)\n"
                        + "n=$((n+1)); echo $n > " + count + "\n"
                        + "if [ $n -eq 1 ]; then cat " + id + "; else sleep 35; fi";
            }
            default -> null;
        };
    }

    /**
     * Whether a Tool declares that it writes.
     *
     * <p>Anything short of an explicit {@code readOnlyHint = true} counts. The spec's default
     * for the field is false, and a Tool that declares nothing is therefore telling a Client
     * it writes — reading it any other way here would let the one mistake this test exists
     * for slip through as an omission.
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

        // A stand-in that would fail if it ran. Listing Tools never reaches gh, and one that
        // could succeed would hide a call this step is not supposed to be making.
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

                // The Remedy only. The sentence beside it names `list_issue_comments` by
                // hand today, and GhCli's javadoc keeps that until a second write Tool
                // forces it to be parameterised -- asserting the wording here would make
                // that day cost this test too.
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

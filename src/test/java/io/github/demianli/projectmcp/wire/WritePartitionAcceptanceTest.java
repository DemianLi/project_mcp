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
 * Tests that every Tool declaring writes actually takes the write route.
 *
 * <p>Write declaration (readOnlyHint = false on the wire) and write implementation
 * (GhCli.runWrite) are in different parts of the code. Both are tested separately, but the
 * gap between them is not: a Tool could declare false and call run(), causing silent contract
 * violations. This test drives each write Tool through the write route's timeout to detect
 * that gap. See docs/design.md#writes.
 *
 * <p>The test keeps no list of write Tools; it reads readOnlyHint from the Server. A Tool
 * with no annotations defaults to write (the spec default). The test costs 30 seconds per
 * write Tool because CHECK_BEFORE_RETRY requires reaching GhCli's real timeout — there is
 * no cheaper way to exercise that code path. See docs/design.md#writes.
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

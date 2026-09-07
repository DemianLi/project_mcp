package io.github.demianli.projectmcp.wire;

import java.util.Map;

/**
 * One call per Tool that gets as far as {@code gh}.
 *
 * <p>Shared by the Acceptance layer tests that drive every Tool rather than a chosen one.
 * Each entry has to survive whatever its Tool refuses before {@code gh} is started — an
 * {@code add_issue_comment} whose {@code body} were blank would be refused with
 * {@code FIX_REQUEST} having never reached the subprocess, and a test meaning to observe a
 * {@code gh} failure would observe nothing of the kind.
 *
 * <p>The requirement is stronger for a write Tool, and both callers depend on it: an entry
 * must reach {@code gh}, and for a Tool that writes it must reach the <em>write</em>. An entry
 * that stopped at a check in between would make {@code WritePartitionAcceptanceTest} fail
 * looking like a routing fault — {@code FIX_REQUEST} where it wanted
 * {@code CHECK_BEFORE_RETRY} — when the fault was here.
 *
 * <p>Not derivable from a Tool's {@code inputSchema}: the schema gives types, and what is
 * needed here is a value that passes a semantic check the schema does not express.
 *
 * <p><strong>This is not a list of Tools.</strong> Whoever asks decides what a missing entry
 * means; every caller so far treats it as a failure, which is what makes a Tool added without
 * one go red rather than be skipped.
 */
final class ToolCalls {

    private ToolCalls() {
    }

    /** Arguments for {@code name}, or {@code null} when this file does not know the Tool. */
    static Map<String, Object> forTool(String name) {
        return switch (name) {
            case "list_issues", "list_labels" ->
                    Map.of("owner", "DemianLi", "repo", "project_mcp");

            case "get_issue" ->
                    Map.of("owner", "cli", "repo", "cli", "number", 14356);

            case "list_issue_comments" ->
                    Map.of("owner", "cli", "repo", "cli", "number", 14361);

            // `body` is non-blank on purpose: blankness is refused before `gh` runs.
            case "add_issue_comment" ->
                    Map.of("owner", "DemianLi", "repo", "project-mcp-sandbox",
                            "number", 1, "body", "hello");

            default -> null;
        };
    }
}

package io.github.demianli.projectmcp.wire;

import java.util.Map;

/**
 * Arguments that exercise each Tool's path through to a {@code gh} call.
 *
 * <p>Acceptance tests use these to drive every Tool. Each entry must survive pre-call
 * validation — blank body on add_issue_comment would fail with FIX_REQUEST before gh runs.
 * For writes, the entry must reach the write path; stopping at a pre-call check would make
 * WritePartitionAcceptanceTest see FIX_REQUEST when it expected CHECK_BEFORE_RETRY.
 *
 * <p>Schema types alone do not suffice; semantic checks are required. Missing entries are
 * treated as failures, so a Tool without one fails rather than skips.
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

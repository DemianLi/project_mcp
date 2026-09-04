package io.github.demianli.projectmcp.tool;

import java.util.ArrayList;
import java.util.List;

import io.github.demianli.projectmcp.gh.GhCli;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * The issue-reading Tools.
 *
 * <p>Parameters and return shape are fixed by
 * {@code docs/adr/0001-list-issues-parameters-and-return-shape.md}. The three known
 * limitations recorded there are repeated in the parameter descriptions below rather than
 * left in the ADR, so a Client meets them in the schema instead of discovering them at
 * runtime.
 */
@Component
public class IssueTools {

    static final int DEFAULT_LIMIT = 30;
    static final int MAX_LIMIT = 100;

    /** The seven fields of {@link IssueSummary}, in the spelling {@code gh} expects. */
    private static final String FIELDS = "number,title,state,labels,assignees,url,updatedAt";

    private final GhCli gh;
    private final IssueMapper mapper;

    public IssueTools(GhCli gh, IssueMapper mapper) {
        this.gh = gh;
        this.mapper = mapper;
    }

    @McpTool(name = "list_issues",
            // Spring AI's defaults are readOnlyHint=false / destructiveHint=true, which
            // would have this Tool advertise itself as a destructive write — the opposite
            // of the map's read-only constraint, and enough to make a Client ask the user
            // to confirm a listing.
            annotations = @McpTool.McpAnnotations(
                    title = "List issues",
                    readOnlyHint = true,
                    destructiveHint = false,
                    // GitHub is an open world: the same call can return different issues.
                    openWorldHint = true),
            description = """
            List issues in a GitHub repository, newest-created first. Returns an envelope \
            {items, count, truncated}; `truncated` is true when more issues exist beyond \
            this response. Each issue carries number, title, state, labels, assignees, url \
            and updatedAt — not the body, which `get_issue` is for.""")
    public ListResult<IssueSummary> listIssues(

            @McpToolParam(required = true,
                    description = "Repository owner, e.g. \"DemianLi\".")
            String owner,

            @McpToolParam(required = true,
                    description = "Repository name, e.g. \"project_mcp\".")
            String repo,

            @McpToolParam(required = false,
                    description = "Which issues to return. Defaults to OPEN.")
            IssueState state,

            @McpToolParam(required = false, description = """
                    Label names to filter by. Multiple labels intersect: an issue must \
                    carry every one of them to be returned. Omit to apply no label \
                    filter.""")
            List<String> labels,

            @McpToolParam(required = false, description = """
                    Maximum issues to return. Defaults to 30 and is capped at 100; values \
                    outside 1-100 are clamped rather than rejected. Asking for more than \
                    100 yields 100 with truncated=true, and the cap cannot be raised.""")
            Integer limit) {

        int effectiveLimit = clamp(limit);
        IssueState effectiveState = state == null ? IssueState.OPEN : state;

        List<String> args = new ArrayList<>(List.of(
                "issue", "list",
                "--repo", owner + "/" + repo,
                "--state", effectiveState.forGh(),
                // One spare, so `truncated` can mean "more exist" rather than merely
                // "your limit was clamped".
                "--limit", Integer.toString(effectiveLimit + 1),
                "--json", FIELDS));

        if (labels != null) {
            for (String label : labels) {
                args.add("--label");
                args.add(label);
            }
        }

        return mapper.toEnvelope(gh.run(args), effectiveLimit);
    }

    /**
     * Clamps at both ends. The ceiling protects the Client's context window; the floor
     * exists because {@code gh} rejects {@code --limit 0} and negatives outright. One rule
     * on one parameter is easier to predict than clamping above and throwing below.
     */
    private static int clamp(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        return Math.clamp(limit.intValue(), 1, MAX_LIMIT);
    }
}

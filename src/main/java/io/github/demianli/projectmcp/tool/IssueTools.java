package io.github.demianli.projectmcp.tool;

import java.util.ArrayList;
import java.util.List;

import io.github.demianli.projectmcp.gh.GhCli;
import io.github.demianli.projectmcp.gh.Remedy;
import io.github.demianli.projectmcp.gh.ToolFailure;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * The issue-reading Tools.
 *
 * <p>Parameters and the success shape are fixed by
 * {@code docs/adr/0001-list-issues-parameters-and-return-shape.md}; the failure shape by
 * {@code docs/adr/0002-failure-contract-for-gh-calls.md}. The three known
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

    /**
     * The twelve fields of {@link IssueDetail}.
     *
     * <p>{@code projectCards} must never appear here or in any future field list: Projects
     * (classic) is sunset, and asking for it fails the whole call with a GraphQL error
     * rather than returning an empty value.
     */
    private static final String DETAIL_FIELDS =
            FIELDS + ",body,author,createdAt,closedAt,stateReason";

    /** What {@code gh} puts in the {@code url} of a pull request but never of an issue. */
    private static final String PULL_REQUEST_PATH = "/pull/";

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
    // Returns CallToolResult rather than the Envelope directly, because a failure has to
    // carry structuredContent and an isError flag, and a Java method has one return type.
    // Spring AI passes a CallToolResult through untouched; the success branch below
    // reproduces exactly what it would otherwise have built.
    public CallToolResult listIssues(

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

        try {
            return ToolResults.of(mapper.toEnvelope(gh.run(args), effectiveLimit));
        } catch (ToolFailure e) {
            return ToolResults.failure(e);
        }
    }

    @McpTool(name = "get_issue",
            annotations = @McpTool.McpAnnotations(
                    title = "Get issue",
                    readOnlyHint = true,
                    destructiveHint = false,
                    openWorldHint = true),
            description = """
            Read one issue in full. Returns a flat object of twelve fields — the seven \
            `list_issues` reports, plus body, author, createdAt, closedAt and stateReason. \
            Comments are not included, and no Tool returns them yet.""")
    public CallToolResult getIssue(

            @McpToolParam(required = true,
                    description = "Repository owner, e.g. \"DemianLi\".")
            String owner,

            @McpToolParam(required = true,
                    description = "Repository name, e.g. \"project_mcp\".")
            String repo,

            @McpToolParam(required = true, description = """
                    Must be an issue number. GitHub numbers issues and pull requests from \
                    one sequence; a pull request number is rejected.""")
            int number) {

        List<String> args = List.of(
                "issue", "view", Integer.toString(number),
                "--repo", owner + "/" + repo,
                "--json", DETAIL_FIELDS);

        try {
            // Mapped first, then judged. IssueMapper stays a pure function of a string and
            // knows nothing about failure; the semantic check runs on the record it
            // returns, so the payload is parsed exactly once. The next Tool that has to
            // reject something it successfully fetched should split the same way.
            IssueDetail issue = mapper.toDetail(gh.run(args));
            if (issue.url().contains(PULL_REQUEST_PATH)) {
                return ToolResults.failure(notAnIssue(number, issue.url()));
            }
            return ToolResults.of(issue);
        } catch (ToolFailure e) {
            return ToolResults.failure(e);
        }
    }

    /**
     * The one failure this Server reports that {@code gh} did not produce.
     *
     * <p>{@code gh issue view} accepts a pull request number and answers with pull request
     * data, because GitHub's data model makes every pull request an issue — not the
     * reverse. Returning it with a marker was rejected: seen through the issue lens a pull
     * request is <em>half</em> a pull request, since {@code isDraft}, {@code headRefName},
     * {@code mergeable}, reviews and the diff have no field on {@code gh issue view} at
     * all, so the marker would certify a payload silently missing everything that makes a
     * pull request one.
     *
     * <p>The sentence is built per call rather than being a constant: it names the number
     * that was asked for and carries the URL out of the payload just parsed, which is the
     * one thing a caller who genuinely wanted that pull request can still act on. That is
     * also what keeps {@link Remedy#FIX_REQUEST} honest here — see ADR-0003.
     *
     * <p>{@code stderr} is empty because there was none: {@code gh} did not fail.
     */
    private static ToolFailure notAnIssue(int number, String url) {
        return new ToolFailure(Remedy.FIX_REQUEST,
                "#" + number + " is a pull request, not an issue. This Server reads issues "
                        + "only; it has no Tool for pull requests. If that number is what "
                        + "you wanted: " + url,
                "", null);
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

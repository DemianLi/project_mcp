package io.github.demianli.projectmcp.tool;

import java.util.ArrayList;
import java.util.List;

import io.github.demianli.projectmcp.gh.GhCli;
import io.github.demianli.projectmcp.gh.ToolFailure;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * The comment-reading Tool.
 *
 * <p>A third component, for the reason {@link LabelTools} gives. Everything shared is shared
 * through code this class calls too — {@link Limits} for the {@code limit} rule,
 * {@link ToolResults} for the success and failure shapes, {@link GhCli} for the contract.
 *
 * <p>What is <em>not</em> shared, and must not be copied from the other two: this is the
 * first Tool that reaches GitHub over {@code gh api graphql} rather than porcelain
 * (ADR-0005), so the query document lives here, the fields are chosen by asking for them
 * rather than trimmed on arrival, and the Envelope carries two keys the others do not
 * (ADR-0006).
 *
 * <p>The shape is fixed by
 * {@code docs/adr/0006-list-issue-comments-parameters-and-return-shape.md}; the failure
 * shape by {@code docs/adr/0002-failure-contract-for-gh-calls.md}. The limitations recorded
 * in ADR-0006 are repeated in the descriptions below rather than left there, so a Client
 * meets them in the schema instead of discovering them at runtime.
 */
@Component
public class CommentTools {

    /**
     * The one query, serving both the first call and every continuation.
     *
     * <p>{@code $before} is nullable and simply absent on the first call, so there is no
     * second document to keep in step with this one — verified against the real endpoint
     * both ways.
     *
     * <p>{@code last:} rather than {@code first:}: the window opens on the newest comments
     * and walks backwards. Ordering <em>within</em> a response stays ascending, so "the
     * newest thirty" is not "the discussion backwards".
     */
    private static final String QUERY = """
            query($owner:String!, $name:String!, $number:Int!, $last:Int!, $before:String) {
              repository(owner:$owner, name:$name) {
                issue(number:$number) {
                  comments(last:$last, before:$before) {
                    totalCount
                    pageInfo { hasPreviousPage startCursor }
                    nodes { author { login } authorAssociation createdAt body url }
                  }
                }
              }
            }""";

    private final GhCli gh;
    private final CommentMapper mapper;

    public CommentTools(GhCli gh, CommentMapper mapper) {
        this.gh = gh;
        this.mapper = mapper;
    }

    @McpTool(name = "list_issue_comments",
            annotations = @McpTool.McpAnnotations(
                    title = "List issue comments",
                    readOnlyHint = true,
                    destructiveHint = false,
                    openWorldHint = true),
            description = """
            Read the comments on one issue, newest first: the first response holds the most \
            recent comments, in ascending time order among themselves. Returns an envelope \
            {items, count, truncated, totalCount, nextCursor}; each comment carries author, \
            authorAssociation, createdAt, body and url. To read further back, pass the \
            `nextCursor` you were given; it is null once the oldest comment is in hand.""")
    public CallToolResult listIssueComments(

            @McpToolParam(required = true,
                    description = "Repository owner, e.g. \"DemianLi\".")
            String owner,

            @McpToolParam(required = true,
                    description = "Repository name, e.g. \"project_mcp\".")
            String repo,

            @McpToolParam(required = true, description = """
                    Must be an issue number. GitHub numbers issues and pull requests from \
                    one sequence; a pull request number is rejected.""")
            int number,

            @McpToolParam(required = false, description = """
                    Maximum comments to return. Defaults to 30 and is capped at 100; values \
                    outside 1-100 are clamped rather than rejected. Unlike this Server's \
                    other caps, 100 is also GitHub's own on this route.""")
            Integer limit,

            @McpToolParam(required = false, description = """
                    Where to continue from: the `nextCursor` of a previous response, passed \
                    back unchanged. Omit it to start from the newest comments. A cursor is \
                    only valid for the issue it came from, and must not be constructed.""")
            String cursor) {

        int effectiveLimit = Limits.clamp(limit);

        List<String> args = new ArrayList<>(List.of(
                "api", "graphql",
                "-f", "query=" + QUERY,
                "-f", "owner=" + owner,
                "-f", "name=" + repo,
                "-F", "number=" + number,
                // No spare row here, and not because one is unnecessary. `first:`/`last:`
                // cap at 100 on GitHub's side -- `Limits.clamp` caps at 100 too, so the
                // spare would be exactly the 101 that hard-errors, with a stderr `classify`
                // does not recognise. `truncated` comes from hasPreviousPage instead, which
                // the response volunteers. See ADR-0006.
                "-F", "last=" + effectiveLimit));

        try {
            // Before the call, so a cursor from the wrong issue costs nothing to reject.
            String before = Cursors.unwrap(owner, repo, number, cursor);
            if (before != null) {
                // -f, not -F: -F coerces a value that looks numeric, and a cursor is a
                // string whatever it happens to look like.
                args.add("-f");
                args.add("before=" + before);
            }
            return ToolResults.of(mapper.toPage(gh.run(args), owner, repo, number));
        } catch (ToolFailure e) {
            return ToolResults.failure(e);
        }
    }
}

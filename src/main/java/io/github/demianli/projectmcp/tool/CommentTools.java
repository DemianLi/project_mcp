package io.github.demianli.projectmcp.tool;

import java.util.ArrayList;
import java.util.List;

import io.github.demianli.projectmcp.gh.GhCli;
import io.github.demianli.projectmcp.gh.Remedy;
import io.github.demianli.projectmcp.gh.ToolFailure;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * The comment Tools: list and add. These reach GitHub via {@code gh api graphql}, not
 * porcelain, so the queries live here and fields are selected explicitly.
 *
 * <p><strong>Every GraphQL variable typed {@code String} goes out with {@code -f}.</strong>
 * The {@code -f} flag treats the value as a literal string. The {@code -F} flag has three
 * other interpretations: numeric or boolean literals pass through as JSON scalars (invalid
 * against a {@code String!} variable), template variables like {@code {owner}} expand to the
 * repository's owner, and {@code @path} or {@code @-} read from a local file or stdin.
 * Because Client strings are passed uninterpreted, a string value like {@code @path} cannot
 * go through {@code -F} without reading an unintended file. Numeric parameters like
 * {@code number} and {@code last} correctly use {@code -F}; string parameters use {@code -f}.
 *
 * <p>{@code add_issue_comment} is the Server's only write Tool. It uses {@link GhCli#runWrite}
 * instead of {@link GhCli#run} to set the failure contract for writes (Remedy determines
 * whether to retry or check before retrying). See docs/design.md#failure-contract.
 */
@Component
public class CommentTools {

    private static final Logger log = LoggerFactory.getLogger(CommentTools.class);

    /**
     * GraphQL query for paginating comments. Uses {@code last:} (most recent first)
     * with an optional {@code before} cursor for continuation.
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

    /**
     * Converts an issue number to its GraphQL ID. Guards against pull requests:
     * {@code repository.issue(number:)} fails on a pull request number with a specific
     * stderr pattern that {@link GhCli} maps to {@code FIX_REQUEST}.
     */
    private static final String ISSUE_ID = """
            query($owner:String!, $name:String!, $number:Int!) {
              repository(owner:$owner, name:$name) {
                issue(number:$number) { id }
              }
            }""";

    /**
     * GraphQL mutation to post a comment. Returns the comment URL from the response.
     */
    private static final String ADD_COMMENT = """
            mutation($subjectId:ID!, $body:String!) {
              addComment(input:{subjectId:$subjectId, body:$body}) {
                commentEdge { node { url } }
              }
            }""";

    private final GhCli gh;
    private final CommentMapper mapper;
    private final WriteLimiter writes;

    public CommentTools(GhCli gh, CommentMapper mapper, WriteLimiter writes) {
        this.gh = gh;
        this.mapper = mapper;
        this.writes = writes;
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
        IssueRef issue = new IssueRef(owner, repo, number);

        List<String> args = new ArrayList<>(List.of(
                "api", "graphql",
                "-f", "query=" + QUERY,
                "-f", "owner=" + owner,
                "-f", "name=" + repo,
                "-F", "number=" + number,
                // No spare row here (see list_issues for that pattern). GitHub's GraphQL caps
                // at 100, so requesting 101 would hard-error. `truncated` comes from
                // hasPreviousPage instead.
                "-F", "last=" + effectiveLimit));

        return ToolResults.attempt("list_issue_comments", owner, repo, () -> {
            // GraphQL route does not use --repo, but validate for consistency.
            Repos.check(owner, repo);

            // Before the call, so a cursor from the wrong issue costs nothing to reject.
            String before = Cursors.unwrap(issue, cursor);
            if (before != null) {
                // Use -f for cursor strings (see class javadoc).
                args.add("-f");
                args.add("before=" + before);
            }
            return mapper.toPage(gh.run(args), issue);
        });
    }
    @McpTool(name = "add_issue_comment",
            annotations = @McpTool.McpAnnotations(
                    title = "Add a comment to an issue",
                    readOnlyHint = false,
                    destructiveHint = false,
                    idempotentHint = false,
                    openWorldHint = true),
            description = """
            Add a comment to a GitHub issue. Returns {url}: the new comment's permalink, \
            and nothing else — the rest is either what you sent or derivable from it. Two \
            calls to GitHub that are not atomic: the issue is looked up, then written to. \
            A pull request number is rejected, and a blank body is refused before GitHub \
            is called.""")
    public CallToolResult addIssueComment(

            @McpToolParam(required = true,
                    description = "Repository owner, e.g. \"DemianLi\".")
            String owner,

            @McpToolParam(required = true,
                    description = "Repository name, e.g. \"project-mcp-sandbox\".")
            String repo,

            @McpToolParam(required = true, description = """
                    Must be an issue number. GitHub numbers issues and pull requests from \
                    one sequence; a pull request number is rejected rather than commented \
                    on.""")
            int number,

            @McpToolParam(required = true, description = """
                    The comment's Markdown. Must not be blank — whitespace alone counts as \
                    blank, and is refused before GitHub is called.""")
            String body) {

        return ToolResults.attempt("add_issue_comment", owner, repo, () -> {
            // Validate inside the lambda so the failure carries structured content.
            // GitHub rejects blank (whitespace-only) bodies, so check before calling gh.
            if (body == null || body.isBlank()) {
                throw blankBody();
            }

            // Validate for consistency, though GraphQL route does not use --repo.
            Repos.check(owner, repo);

            // Check rate limit after validation and before any gh call.
            writes.acquire();

            // Call one: resolve issue number to GraphQL ID (uses read route).
            String subjectId = mapper.toIssueId(gh.run(List.of(
                    "api", "graphql",
                    "-f", "query=" + ISSUE_ID,
                    "-f", "owner=" + owner,
                    "-f", "name=" + repo,
                    "-F", "number=" + number)));

            // Call two: post the comment (uses write route, see docs/design.md#writes).
            // Use -f for all string values (see class javadoc).
            NewComment written = mapper.toNewComment(gh.runWrite(List.of(
                    "api", "graphql",
                    "-f", "query=" + ADD_COMMENT,
                    "-f", "subjectId=" + subjectId,
                    "-f", "body=" + body)));

            // Log the permalink; it identifies the comment without exposing content.
            MDC.put("commentUrl", written.url());
            log.info("comment written");
            MDC.remove("commentUrl");

            return written;
        });
    }

    /**
     * Failure for a blank comment body. Reported before calling gh.
     */
    private static ToolFailure blankBody() {
        return new ToolFailure(Remedy.FIX_REQUEST,
                "`body` is blank, so there is nothing to post. GitHub rejects a blank "
                        + "comment body, and counts whitespace alone as blank.",
                "", null);
    }
}

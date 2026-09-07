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
 * The comment Tools: one that reads an issue's discussion, and one that adds to it.
 *
 * <p>A third component, for the reason {@link LabelTools} gives. Everything shared is shared
 * through code this class calls too — {@link Limits} for the {@code limit} rule,
 * {@link ToolResults} for the success and failure shapes, {@link GhCli} for the contract.
 *
 * <p>What is <em>not</em> shared, and must not be copied from the other two components:
 * these are the Tools that reach GitHub over {@code gh api graphql} rather than porcelain
 * (ADR-0005 for the read, ADR-0007 for the write), so the documents live here, the fields
 * are chosen by asking for them rather than trimmed on arrival, and
 * {@code list_issue_comments}' Envelope carries two keys the others do not (ADR-0006).
 *
 * <p><strong>Every GraphQL variable here is sent with {@code -f}, and none with
 * {@code -F}.</strong> The two flags are not spellings of one thing. {@code -f} adds the
 * value as a string, taken literally. {@code -F} has three magic readings of it, all three
 * measured on this exact route: {@code 123} and {@code true} arrive as JSON scalars, which
 * against a {@code String!} variable is a type error; {@code {owner}}, {@code {repo}} and
 * {@code {branch}} are replaced by whatever repository this Server's working directory
 * resolves to; and {@code @path} or {@code @-} reads the value out of a local file or out
 * of stdin and sends <em>that</em>.
 *
 * <p>Which matters because the values below are a Client's strings. Under {@code -F} a
 * {@code body} of {@code @} followed by a path would post a file off this machine to
 * GitHub, over a Tool a Client was told writes a comment; a {@code cursor} of
 * {@code {owner}} would ask for something nobody typed. The rule that holds is therefore
 * not "avoid {@code -F}" — {@code -F number=} and {@code -F last=} below are correct, and
 * are exactly the two whose values are Java {@code int}s and so can carry none of the
 * three. It is that a value typed {@code String} here goes out with {@code -f}.
 *
 * <p><strong>{@code add_issue_comment} is the first Tool in this Server that changes
 * anything.</strong> Two things follow that a reader should not have to infer. Its second
 * call goes through {@link GhCli#runWrite} rather than {@link GhCli#run}, which is what
 * decides whether an abandoned call tells a Client to retry or to go and check (ADR-0008);
 * and its {@code annotations} are the first here where {@code destructiveHint} and
 * {@code idempotentHint} mean anything at all, because the spec makes both meaningful only
 * when {@code readOnlyHint} is false.
 *
 * <p>The read's shape is fixed by
 * {@code docs/adr/0006-list-issue-comments-parameters-and-return-shape.md} and the write's
 * by {@code docs/adr/0007-add-issue-comment-parameters-return-and-annotations.md}; the
 * failure shape by {@code docs/adr/0002-failure-contract-for-gh-calls.md} as amended for
 * writes by {@code docs/adr/0008-failure-contract-for-writes.md}. The limitations recorded
 * in those ADRs are repeated in the descriptions below rather than left there, so a Client
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

    /**
     * The lookup {@code add_issue_comment} makes first, and the whole pull-request guard.
     *
     * <p>{@code addComment} takes a {@code subjectId}, not a number, so something has to
     * turn one into the other. Doing it this way is what makes writing into a pull request
     * impossible rather than merely unintended: {@code repository.issue(number:)} cannot
     * resolve a pull request's id, and {@code gh} exits 1 with
     * {@code Could not resolve to an Issue with the number of N} — a string
     * {@link GhCli}'s {@code classify} already turns into {@code FIX_REQUEST}. Both
     * {@code gh issue comment} and the REST endpoint resolve a number without caring which
     * kind it is, and both were measured writing into a pull request. See ADR-0007.
     */
    private static final String ISSUE_ID = """
            query($owner:String!, $name:String!, $number:Int!) {
              repository(owner:$owner, name:$name) {
                issue(number:$number) { id }
              }
            }""";

    /**
     * The mutation, selecting the one field {@link NewComment} keeps.
     *
     * <p>{@code clientMutationId} is not sent. It is the obvious candidate for an
     * idempotency key and is not one: GitHub's schema describes it as identifying the client
     * performing the mutation, and the same key with the same body twice was measured
     * producing two distinct comments. See ADR-0008.
     */
    private static final String ADD_COMMENT = """
            mutation($subjectId:ID!, $body:String!) {
              addComment(input:{subjectId:$subjectId, body:$body}) {
                commentEdge { node { url } }
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
        IssueRef issue = new IssueRef(owner, repo, number);

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

        return ToolResults.attempt(() -> {
            // Before the call, so a cursor from the wrong issue costs nothing to reject.
            String before = Cursors.unwrap(issue, cursor);
            if (before != null) {
                // -f, not -F, for the reason the class javadoc measures. A cursor is a
                // Client's string whatever it happens to look like -- and under -F one
                // beginning `@` would be read as a filename off this machine.
                args.add("-f");
                args.add("before=" + before);
            }
            return mapper.toPage(gh.run(args), issue);
        });
    }
    @McpTool(name = "add_issue_comment",
            annotations = @McpTool.McpAnnotations(
                    title = "Add a comment to an issue",
                    // The first false in this Server, and the switch that gives the next two
                    // any meaning at all: the spec says destructiveHint and idempotentHint
                    // are meaningful only when readOnlyHint is false.
                    readOnlyHint = false,
                    // The spec's axis here is additive versus destructive, not reversible
                    // versus irreversible. A comment overwrites nothing and removes nothing.
                    // That this Server exposes no delete is true and is a different
                    // question; answering with it would report something a Client did not
                    // ask about.
                    destructiveHint = false,
                    // Not a judgement: two calls with the same four arguments produce two
                    // comments, and no upsert is exposed. Written out although the spec's
                    // default is also false and the wire bytes are identical either way,
                    // because this is the one place in this Server where it means anything.
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

        return ToolResults.attempt(() -> {
            // Before the call, for the reason Cursors.unwrap is: a blank body has no possible
            // success, so letting `gh` discover it spends a round trip held open by a
            // 30-second timeout. GitHub's predicate is blankness rather than emptiness --
            // `--body " "` fails exactly as `--body ""` does -- so isBlank(), not isEmpty().
            // See ADR-0007.
            //
            // Inside the lambda, not before it. Thrown out of this method the failure would
            // be caught by Spring AI instead, which answers isError with no
            // structuredContent -- the Remedy would be gone and the refusal would still
            // look right. See ToolResults.
            if (body == null || body.isBlank()) {
                throw blankBody();
            }

            // Call one is a read, and takes the read route deliberately. A timeout here
            // means nothing was written, so CHECK_BEFORE_RETRY would send a Client looking
            // for a comment that cannot exist.
            String subjectId = mapper.toIssueId(gh.run(List.of(
                    "api", "graphql",
                    "-f", "query=" + ISSUE_ID,
                    "-f", "owner=" + owner,
                    "-f", "name=" + repo,
                    "-F", "number=" + number)));

            // No check that subjectId is non-empty. `gh` exiting zero on that query without
            // an id should be unreachable, and an empty one cannot address anything: GitHub
            // answers `Could not resolve to a node with the global id of ''` and exits 1,
            // which classify() floors at UNKNOWN with nothing written. A branch here would
            // be an unreachable one wearing a Remedy that fits it badly.

            // Call two writes. runWrite, not run -- they differ in nothing a happy-path test
            // can see, and in everything a Client is told at the one moment a comment may
            // already exist. See ADR-0008.
            //
            // The Remedy that comes back is not rewritten anywhere in this class. GhCli is
            // where the contract knows a write from a read; adjusting it here would move
            // half the contract into the Tools and every future write Tool would copy it.
            // There is no longer a catch to be tempted into doing it in.
            //
            // -f throughout, never -F, for the reason the class javadoc measures. A body of
            // "123" arriving as a JSON number against `body:String!` is the mildest of the
            // three readings -- a body beginning `@` would post a file off this machine to
            // GitHub, over a Tool a Client is told writes a comment.
            return mapper.toNewComment(gh.runWrite(List.of(
                    "api", "graphql",
                    "-f", "query=" + ADD_COMMENT,
                    "-f", "subjectId=" + subjectId,
                    "-f", "body=" + body)));
        });
    }

    /**
     * The failure for a body GitHub would reject, reported without asking it.
     *
     * <p>{@code stderr} is empty because there was none: {@code gh} did not run. This is the
     * third failure this Server invents rather than inherits, after a pull request number
     * reaching {@code get_issue} and a cursor from the wrong issue.
     */
    private static ToolFailure blankBody() {
        return new ToolFailure(Remedy.FIX_REQUEST,
                "`body` is blank, so there is nothing to post. GitHub rejects a blank "
                        + "comment body, and counts whitespace alone as blank.",
                "", null);
    }
}

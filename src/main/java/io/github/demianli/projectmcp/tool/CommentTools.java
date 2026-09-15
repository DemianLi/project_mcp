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
 * 留言 Tools：列出與新增。兩者經由 {@code gh api graphql} 而非 porcelain 指令存取
 * GitHub，因此查詢寫在這裡，欄位也明確指定。
 *
 * <p><strong>型別為 {@code String} 的 GraphQL 變數一律用 {@code -f} 送出。</strong>
 * {@code -f} 把值當成字面字串；{@code -F} 另有三種解讀：數字或布林字面值會成為 JSON
 * 純量（對 {@code String!} 變數無效）、{@code {owner}} 這類模板變數會展開成 repository
 * 的 owner、{@code @path} 或 {@code @-} 會讀取本機檔案或 stdin。Client 的字串原樣傳遞，
 * 像 {@code @path} 這樣的值若走 {@code -F}，就會讀到不該讀的檔案。{@code number} 與
 * {@code last} 等數字參數正確地使用 {@code -F}；字串參數使用 {@code -f}。
 *
 * <p>{@code add_issue_comment} 是本 Server 唯一的寫入 Tool。它用 {@link GhCli#runWrite}
 * 而非 {@link GhCli#run}，以套用寫入的失敗契約（由 Remedy 決定直接重試或先檢查再重試）。
 * 見 docs/design.md#failure-contract。
 */
@Component
public class CommentTools {

    private static final Logger log = LoggerFactory.getLogger(CommentTools.class);

    /**
     * 分頁讀取留言的 GraphQL 查詢。用 {@code last:}（最新的優先），並以選用的
     * {@code before} cursor 接續下一頁。
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
     * 把 issue 編號轉成 GraphQL ID，同時擋下 pull request：對 pull request 編號，
     * {@code repository.issue(number:)} 會以特定 stderr 失敗，{@link GhCli} 將其對應為
     * {@code FIX_REQUEST}。
     */
    private static final String ISSUE_ID = """
            query($owner:String!, $name:String!, $number:Int!) {
              repository(owner:$owner, name:$name) {
                issue(number:$number) { id }
              }
            }""";

    /**
     * 新增留言的 GraphQL mutation，從回應取得留言 URL。
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
                // 這裡不多要一列（多要一列的做法見 list_issues）。GitHub GraphQL 上限為 100，
                // 要求 101 會直接出錯，所以 `truncated` 改由 hasPreviousPage 決定。
                "-F", "last=" + effectiveLimit));

        return ToolResults.attempt("list_issue_comments", owner, repo, () -> {
            // GraphQL 路徑不使用 --repo，但為了一致仍做驗證。
            Repos.check(owner, repo);

            // 在呼叫前檢查，拒絕其他 issue 的 cursor 不花任何成本。
            String before = Cursors.unwrap(issue, cursor);
            if (before != null) {
                // cursor 字串用 -f（見 class javadoc）。
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
            // 在 lambda 內驗證，失敗才會帶有 structured content。
            // GitHub 拒絕空白（只有空白字元）的 body，所以在呼叫 gh 前檢查。
            if (body == null || body.isBlank()) {
                throw blankBody();
            }

            // GraphQL 路徑不使用 --repo，但為了一致仍做驗證。
            Repos.check(owner, repo);

            // 驗證通過後、任何 gh 呼叫之前，檢查 rate limit。
            writes.acquire();

            // 第一次呼叫：把 issue 編號解析成 GraphQL ID（走讀取路徑）。
            String subjectId = mapper.toIssueId(gh.run(List.of(
                    "api", "graphql",
                    "-f", "query=" + ISSUE_ID,
                    "-f", "owner=" + owner,
                    "-f", "name=" + repo,
                    "-F", "number=" + number)));

            // 第二次呼叫：新增留言（走寫入路徑，見 docs/design.md#writes）。
            // 所有字串值都用 -f（見 class javadoc）。
            NewComment written = mapper.toNewComment(gh.runWrite(List.of(
                    "api", "graphql",
                    "-f", "query=" + ADD_COMMENT,
                    "-f", "subjectId=" + subjectId,
                    "-f", "body=" + body)));

            // 記錄永久連結：它能識別這則留言，又不暴露內容。
            MDC.put("commentUrl", written.url());
            log.info("comment written");
            MDC.remove("commentUrl");

            return written;
        });
    }

    /**
     * 留言 body 為空白時的失敗，在呼叫 gh 前回報。
     */
    private static ToolFailure blankBody() {
        return new ToolFailure(Remedy.FIX_REQUEST,
                "`body` is blank, so there is nothing to post. GitHub rejects a blank "
                        + "comment body, and counts whitespace alone as blank.",
                "", null);
    }
}

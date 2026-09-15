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
 * 讀取 issue 的 Tools：列出 issue 與取得單一 issue 內容。
 */
@Component
public class IssueTools {

    /** {@link IssueSummary} 的七個欄位，採 {@code gh} 要求的拼法。 */
    private static final String FIELDS = "number,title,state,labels,assignees,url,updatedAt";

    /**
     * {@link IssueDetail} 的十二個欄位。
     *
     * <p>{@code projectCards} 絕不能出現在這裡或任何欄位清單中：Projects（classic）已停用，
     * 要求它會讓整個呼叫以 GraphQL 錯誤失敗，而不是回傳空值。
     */
    private static final String DETAIL_FIELDS =
            FIELDS + ",body,author,createdAt,closedAt,stateReason";

    /** {@code gh} 只會放在 pull request（而非 issue）{@code url} 中的片段。 */
    private static final String PULL_REQUEST_PATH = "/pull/";

    private final GhCli gh;
    private final IssueMapper mapper;

    public IssueTools(GhCli gh, IssueMapper mapper) {
        this.gh = gh;
        this.mapper = mapper;
    }

    @McpTool(name = "list_issues",
            annotations = @McpTool.McpAnnotations(
                    title = "List issues",
                    readOnlyHint = true,
                    destructiveHint = false,
                    openWorldHint = true),
            description = """
            List issues in a GitHub repository, newest-created first. Returns an envelope \
            {items, count, truncated}; `truncated` is true when more issues exist beyond \
            this response. Each issue carries number, title, state, labels, assignees, url \
            and updatedAt — not the body, which `get_issue` is for.""")
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

        int effectiveLimit = Limits.clamp(limit);
        IssueState effectiveState = state == null ? IssueState.OPEN : state;

        return ToolResults.attempt("list_issues", owner, repo, () -> {
            // 在 lambda 內組 argv，驗證失敗才會帶有 structured content。
            List<String> args = new ArrayList<>(List.of(
                    "issue", "list",
                    "--repo", Repos.slug(owner, repo),
                    "--state", effectiveState.forGh(),
                    // 多要一筆，以偵測是否還有更多 issue。
                    "--limit", Integer.toString(effectiveLimit + 1),
                    "--json", FIELDS));

            if (labels != null) {
                for (String label : labels) {
                    args.add("--label");
                    args.add(label);
                }
            }

            return mapper.toEnvelope(gh.run(args), effectiveLimit);
        });
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
            Comments are not included; use `list_issue_comments` to read them.""")
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

        return ToolResults.attempt("get_issue", owner, repo, () -> {
            List<String> args = List.of(
                    "issue", "view", Integer.toString(number),
                    "--repo", Repos.slug(owner, repo),
                    "--json", DETAIL_FIELDS);

            // 先解析再驗證。語意檢查（擋下 pull request）作用在解析後的 issue 上，
            // 驗證時可直接取用 url，不必重新解析。
            IssueDetail issue = mapper.toDetail(gh.run(args));
            if (issue.url().contains(PULL_REQUEST_PATH)) {
                throw notAnIssue(number, issue.url());
            }
            return issue;
        });
    }

    /**
     * 傳給 {@code get_issue} 的是 pull request 編號時的失敗。{@code gh} 接受 pull request
     * 編號，但回傳的資料不完整。錯誤訊息附上 url，讓呼叫者能直接前往該 pull request。
     */
    private static ToolFailure notAnIssue(int number, String url) {
        return new ToolFailure(Remedy.FIX_REQUEST,
                "#" + number + " is a pull request, not an issue. This Server's Tools "
                        + "work on issues only; it has none for pull requests. If that "
                        + "number is what you wanted: " + url,
                "", null);
    }
}

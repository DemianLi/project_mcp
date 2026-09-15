package io.github.demianli.projectmcp.tool;

import java.util.ArrayList;
import java.util.List;

import io.github.demianli.projectmcp.gh.GhCli;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * 讀取 label 的 Tool。獨立成一個 component，讓 label 與 issue 互不相依。
 */
@Component
public class LabelTools {

    /** {@link LabelSummary} 的兩個欄位，採 {@code gh} 要求的拼法。 */
    private static final String FIELDS = "name,description";

    private final GhCli gh;
    private final LabelMapper mapper;

    public LabelTools(GhCli gh, LabelMapper mapper) {
        this.gh = gh;
        this.mapper = mapper;
    }

    @McpTool(name = "list_labels",
            annotations = @McpTool.McpAnnotations(
                    title = "List labels",
                    readOnlyHint = true,
                    destructiveHint = false,
                    openWorldHint = true),
            description = """
            List a repository's labels, alphabetically by name. Returns an envelope \
            {items, count, truncated}; each label carries name and description, and `name` \
            is exactly the string `list_issues` accepts in its `labels` parameter. On a \
            repository with hundreds of labels, paging with `limit` will not get you the \
            vocabulary — the alphabetical head is not a representative sample — use \
            `search` instead.""")
    public CallToolResult listLabels(

            @McpToolParam(required = true,
                    description = "Repository owner, e.g. \"DemianLi\".")
            String owner,

            @McpToolParam(required = true,
                    description = "Repository name, e.g. \"project_mcp\".")
            String repo,

            @McpToolParam(required = false, description = """
                    Maximum labels to return. Defaults to 30 and is capped at 100; values \
                    outside 1-100 are clamped rather than rejected. The cap is this \
                    Server's own — `gh` imposes none — and raising it is not the way to \
                    read a large label set; `search` is.""")
            Integer limit,

            @McpToolParam(required = false, description = """
                    Case-insensitive substring filter, matched against label names AND \
                    descriptions — not a query language. Omit or leave blank to apply no \
                    filter. While a search is in effect the alphabetical ordering does not \
                    hold: GitHub's own match order applies instead.""")
            String search) {

        int effectiveLimit = Limits.clamp(limit);
        boolean filtering = search != null && !search.isBlank();

        return ToolResults.attempt("list_labels", owner, repo, () -> {
            // 在 lambda 內組 argv，驗證失敗才會帶有 structured content。
            List<String> args = new ArrayList<>(List.of(
                    "label", "list",
                    "--repo", Repos.slug(owner, repo),
                    // 多要一筆，以偵測是否還有更多 label。
                    "--limit", Integer.toString(effectiveLimit + 1),
                    "--json", FIELDS));

            // gh 的 --search 與 --sort/--order 互斥。本 Server 開放搜尋但不開放排序，所以搜尋時
            // 省略排序旗標，讓這種不合法的組合在 schema 層就不可能出現。
            if (filtering) {
                args.add("--search");
                args.add(search);
            } else {
                args.add("--sort");
                args.add("name");
                args.add("--order");
                args.add("asc");
            }

            return mapper.toEnvelope(gh.run(args), effectiveLimit);
        });
    }
}

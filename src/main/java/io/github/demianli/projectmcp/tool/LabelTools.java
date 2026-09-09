package io.github.demianli.projectmcp.tool;

import java.util.ArrayList;
import java.util.List;

import io.github.demianli.projectmcp.gh.GhCli;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * The label-reading Tools.
 *
 * <p>A second component rather than another method on {@link IssueTools}: labels are not
 * issues, and Spring AI discovers {@code @McpTool} methods on any bean, so the split costs
 * nothing. Everything shared is shared through code both classes call —
 * {@link Limits} for the {@code limit} rule, {@link ToolResults} for the success and failure
 * shapes, {@link ListResult} for the Envelope.
 *
 * <p>The shape is fixed by
 * {@code docs/adr/0004-list-labels-tool-shape-parameters-and-return.md}; the failure shape by
 * {@code docs/adr/0002-failure-contract-for-gh-calls.md}. The limitations recorded in
 * ADR-0004 are repeated in the descriptions below rather than left in the ADR, so a Client
 * meets them in the schema instead of discovering them at runtime.
 */
@Component
public class LabelTools {

    /** The two fields of {@link LabelSummary}, in the spelling {@code gh} expects. */
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
            // Composed in here, not above: Repos.slug can refuse, and a refusal
            // thrown outside this lambda loses its Remedy to Spring AI. See
            // ToolResults and ADR-0011.
            List<String> args = new ArrayList<>(List.of(
                    "label", "list",
                    "--repo", Repos.slug(owner, repo),
                    // One spare, as in list_issues, so `truncated` means "more exist" rather
                    // than merely "your limit was clamped". Verified to hold under --search.
                    "--limit", Integer.toString(effectiveLimit + 1),
                    "--json", FIELDS));

            // Ordering is a guarantee this Server makes, not a parameter it accepts -- and the
            // two flags must not be sent alongside --search, which `gh` refuses outright
            // ("cannot specify --order or --sort with --search", non-zero exit). Spring AI
            // derives its schema from this method's signature, so it has no oneOf and could not
            // publish that exclusion; keeping `sort` out of the signature is what makes the
            // illegal combination unreachable rather than merely rejected. Which is why these
            // two branches are exclusive and must stay that way.
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

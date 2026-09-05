package io.github.demianli.projectmcp.prototype;

import java.util.ArrayList;
import java.util.List;

import io.github.demianli.projectmcp.gh.GhCli;
import io.github.demianli.projectmcp.gh.ToolFailure;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Role;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * THROWAWAY PROTOTYPE — delete this package before merging anything.
 *
 * <p>Exists to answer one question for
 * <a href="https://github.com/DemianLi/project_mcp/issues/15">#15</a>: should
 * {@code list_labels} be a Tool or a Resource Template? Both are declared here, over the
 * same {@code gh label list} call, so the two can be read side by side in the Inspector
 * instead of argued about. Deliberately unpolished: no Envelope record, no mapper, no
 * tests, one hand-built JSON string.
 *
 * <p>Names are suffixed {@code _prototype} so nothing here can be mistaken for the real
 * surface if it ever escapes this branch.
 */
@Component
public class LabelsPrototype {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final GhCli gh;

    public LabelsPrototype(GhCli gh) {
        this.gh = gh;
    }

    // ---------------------------------------------------------------- shape A: a Tool

    @McpTool(name = "list_labels_prototype",
            annotations = @McpTool.McpAnnotations(
                    title = "List labels (PROTOTYPE)",
                    readOnlyHint = true, destructiveHint = false, openWorldHint = true),
            description = "PROTOTYPE. Shape A: labels as a Tool call.")
    public CallToolResult listLabelsAsTool(
            @McpToolParam(required = true, description = "Repository owner.") String owner,
            @McpToolParam(required = true, description = "Repository name.") String repo) {
        try {
            return CallToolResult.builder().addTextContent(labelsJson(owner, repo)).build();
        } catch (ToolFailure e) {
            // The whole of ADR-0002, available: a Remedy the caller can act on, gh's stderr
            // verbatim, and isError so the model is told this is a failure rather than data.
            return CallToolResult.builder()
                    .addTextContent(e.getMessage())
                    .structuredContent(java.util.Map.of(
                            "remedy", e.remedy().name(),
                            "message", e.getMessage(),
                            "stderr", e.stderr()))
                    .isError(true)
                    .build();
        }
    }

    // ------------------------------------------------- shape B: a Resource Template

    /**
     * A URI containing {@code {...}} is what makes this a Resource <em>Template</em> rather
     * than a Resource: {@code SyncMcpResourceProvider} splits on
     * {@code McpPredicates.isUriTemplate(uri)} and files it under
     * {@code resources/templates/list}. That split is what a stateless Server needs —
     * {@code resources/list} could not enumerate every repository on GitHub.
     *
     * <p>The failure path is the thing to look at. {@code ReadResourceResult} is
     * {@code (contents, _meta)} and nothing else — there is no {@code isError}. Anything
     * thrown here is turned by {@code SyncMcpResourceMethodCallback} into an
     * {@code McpError} with code {@code INVALID_PARAMS}, i.e. a JSON-RPC protocol error.
     * So this method has exactly two options, and both are visible in the Inspector below.
     */
    @McpResource(
            uri = "github://{owner}/{repo}/labels",
            name = "repository_labels_prototype",
            title = "Repository labels (PROTOTYPE)",
            description = "PROTOTYPE. Shape B: labels as a Resource Template.",
            mimeType = "application/json",
            annotations = @McpResource.McpAnnotations(audience = {Role.ASSISTANT}, priority = 0.5))
    public String labelsAsResource(String owner, String repo) {
        return labelsJson(owner, repo);
    }

    /**
     * The second option on the Resource path: swallow the failure and return it as content,
     * because there is no error channel. Kept as a separate template so both can be read in
     * the same session — a real implementation would have to pick one.
     */
    @McpResource(
            uri = "github+lenient://{owner}/{repo}/labels",
            name = "repository_labels_lenient_prototype",
            title = "Repository labels, failure-as-content (PROTOTYPE)",
            description = "PROTOTYPE. Shape B2: same, but a failure comes back as ordinary "
                    + "content because ReadResourceResult has no isError.",
            mimeType = "application/json",
            annotations = @McpResource.McpAnnotations(audience = {Role.ASSISTANT}, priority = 0.5))
    public String labelsAsResourceLenient(String owner, String repo) {
        try {
            return labelsJson(owner, repo);
        } catch (ToolFailure e) {
            return JSON.writeValueAsString(java.util.Map.of(
                    "remedy", e.remedy().name(),
                    "message", e.getMessage(),
                    "stderr", e.stderr()));
        }
    }

    // ------------------------------------------------------------------------ shared

    /** One `gh label list`, hand-shaped into the Envelope. No mapper, on purpose. */
    private String labelsJson(String owner, String repo) {
        String raw = gh.run(List.of(
                "label", "list", "--repo", owner + "/" + repo,
                "--limit", "31", "--json", "name,description,color"));

        JsonNode root = JSON.readTree(raw);
        List<String> names = new ArrayList<>();
        for (JsonNode label : root) {
            names.add(label.path("name").asString(""));
        }
        boolean truncated = names.size() > 30;
        if (truncated) {
            names = names.subList(0, 30);
        }
        return JSON.writeValueAsString(java.util.Map.of(
                "items", names, "count", names.size(), "truncated", truncated));
    }
}

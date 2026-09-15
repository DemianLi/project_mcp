package io.github.demianli.projectmcp.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.demianli.projectmcp.gh.FakeGh;
import io.github.demianli.projectmcp.gh.GhCli;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@code list_labels} response shape and verifies sort/search exclusion in argv.
 *
 * <p>Sort and search are mutually exclusive in GitHub's API; the Server enforces this by
 * never sending both, which tests verify through argv inspection. Fixtures are real captures
 * from {@code gh label list}.
 */
class ListLabelsTest {

    @TempDir Path tmp;

    /** A stand-in {@code gh} that writes down its arguments, then prints the fixture. */
    private LabelTools toolsReturning(String fixture) throws IOException {
        Path payload = tmp.resolve("payload.json");
        Files.writeString(payload, Files.readString(Path.of("src/test/resources/gh/" + fixture)));
        String script = FakeGh.writing(tmp,
                "printf '%s\\n' \"$@\" > " + tmp.resolve("argv.txt") + "\n"
                        + "cat " + payload);
        return new LabelTools(new GhCli(script, 30), new LabelMapper());
    }

    /** What the stand-in was called with, one argument per element. */
    private List<String> argv() throws IOException {
        return Files.readAllLines(tmp.resolve("argv.txt"));
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().get(0)).text();
    }

    @Test
    void labelsArriveInAnEnvelopeOfTwoFieldObjects() throws Exception {
        CallToolResult result = toolsReturning("label-list.json")
                .listLabels("DemianLi", "project_mcp", 100, null);

        assertThat(result.isError()).isFalse();
        assertThat(text(result))
                .as("standard envelope with items, count, truncated")
                .startsWith("{\"items\":[")
                .contains("\"count\":19")
                .contains("\"truncated\":false");

        assertThat(text(result))
                .as("two fields: name and description")
                .startsWith("{\"items\":[{\"name\":\"accessibility\",\"description\":"
                        + "\"Barrier affecting people with disabilities\"}")
                .doesNotContain("\"color\"", "\"id\"", "\"isDefault\"",
                        "\"createdAt\"", "\"updatedAt\"", "\"url\"");
    }

    @Test
    void withoutASearchTheSortFlagsAreSentAndTheOrderIsAGuarantee() throws Exception {
        toolsReturning("label-list.json").listLabels("DemianLi", "project_mcp", null, null);

        assertThat(argv()).containsSequence("--sort", "name").containsSequence("--order", "asc");
        assertThat(argv())
                .as("nothing asks gh to search when the Client did not")
                .doesNotContain("--search");
    }

    @Test
    void aSearchDropsTheSortFlagsBecauseGhRefusesBothTogether() throws Exception {
        // gh forbids combining --sort with --search; Server omits sort when searching.
        toolsReturning("label-list.json").listLabels("DemianLi", "project_mcp", null, "wayfinder");

        assertThat(argv()).containsSequence("--search", "wayfinder");
        assertThat(argv()).doesNotContain("--sort", "--order");
    }

    @Test
    void aBlankSearchIsOmittedRatherThanSentAsAnEmptyValue() throws Exception {
        // Blank search is treated as no search, preserving sort flags.
        toolsReturning("label-list.json").listLabels("DemianLi", "project_mcp", null, "   ");

        assertThat(argv()).doesNotContain("--search");
        assertThat(argv()).containsSequence("--sort", "name");
    }

    @Test
    void oneSpareIsAskedForSoTruncatedMeansMoreExist() throws Exception {
        // The fixture is a real `--limit 6` capture: six rows for a limit of five.
        CallToolResult result = toolsReturning("label-list-with-spare.json")
                .listLabels("DemianLi", "project_mcp", 5, null);

        assertThat(argv())
                .as("gh is asked for limit + 1")
                .containsSequence("--limit", "6");
        assertThat(text(result))
                .contains("\"count\":5")
                .contains("\"truncated\":true")
                .as("the spare is dropped, not reported")
                .doesNotContain("\"good first issue\"");
    }

    @Test
    void anAbsentLimitDefaultsAndAnAbsurdOneIsClamped() throws Exception {
        toolsReturning("label-list.json").listLabels("DemianLi", "project_mcp", null, null);
        assertThat(argv()).as("default 30, plus the spare").containsSequence("--limit", "31");

        toolsReturning("label-list.json").listLabels("DemianLi", "project_mcp", 5000, null);
        assertThat(argv()).as("capped at 100, plus the spare").containsSequence("--limit", "101");

        toolsReturning("label-list.json").listLabels("DemianLi", "project_mcp", 0, null);
        assertThat(argv())
                .as("clamped up rather than passed on -- gh rejects --limit 0 outright")
                .containsSequence("--limit", "2");
    }

    @Test
    void aLabelWithoutADescriptionKeepsTheKey() throws Exception {
        // Empty descriptions stay in the response; omitting them would be ambiguous.
        CallToolResult result = toolsReturning("label-list-empty-description.json")
                .listLabels("cli", "cli", 30, "blocked");

        assertThat(text(result)).contains("{\"name\":\"blocked\",\"description\":\"\"}");
    }

    @Test
    void aGhFailureTravelsTheOrdinaryWay() throws Exception {
        // Failures are classified to Remedies by the standard path.
        var tools = new LabelTools(
                new GhCli(FakeGh.failing(tmp, "GraphQL: Could not resolve to a Repository "
                        + "with the name 'DemianLi/nope'. (repository)"), 30),
                new LabelMapper());

        CallToolResult result = tools.listLabels("DemianLi", "nope", null, null);

        assertThat(result.isError()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> structured = (Map<String, Object>) result.structuredContent();
        assertThat(structured).containsEntry("remedy", "FIX_REQUEST");
    }
}

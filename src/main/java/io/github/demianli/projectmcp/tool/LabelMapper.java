package io.github.demianli.projectmcp.tool;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Maps {@code gh label list} JSON output to the response envelope.
 *
 * <p>Testable as a pure function against captured payloads.
 */
@Component
public class LabelMapper {

    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * @param ghJson the array {@code gh label list --json name,description} printed, fetched
     *     with one spare entry so truncation can be detected
     * @param limit the effective, already-clamped limit the Client is owed
     */
    public ListResult<LabelSummary> toEnvelope(String ghJson, int limit) {
        JsonNode root = json.readTree(ghJson);
        List<LabelSummary> labels = new ArrayList<>();
        for (JsonNode label : root) {
            labels.add(new LabelSummary(
                    label.path("name").asString(""),
                    label.path("description").asString("")));
        }
        return ListResult.of(labels, limit);
    }
}

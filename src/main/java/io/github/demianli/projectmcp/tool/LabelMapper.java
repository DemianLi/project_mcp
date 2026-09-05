package io.github.demianli.projectmcp.tool;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns what {@code gh label list --json} prints into the Envelope {@code list_labels}
 * returns.
 *
 * <p>Apart from {@link IssueMapper} rather than folded into it: the two share no field and
 * no shape, and a single class named for neither domain would be a home for the next
 * unrelated mapping too. What they do share is the property that earns them their own
 * classes — each is a pure function of a string, exercisable against a captured payload
 * with no subprocess, no network call and no GitHub account.
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

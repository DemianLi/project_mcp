package io.github.demianli.projectmcp.tool;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 把 {@code gh label list} 的 JSON 輸出對應成回應 Envelope。
 *
 * <p>是純函式，可直接用擷取下來的回應測試。
 */
@Component
public class LabelMapper {

    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * @param ghJson {@code gh label list --json name,description} 印出的陣列，多要了一筆
     *     以便偵測截斷
     * @param limit 應回給 Client 的有效上限，已限制在範圍內
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

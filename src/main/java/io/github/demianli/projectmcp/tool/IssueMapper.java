package io.github.demianli.projectmcp.tool;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 把 {@code gh} 的 issue JSON 對應成 issue 相關的結果型別。
 *
 * <p>是純函式，可直接用擷取下來的回應測試。語意驗證（例如拒絕 pull request）在 Tool
 * 層進行，不在這裡。
 */
@Component
public class IssueMapper {

    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * @param ghJson {@code gh issue list --json ...} 印出的陣列，多要了一筆以便偵測
     *     截斷
     * @param limit 應回給 Client 的有效上限，已限制在範圍內
     */
    public ListResult<IssueSummary> toEnvelope(String ghJson, int limit) {
        JsonNode root = json.readTree(ghJson);
        List<IssueSummary> issues = new ArrayList<>();
        for (JsonNode issue : root) {
            issues.add(new IssueSummary(
                    issue.path("number").asInt(),
                    issue.path("title").asString(""),
                    issue.path("state").asString(""),
                    flatten(issue.path("labels"), "name"),
                    flatten(issue.path("assignees"), "login"),
                    issue.path("url").asString(""),
                    issue.path("updatedAt").asString("")));
        }
        return ListResult.of(issues, limit);
    }

    /** 把 issue view 的 JSON 對應成 {@code IssueDetail}。 */
    public IssueDetail toDetail(String ghJson) {
        JsonNode issue = json.readTree(ghJson);
        return new IssueDetail(
                issue.path("number").asInt(),
                issue.path("title").asString(""),
                issue.path("state").asString(""),
                flatten(issue.path("labels"), "name"),
                flatten(issue.path("assignees"), "login"),
                issue.path("url").asString(""),
                issue.path("updatedAt").asString(""),
                issue.path("body").asString(""),
                issue.path("author").path("login").asString(""),
                issue.path("createdAt").asString(""),
                issue.path("closedAt").asString(null),
                issue.path("stateReason").asString(""));
    }

    /** 把物件陣列縮減成值得保留的單一欄位。 */
    private static List<String> flatten(JsonNode array, String field) {
        List<String> values = new ArrayList<>();
        for (JsonNode element : array) {
            values.add(element.path(field).asString(""));
        }
        return List.copyOf(values);
    }
}

package io.github.demianli.projectmcp.tool;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 把 GraphQL 回應對應成留言相關的結果型別。
 *
 * <p>是純函式，可直接用擷取下來的回應測試。
 */
@Component
public class CommentMapper {

    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * @param ghJson 以 {@code data} 為根的 GraphQL 回應
     * @param issue 交給 {@link Cursors}，為 cursor 標註所屬的 issue
     */
    public CommentPage toPage(String ghJson, IssueRef issue) {
        JsonNode comments = json.readTree(ghJson)
                .path("data").path("repository").path("issue").path("comments");

        List<Comment> items = new ArrayList<>();
        for (JsonNode node : comments.path("nodes")) {
            items.add(new Comment(
                    node.path("author").path("login").asString(""),
                    node.path("authorAssociation").asString(""),
                    node.path("createdAt").asString(""),
                    node.path("body").asString(""),
                    node.path("url").asString("")));
        }

        JsonNode pageInfo = comments.path("pageInfo");
        boolean more = pageInfo.path("hasPreviousPage").asBoolean(false);
        String nextCursor = more
                ? Cursors.wrap(issue, pageInfo.path("startCursor").asString(null))
                : null;

        return new CommentPage(List.copyOf(items), items.size(), more,
                comments.path("totalCount").asInt(0), nextCursor);
    }

    /** 從查詢回應取出 issue 的 node id。 */
    public String toIssueId(String ghJson) {
        return json.readTree(ghJson)
                .path("data").path("repository").path("issue").path("id").asString("");
    }

    /** 從 {@code addComment} 的回應讀出新留言的永久連結。 */
    public NewComment toNewComment(String ghJson) {
        return new NewComment(json.readTree(ghJson)
                .path("data").path("addComment").path("commentEdge").path("node").path("url")
                .asString(""));
    }
}

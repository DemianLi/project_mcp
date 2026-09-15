package io.github.demianli.projectmcp.tool;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Maps GraphQL responses to comment result types.
 *
 * <p>Testable as a pure function against captured payloads.
 */
@Component
public class CommentMapper {

    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * @param ghJson GraphQL response rooted at {@code data}
     * @param issue passed to {@link Cursors} for cursor naming
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

    /** Extracts the issue's node id from the lookup response. */
    public String toIssueId(String ghJson) {
        return json.readTree(ghJson)
                .path("data").path("repository").path("issue").path("id").asString("");
    }

    /** Reads the new comment's permalink out of what {@code addComment} answered. */
    public NewComment toNewComment(String ghJson) {
        return new NewComment(json.readTree(ghJson)
                .path("data").path("addComment").path("commentEdge").path("node").path("url")
                .asString(""));
    }
}

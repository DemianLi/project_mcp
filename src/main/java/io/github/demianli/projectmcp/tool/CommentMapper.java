package io.github.demianli.projectmcp.tool;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns what {@code gh api graphql} prints into the Envelope {@code list_issue_comments}
 * returns.
 *
 * <p>Its own class for the reason {@link LabelMapper} gives: a pure function of its
 * arguments, exercisable against a captured payload with no subprocess, no network call and
 * no GitHub account. The payload it reads is nested rather than a top-level array — this is
 * the first Tool that does not read porcelain, so the shape has nothing in common with the
 * other two mappers.
 */
@Component
public class CommentMapper {

    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * @param ghJson what {@code gh api graphql} printed, rooted at {@code data}
     * @param owner the repository owner this response is about
     * @param repo the repository name this response is about
     * @param number the issue this response is about
     *
     * <p>The last three are here only so {@link Cursors} can name the issue inside the
     * cursor this method emits. They keep it a pure function — three more arguments, no
     * more I/O.
     */
    public CommentPage toPage(String ghJson, String owner, String repo, int number) {
        JsonNode comments = json.readTree(ghJson)
                .path("data").path("repository").path("issue").path("comments");

        List<Comment> items = new ArrayList<>();
        for (JsonNode node : comments.path("nodes")) {
            items.add(new Comment(
                    // A deleted account leaves `author` null rather than absent, and
                    // path().path() reaches the same missing node either way.
                    node.path("author").path("login").asString(""),
                    node.path("authorAssociation").asString(""),
                    node.path("createdAt").asString(""),
                    node.path("body").asString(""),
                    node.path("url").asString("")));
        }

        // hasPreviousPage is the sole discriminator, and startCursor must not be used as
        // one. On a terminal page that is not empty they disagree: the last 43 comments of
        // cli/cli#13840 come back with hasPreviousPage false and a startCursor pointing at
        // the oldest comment. Keying off the cursor's presence would hand a Client a marker
        // whose next response is empty.
        JsonNode pageInfo = comments.path("pageInfo");
        boolean more = pageInfo.path("hasPreviousPage").asBoolean(false);
        String nextCursor = more
                ? Cursors.wrap(owner, repo, number, pageInfo.path("startCursor").asString(null))
                : null;

        return new CommentPage(List.copyOf(items), items.size(), more,
                comments.path("totalCount").asInt(0), nextCursor);
    }
}

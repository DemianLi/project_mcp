package io.github.demianli.projectmcp.tool;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns what {@code gh api graphql} prints into what the comment Tools return.
 *
 * <p>Its own class for the reason {@link LabelMapper} gives: a pure function of its
 * arguments, exercisable against a captured payload with no subprocess, no network call and
 * no GitHub account. The payloads it reads are nested rather than top-level arrays — the
 * comment Tools are the ones that do not read porcelain, so these shapes have nothing in
 * common with the other two mappers.
 *
 * <p>Three methods for two Tools, because the write is two calls: {@link #toPage} for the
 * read, and {@link #toIssueId} then {@link #toNewComment} for the lookup and the mutation
 * that {@code add_issue_comment} is made of.
 */
@Component
public class CommentMapper {

    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * @param ghJson what {@code gh api graphql} printed, rooted at {@code data}
     * @param issue the issue this response is about
     *
     * <p>{@code issue} is here only so {@link Cursors} can name the issue inside the cursor
     * this method emits; nothing is read off it here. It keeps this a pure function — one
     * more argument, no more I/O — and it is one argument rather than the three it used to
     * be, which is the whole of what {@link IssueRef} is for.
     */
    public CommentPage toPage(String ghJson, IssueRef issue) {
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
                ? Cursors.wrap(issue, pageInfo.path("startCursor").asString(null))
                : null;

        return new CommentPage(List.copyOf(items), items.size(), more,
                comments.path("totalCount").asInt(0), nextCursor);
    }

    /**
     * Reads the issue's node id out of the lookup {@code add_issue_comment} makes first.
     *
     * <p>Nothing is rejected here, and nothing needs to be: the pull-request guard is the
     * query itself, and it lives with the query on {@code CommentTools.ISSUE_ID}. A pull
     * request number never produces a payload for this method to read — {@code gh} exits 1
     * on the call before it.
     *
     * @return the id, or the empty string if the payload somehow has none — which
     *     {@code gh} exiting zero should make unreachable, and which
     *     {@link CommentTools#addIssueComment} deliberately does not test for
     */
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

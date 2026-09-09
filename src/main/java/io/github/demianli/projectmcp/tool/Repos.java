package io.github.demianli.projectmcp.tool;

import io.github.demianli.projectmcp.gh.Remedy;
import io.github.demianli.projectmcp.gh.ToolFailure;

/**
 * The one rule about {@code owner} and {@code repo}, shared by every Tool.
 *
 * <p>Neither may contain a slash, and the reason is not tidiness. {@code gh}'s
 * {@code --repo} takes <strong>{@code [HOST/]OWNER/REPO}</strong>, so a slash inside
 * {@code owner} promotes its first segment to a hostname: measured through this Server's own
 * Tool interface, {@code owner} of {@code 127.0.0.1:8099/a} with {@code repo} of {@code b}
 * sent it to {@code https://127.0.0.1:8099/api/graphql}. The caller chooses where this
 * Server makes its next request, and in the deployment shape this Server is built for that
 * caller is a model that has just read someone else's issue text. See issue #37.
 *
 * <p>The failure it produced was the other half of the problem. {@code gh}'s
 * {@code dial tcp ... connection refused} matches {@code GhStderr}'s network row, so the
 * Client was told {@code RETRY}, "the network looks unavailable" — a <em>confident wrong
 * Remedy</em>, which ADR-0002 puts in its worst category. The network was fine; the address
 * was not. Retrying can only send the same request to the same host again.
 *
 * <p><strong>Checked on every route, including the ones that were already safe.</strong> Only
 * the porcelain Tools compose {@code --repo}; {@code list_issue_comments} and
 * {@code add_issue_comment} pass {@code owner} and {@code repo} as separate GraphQL
 * variables, where a slash is inert and GitHub answers "Could not resolve to a Repository"
 * on its own. Refusing there too costs a check that changes nothing and buys the property
 * that matters more: <em>the same bad parameter gets the same answer whichever Tool receives
 * it</em>. Which route a Tool takes to GitHub is this Server's business (ADR-0005,
 * ADR-0010), and it should not be visible in what a Client is told about its own typo.
 *
 * <p><strong>Only the slash.</strong> An empty half or a trailing one is already refused with
 * the same Remedy, by {@code gh} itself: {@code --repo /c} and {@code --repo a/} both answer
 * {@code expected the "[HOST/]OWNER/REPO" format}, which {@code GhStderr}'s malformed-name
 * row turns into {@code FIX_REQUEST} — measured, and pinned by
 * {@code docs/measurements/gh-compatibility.sh}. A second guard over those would be this
 * Server re-answering a question GitHub's own CLI answers correctly.
 *
 * <p>The fifth failure this Server invents rather than inherits, after a pull request
 * number, a cursor from the wrong issue, a blank comment body and a response over the
 * ceiling.
 */
final class Repos {

    private Repos() {
    }

    /**
     * The {@code owner/repo} {@code gh}'s {@code --repo} takes, once both halves are safe.
     *
     * <p>Composing here rather than at the call sites is the point: this is the only place
     * that knows the string is going somewhere a slash would change the meaning of, and a
     * Tool that composed its own would be a Tool that could forget.
     *
     * @throws ToolFailure if either half contains a slash
     */
    static String slug(String owner, String repo) {
        check(owner, repo);
        return owner + "/" + repo;
    }

    /**
     * The same rule for the routes that never compose a slug.
     *
     * <p>Nothing is returned, because nothing needs to be: the GraphQL Tools pass the two
     * halves as separate variables and only need to know that they are allowed to.
     */
    static void check(String owner, String repo) {
        refuseSlash("owner", owner);
        refuseSlash("repo", repo);
    }

    private static void refuseSlash(String parameter, String value) {
        if (value != null && value.indexOf('/') >= 0) {
            throw new ToolFailure(Remedy.FIX_REQUEST,
                    "`" + parameter + "` must not contain a slash. Pass the owner and the "
                            + "repository as two separate parameters — `owner` is the user or "
                            + "organisation alone, `repo` the repository name alone. A slash "
                            + "here would be read as a host name, and this Server would ask "
                            + "somewhere other than GitHub.",
                    "", null);
        }
    }
}

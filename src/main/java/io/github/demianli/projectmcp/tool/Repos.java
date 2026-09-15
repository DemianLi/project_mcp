package io.github.demianli.projectmcp.tool;

import io.github.demianli.projectmcp.gh.Remedy;
import io.github.demianli.projectmcp.gh.ToolFailure;

/**
 * Validation rule shared by all Tools: owner and repo must not contain slashes.
 *
 * <p>A slash in owner would be read as a hostname by {@code gh --repo [HOST/]OWNER/REPO},
 * routing to a different host. Checked on every Tool so the same bad parameter gets the
 * same answer regardless of which Tool receives it. See design.md#identity-and-permissions.
 */
final class Repos {

    private Repos() {
    }

    /**
     * Composes {@code owner/repo} after validation.
     *
     * @throws ToolFailure if either half contains a slash
     */
    static String slug(String owner, String repo) {
        check(owner, repo);
        return owner + "/" + repo;
    }

    /** Validates owner and repo without composing the slug. */
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

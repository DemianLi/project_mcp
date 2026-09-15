package io.github.demianli.projectmcp.tool;

import io.github.demianli.projectmcp.gh.Remedy;
import io.github.demianli.projectmcp.gh.ToolFailure;

/**
 * 所有 Tools 共用的驗證規則：owner 與 repo 不得含斜線。
 *
 * <p>owner 中的斜線會被 {@code gh --repo [HOST/]OWNER/REPO} 當成主機名稱，把請求送到
 * 別的主機。每個 Tool 都做這項檢查，同樣的錯誤參數無論送到哪個 Tool 都得到同樣的回應。
 * 見 design.md#identity-and-permissions。
 */
final class Repos {

    private Repos() {
    }

    /**
     * 驗證後組出 {@code owner/repo}。
     *
     * @throws ToolFailure 任一半含有斜線時
     */
    static String slug(String owner, String repo) {
        check(owner, repo);
        return owner + "/" + repo;
    }

    /** 驗證 owner 與 repo，但不組出 slug。 */
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

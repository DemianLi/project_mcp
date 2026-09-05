package io.github.demianli.projectmcp.prototype;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.demianli.projectmcp.gh.GhCli;
import io.github.demianli.projectmcp.gh.ToolFailure;

import org.junit.jupiter.api.Test;

/**
 * PROTOTYPE — THROWAWAY. Not a test: it asserts nothing and it talks to the network.
 *
 * <p>Answers issue #21 — which of three {@code gh} routes {@code list_issue_comments} should
 * take. Run it with:
 *
 * <pre>mvn test -Dtest=CommentsRouteProbe -DfailIfNoTests=false</pre>
 *
 * <p>It drives the <em>real</em> {@link GhCli}, so the Remedy column is what a Client would
 * actually be told today — not a reading of {@code classify()}.
 *
 * <p>{@code owner} and {@code repo} are separate here because they are separate on every
 * Tool in this Server. An earlier draft joined them into one slug and produced a "malformed
 * slug" row that no Tool can ever reach.
 *
 * <p>Delete this file once #21 is resolved; the branch keeps it as the primary source.
 */
class CommentsRouteProbe {

    private static final int LIMIT = 30;

    private interface Argv {
        List<String> of(String owner, String repo, int number);
    }

    private record Route(String name, Argv argv) {}

    private static final String GRAPHQL = """
            query($owner:String!,$name:String!,$number:Int!,$first:Int!){
              repository(owner:$owner,name:$name){
                issue(number:$number){
                  url
                  comments(first:$first){
                    totalCount
                    pageInfo{hasNextPage endCursor}
                    nodes{author{login} createdAt body url}
                  }
                }
              }
            }""";

    private static final List<Route> ROUTES = List.of(
            new Route("A porcelain", (owner, repo, n) -> List.of(
                    "issue", "view", String.valueOf(n),
                    "--repo", owner + "/" + repo, "--json", "comments,url")),
            new Route("B rest", (owner, repo, n) -> List.of(
                    "api", "repos/" + owner + "/" + repo + "/issues/" + n
                            + "/comments?per_page=" + LIMIT)),
            new Route("C graphql", (owner, repo, n) -> List.of(
                    "api", "graphql",
                    "-f", "query=" + GRAPHQL,
                    "-F", "owner=" + owner,
                    "-F", "name=" + repo,
                    "-F", "number=" + n,
                    "-F", "first=" + LIMIT)));

    private record Scenario(String label, String owner, String repo, int number) {}

    private static final List<Scenario> SCENARIOS = List.of(
            new Scenario("issue, 143 comments", "cli", "cli", 13840),
            new Scenario("issue, 8 comments", "cli", "cli", 14256),
            new Scenario("issue, 1 comment", "cli", "cli", 14361),
            new Scenario("PR, 3 comments", "cli", "cli", 14362),
            new Scenario("PR, 0 comments", "cli", "cli", 13841),
            new Scenario("no such issue", "cli", "cli", 99999999),
            new Scenario("no such repo", "cli", "nonexistent-xyzzy", 1),
            new Scenario("empty repo", "cli", "", 1));

    /** The issue's own url — NOT any url inside a comment body. */
    private static final Pattern OWN_URL =
            Pattern.compile("\"url\"\\s*:\\s*\"(https://github\\.com/[^\"]+?/(issues|pull)/\\d+)\"");
    private static final Pattern FIRST_COMMENT_HTML_URL =
            Pattern.compile("\"html_url\"\\s*:\\s*\"(https://github\\.com/[^\"]+?/(issues|pull)/\\d+)#");
    private static final Pattern TOTAL = Pattern.compile("\"totalCount\"\\s*:\\s*(\\d+)");
    private static final Pattern HAS_NEXT = Pattern.compile("\"hasNextPage\"\\s*:\\s*(true|false)");

    @Test
    void probe() {
        GhCli gh = new GhCli();
        System.out.println();
        System.out.printf("%-21s  %-11s  %8s  %6s  %-12s  %-11s  %s%n",
                "scenario", "route", "bytes", "ms", "remedy", "PR tell", "note");
        System.out.println("-".repeat(125));

        for (Scenario s : SCENARIOS) {
            for (Route r : ROUTES) {
                List<String> argv = r.argv().of(s.owner(), s.repo(), s.number());
                long t0 = System.nanoTime();
                try {
                    String out = gh.run(argv);
                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    System.out.printf("%-21s  %-11s  %,8d  %,6d  %-12s  %-11s  %s%n",
                            s.label(), r.name(), out.length(), ms, "ok", prTell(out),
                            r.name().startsWith("C") ? extras(out) : "—");
                } catch (ToolFailure f) {
                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    System.out.printf("%-21s  %-11s  %8s  %,6d  %-12s  %-11s  %s%n",
                            s.label(), r.name(), "-", ms, f.remedy(), "n/a",
                            oneLine(f.stderr()));
                }
            }
            System.out.println();
        }
    }

    /** Can this payload, on its own, tell a caller the number was a pull request? */
    private static String prTell(String out) {
        String flat = oneLine(out);
        if (flat.contains("\"issue\":null") || flat.contains("\"issue\": null")) {
            return "issue:null";
        }
        Matcher own = OWN_URL.matcher(flat);
        if (own.find()) {
            return own.group(2).equals("pull") ? "url=/pull/" : "url=/issues/";
        }
        Matcher c = FIRST_COMMENT_HTML_URL.matcher(flat);
        if (c.find()) {
            return c.group(2).equals("pull") ? "html_url" : "html_url ok";
        }
        return "NONE";
    }

    /**
     * Whatever the route volunteers about paging. Route C only: an earlier draft ran this
     * over every route and matched a {@code totalCount} that lived inside a comment's body
     * text, reporting a field the porcelain route does not have.
     */
    private static String extras(String out) {
        String flat = oneLine(out);
        Matcher t = TOTAL.matcher(flat);
        Matcher h = HAS_NEXT.matcher(flat);
        String total = t.find() ? "totalCount=" + t.group(1) : "no totalCount";
        String next = h.find() ? " hasNextPage=" + h.group(1) : "";
        boolean cursor = flat.contains("\"endCursor\"");
        return total + next + (cursor ? " +endCursor" : "");
    }

    private static String oneLine(String s) {
        return s.replaceAll("\\s+", " ").strip();
    }
}

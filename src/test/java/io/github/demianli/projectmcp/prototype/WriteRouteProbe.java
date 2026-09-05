package io.github.demianli.projectmcp.prototype;

import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.demianli.projectmcp.gh.GhCli;
import io.github.demianli.projectmcp.gh.ToolFailure;

import org.junit.jupiter.api.Test;

/**
 * PROTOTYPE — THROWAWAY. Not a test: it asserts nothing, it talks to the network, and it
 * leaves comments behind on real issues.
 *
 * <p>Answers issue #27 — what the two write routes cost and how they fail. Run it with:
 *
 * <pre>mvn test -Dtest=WriteRouteProbe -DfailIfNoTests=false</pre>
 *
 * <p>Like {@code CommentsRouteProbe} before it, this drives the <em>real</em>
 * {@link GhCli}, so the Remedy column is what a Client would actually be told today — not
 * a reading of {@code classify()}.
 *
 * <p><b>Every side effect lands in {@code DemianLi/project-mcp-sandbox}.</b> The one
 * scenario that points elsewhere is the archived repository, and a write there cannot
 * succeed by construction — which is the whole reason it was picked. See the note on
 * {@code ARCHIVED} below: the ticket asked for "a public repo you are not a collaborator
 * on", and that turns out not to be a write-permission failure at all.
 *
 * <p>Delete this file once #27 is resolved; the branch keeps it as the primary source.
 */
class WriteRouteProbe {

    private static final String OWNER = "DemianLi";
    private static final String SANDBOX = "project-mcp-sandbox";

    /** The five fields ADR-0006 keeps for a Comment on the read side. */
    private static final String FIELDS = "author{login} authorAssociation createdAt body url";

    private static final String NODE_ID_QUERY = """
            query($owner:String!,$name:String!,$number:Int!){
              repository(owner:$owner,name:$name){ issue(number:$number){ id } }
            }""";

    private static final String ADD_COMMENT = """
            mutation($subjectId:ID!,$body:String!){
              addComment(input:{subjectId:$subjectId, body:$body}){
                commentEdge{ node{ %s } }
              }
            }""".formatted(FIELDS);

    private interface Write {
        /** Runs the route; returns one line describing what came back. */
        String run(GhCli gh, String owner, String repo, int number, String body);
    }

    private record Route(String name, Write write) {}

    /** Calls made, and bytes/ms of the whole route — a route may be more than one call. */
    private static int calls;
    private static int bytes;

    private static String porcelain(GhCli gh, String owner, String repo, int n, String body) {
        calls = 1;
        String out = gh.run(List.of("issue", "comment", String.valueOf(n),
                "--repo", owner + "/" + repo, "--body", body));
        bytes = out.length();
        return out.strip();
    }

    private static String graphql(GhCli gh, String owner, String repo, int n, String body) {
        calls = 1;
        String idOut = gh.run(List.of("api", "graphql",
                "-f", "query=" + NODE_ID_QUERY,
                "-F", "owner=" + owner, "-F", "name=" + repo, "-F", "number=" + n));
        Matcher m = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"").matcher(idOut);
        if (!m.find()) {
            bytes = idOut.length();
            return "call 1 gave no node id: " + oneLine(idOut);
        }
        calls = 2;
        String out = gh.run(List.of("api", "graphql",
                "-f", "query=" + ADD_COMMENT,
                "-F", "subjectId=" + m.group(1), "-F", "body=" + body));
        bytes = idOut.length() + out.length();
        return oneLine(out);
    }

    private static String rest(GhCli gh, String owner, String repo, int n, String body) {
        calls = 1;
        String out = gh.run(List.of("api", "--method", "POST",
                "repos/" + owner + "/" + repo + "/issues/" + n + "/comments",
                "-f", "body=" + body));
        bytes = out.length();
        return oneLine(out);
    }

    private static final List<Route> ROUTES = List.of(
            new Route("P porcelain", WriteRouteProbe::porcelain),
            new Route("G graphql", WriteRouteProbe::graphql),
            new Route("R rest", WriteRouteProbe::rest));

    private record Scenario(String label, String owner, String repo, int number) {}

    /**
     * An archived repository, NOT "a repo I am not a collaborator on".
     *
     * <p>The ticket asked for the latter, and it does not measure what it was meant to:
     * commenting on a public repository's issue is open to any authenticated user, so that
     * call would have <em>succeeded</em> and left a comment on a stranger's repo. An
     * archived repository is read-only for everyone including its owner, so the rejection
     * is guaranteed and no side effect is possible.
     */
    private static final Scenario ARCHIVED =
            new Scenario("archived repo", "facebookarchive", "draft-js", 1);

    private static final List<Scenario> SCENARIOS = List.of(
            new Scenario("issue (writes)", OWNER, SANDBOX, 1),
            new Scenario("PR number (writes?)", OWNER, SANDBOX, 3),
            new Scenario("no such issue", OWNER, SANDBOX, 99999999),
            new Scenario("no such repo", OWNER, "nonexistent-xyzzy", 1),
            new Scenario("empty repo", OWNER, "", 1),
            ARCHIVED);

    @Test
    void probe() {
        GhCli gh = new GhCli();
        String stamp = Instant.now().toString();

        System.out.println();
        System.out.printf("%-20s  %-12s  %5s  %7s  %6s  %-12s  %s%n",
                "scenario", "route", "calls", "bytes", "ms", "remedy", "what came back");
        System.out.println("-".repeat(150));

        for (Scenario s : SCENARIOS) {
            for (Route r : ROUTES) {
                String body = "probe #27 " + r.name() + " " + stamp;
                calls = 0;
                bytes = 0;
                long t0 = System.nanoTime();
                try {
                    String what = r.write().run(gh, s.owner(), s.repo(), s.number(), body);
                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    System.out.printf("%-20s  %-12s  %5d  %,7d  %,6d  %-12s  %s%n",
                            s.label(), r.name(), calls, bytes, ms, "ok", clip(what));
                } catch (ToolFailure f) {
                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    System.out.printf("%-20s  %-12s  %5d  %7s  %,6d  %-12s  %s%n",
                            s.label(), r.name(), calls, "-", ms, f.remedy(),
                            clip(oneLine(f.stderr())));
                }
            }
            System.out.println();
        }
    }

    private static String clip(String s) {
        return s.length() <= 88 ? s : s.substring(0, 88) + "…";
    }

    private static String oneLine(String s) {
        return s.replaceAll("\\s+", " ").strip();
    }
}

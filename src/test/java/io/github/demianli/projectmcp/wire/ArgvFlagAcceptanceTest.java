package io.github.demianli.projectmcp.wire;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Acceptance layer: every GraphQL variable leaves with the flag its declared type requires.
 *
 * <p><strong>What {@code -F} actually does.</strong> {@code gh api}'s two field flags are not
 * spellings of one thing. {@code -f} sends the value as a string, literally. {@code -F} has
 * three magic readings of it, all three measured on this route: {@code 123} and {@code true}
 * become JSON scalars; {@code {owner}}, {@code {repo}} and {@code {branch}} are replaced with
 * whatever repository the Server's working directory resolves to; and {@code @path} or
 * {@code @-} reads the value out of a local file or stdin and sends <em>that</em>. So
 * {@code -F body=} on {@code add_issue_comment} would let a Client name a file on this
 * machine and have its contents posted to GitHub, over a Tool whose annotations say it
 * writes a comment.
 *
 * <p><strong>What was already watched, and what was not.</strong> Not the whole rule — the
 * gap was uneven and measuring it is what shaped this test. {@code AddIssueCommentTest} pins
 * every flag on both of that Tool's argv by hand, down to
 * {@code assertThat(argv(2)).doesNotContain("-F")}, so flipping {@code body} to {@code -F}
 * was red before this file existed. {@code list_issue_comments} was watched on {@code cursor}
 * alone; {@code owner}, {@code name}, {@code number} and {@code last} had no assertion
 * anywhere. Flipping {@code -f owner=} there — the mutation that lets a Client's
 * {@code {owner}} be replaced by this machine's repository — left all 75 other tests green.
 * That is the shape of the thing being fixed: not an unguarded rule, a rule guarded Tool by
 * Tool, by hand, by whoever remembered.
 *
 * <p><strong>The rule is two-sided, and it is not "avoid {@code -F}".</strong>
 * {@code -F number=} and {@code -F last=} are correct: their values are Java {@code int}s and
 * can carry none of the three readings, and sending them with {@code -f} would hand a JSON
 * string to an {@code Int!}. So the check runs both ways — {@code Int}, {@code Float} and
 * {@code Boolean} must be {@code -F}; every other scalar must be {@code -f}. {@code ID} sits
 * on the {@code -f} side deliberately: GraphQL's {@code ID} takes a string and serialises as
 * one, so {@code subjectId} is right as it stands and a naive "not a String means
 * {@code -F}" would turn that call site red.
 *
 * <p><strong>Both halves are read off one argv.</strong> The types are not written down
 * here. Every document declares them — {@code query($owner:String!, …, $number:Int!, …)} —
 * and the document itself travels in the argv as {@code -f query=…}, so a captured call
 * carries what was sent <em>and</em> what it should have been sent as. This test keeps no
 * table of variable names, exactly as {@link WritePartitionAcceptanceTest} keeps no list of
 * write Tools: a variable added to a document with the wrong flag goes red without anyone
 * touching this file.
 *
 * <p>A typed argv would make the mistake unrepresentable rather than merely un-shippable, and
 * that is the better answer at two adapters. ADR-0010 records why it waits for one: today
 * {@code gh api graphql} is three call sites in a single class.
 *
 * <p>Declarations are checked against the pairs that appear, never the reverse. {@code $before}
 * is legitimately absent on a first call, and demanding every declared variable be sent would
 * fail the ordinary case.
 *
 * <p><strong>Why here, when a Client cannot see an argv.</strong> Placement follows the
 * enumeration, not the visibility. "Every Tool" exists in exactly one place —
 * {@code listTools()} — and that is above the wire. Below it this test would be reflecting
 * over {@code @McpTool} or hand-listing the three call sites in {@code CommentTools}, which
 * is the "covered by being remembered" it exists to remove. The argv is reachable from up
 * here anyway: the stand-in {@code gh} writes it down.
 *
 * <p>Cheap: one Server, one stand-in that answers immediately, one call per Tool. Nothing
 * here touches {@code GhCli}'s timeout.
 */
class ArgvFlagAcceptanceTest {

    @TempDir Path tmp;

    /** Scalars whose JSON form is not a string, and which therefore need {@code -F}. */
    private static final Set<String> SENT_TYPED = Set.of("Int", "Float", "Boolean");

    /** {@code $name:Type} in an operation's parameter list. Usages carry no {@code :Type}. */
    private static final Pattern DECLARATION = Pattern.compile("\\$(\\w+)\\s*:\\s*(\\w+)");

    /** Recognises {@code call<n>.arg<i>}, the one element of one call. */
    private static final Pattern CAPTURED = Pattern.compile("call(\\d+)\\.arg(\\d+)");

    /**
     * A {@code gh} that writes down every argv element and then answers.
     *
     * <p>One file per element, and no separator anywhere. A GraphQL document is several
     * lines long, so the {@code printf '%s\n' "$@"} the Coverage layer uses would split one
     * argv element across lines and there would be no way to put it back — and a
     * NUL-separated capture would rest on {@code /bin/sh} being the same shell here and on
     * CI, which it is not.
     *
     * <p>It answers rather than failing because {@code add_issue_comment} makes two calls and
     * only the second is the one with a body: a stand-in that failed the id lookup would
     * never let the mutation's argv be written at all, and this test would pass having
     * examined the wrong half of the Tool.
     */
    private String recordingGh(Path dir) throws IOException {
        Path calls = dir.resolve("calls");
        Path id = dir.resolve("issue-node-id.json");
        Path added = dir.resolve("add-comment.json");
        Files.copy(Path.of("src/test/resources/gh/issue-node-id.json"), id);
        Files.copy(Path.of("src/test/resources/gh/add-comment.json"), added);

        return """
                n=$(cat %s 2>/dev/null || echo 0)
                n=$((n+1)); echo $n > %s
                i=0
                for a in "$@"; do
                  i=$((i+1))
                  printf '%%s' "$a" > %s/call$n.arg$i
                done
                case "$*" in
                  *"{ id }"*) cat %s ;;
                  *addComment*) cat %s ;;
                  *) echo '{}' ;;
                esac""".formatted(calls, calls, dir, id, added);
    }

    /** Every call the stand-in recorded, in the order it recorded them. */
    private static List<List<String>> captured(Path dir) throws IOException {
        Map<Integer, Map<Integer, String>> calls = new TreeMap<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.toList()) {
                Matcher m = CAPTURED.matcher(file.getFileName().toString());
                if (m.matches()) {
                    calls.computeIfAbsent(Integer.valueOf(m.group(1)), k -> new TreeMap<>())
                            .put(Integer.valueOf(m.group(2)), Files.readString(file));
                }
            }
        }
        List<List<String>> argvs = new ArrayList<>();
        for (Map<Integer, String> call : calls.values()) {
            argvs.add(List.copyOf(call.values()));
        }
        return argvs;
    }

    /** {@code name -> declared type}, read out of the operation's parameter list. */
    private static Map<String, String> declarationsIn(String document) {
        String header = document.substring(document.indexOf('('), document.indexOf('{'));
        Map<String, String> declared = new TreeMap<>();
        Matcher m = DECLARATION.matcher(header);
        while (m.find()) {
            declared.put(m.group(1), m.group(2));
        }
        return declared;
    }

    /**
     * Checks one {@code api graphql} argv against the document it carries.
     *
     * <p>Everything from index two on is a flag and its {@code key=value}, so the pairs are
     * walked rather than searched for — a value that happened to read {@code -f} could
     * otherwise shift the whole scan by one.
     */
    private static void checkFlags(List<String> argv) {
        assertThat((argv.size() - 2) % 2)
                .as("`%s` is not `api graphql` followed by flag/value pairs", argv)
                .isZero();

        String document = null;
        for (int i = 2; i < argv.size(); i += 2) {
            if (argv.get(i + 1).startsWith("query=")) {
                document = argv.get(i + 1).substring("query=".length());
            }
        }
        assertThat(document).as("`%s` carries no GraphQL document to read types off", argv)
                .isNotNull();

        Map<String, String> declared = declarationsIn(document);

        for (int i = 2; i < argv.size(); i += 2) {
            String flag = argv.get(i);
            String pair = argv.get(i + 1);

            assertThat(flag).as("`%s` is not a field flag", flag).isIn("-f", "-F");
            assertThat(pair).as("`%s` is not a key=value", pair).contains("=");

            String name = pair.substring(0, pair.indexOf('='));
            if (name.equals("query")) {
                continue;
            }

            String type = declared.get(name);
            assertThat(type)
                    .as("`%s` is sent, but the document declares no $%s -- the argv and the "
                            + "document have drifted apart", name, name)
                    .isNotNull();

            String expected = SENT_TYPED.contains(type) ? "-F" : "-f";
            assertThat(flag)
                    .as("$%s is declared %s, so it must be sent with %s. -f sends a string "
                            + "literally; -F reads `123` as a number, `{owner}` as this "
                            + "machine's repository, and `@path` as a file to send the "
                            + "contents of.", name, type, expected)
                    .isEqualTo(expected);
        }
    }

    @Test
    void everyGraphqlVariableIsSentWithTheFlagItsTypeRequires() throws Exception {
        List<Tool> tools;

        try (McpSyncClient client = LaunchedServer.withGh(tmp, recordingGh(tmp))) {
            tools = client.listTools().tools();

            assertThat(tools)
                    .as("an empty Tool list would make everything below pass without "
                            + "asserting anything")
                    .isNotEmpty();

            for (Tool tool : tools) {
                Map<String, Object> arguments = ToolCalls.forTool(tool.name());

                assertThat(arguments)
                        .as("`%s` is declared, so this test needs a call that reaches gh -- "
                                + "add it to ToolCalls", tool.name())
                        .isNotNull();

                // The result is deliberately not read. What a Tool answers is
                // WireAcceptanceTest's subject and FailureContractAcceptanceTest's; this
                // test's whole evidence is what the stand-in wrote down.
                client.callTool(new CallToolRequest(tool.name(), arguments));
            }
        }

        List<List<String>> calls = captured(tmp);

        // Not merely non-empty. Every Tool was driven and every Tool reaches gh at least
        // once, so a total below the Tool count means one of them was refused before the
        // subprocess -- and that Tool's argv is simply absent here rather than wrong. A
        // bare isNotEmpty() would let the other four cover for it.
        assertThat(calls)
                .as("%d Tools were driven, so at least that many gh calls should have been "
                        + "recorded -- a Tool stopped before gh contributes no argv and is "
                        + "silently exempt from everything below", tools.size())
                .hasSizeGreaterThanOrEqualTo(tools.size());

        int graphql = 0;
        for (List<String> argv : calls) {
            if (argv.size() >= 2 && argv.get(0).equals("api") && argv.get(1).equals("graphql")) {
                graphql++;
                checkFlags(argv);
            } else {
                // Porcelain. The type rule does not apply, but its absence is the assertion:
                // a Tool that starts passing fields without going through `api graphql` is
                // not exempt from the reason those flags are dangerous, and would otherwise
                // be skipped here rather than caught.
                assertThat(argv)
                        .as("`%s` is not an `api graphql` call, so it must carry no field "
                                + "flags -- a value sent with -F is read for `@path` and "
                                + "`{owner}` wherever it appears", argv)
                        .doesNotContain("-f", "-F");
            }
        }

        assertThat(graphql)
                .as("no `api graphql` call was recorded, so the type rule above was never "
                        + "applied to anything")
                .isNotZero();
    }
}

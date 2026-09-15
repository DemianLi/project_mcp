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
 * 驗證每個 GraphQL 變數都以其宣告型別要求的旗標送出。
 *
 * <p><strong>-F 的風險。</strong>{@code gh api} 的 -f 把值當字串原樣送出；{@code -F} 則有
 * 三種特殊解讀：123 與 true 變成 JSON 純量；{owner}、{repo}、{branch} 換成目前的
 * repository；@path 讀取檔案。因此 add_issue_comment 若用 {@code -F body=}，Client 就能
 * 指定本機上的檔案並把內容貼到 GitHub，與 Tool 的 annotations 所宣告的行為不符。
 *
 * <p><strong>規則是雙向的。</strong>{@code Int}、{@code Float}、{@code Boolean} 必須用
 * -F（以字串送出會違反 GraphQL 型別）；其他純量一律用 -f。{@code ID} 刻意用 -f，因為
 * GraphQL 的 ID 接受字串。
 *
 * <p><strong>檢查的兩端在同一處。</strong>型別宣告在 GraphQL 文件裡，文件以
 * {@code -f query=…} 放在 argv 中送出。擷取到的 argv 同時帶有實際送出的旗標與文件要求的
 * 型別，所以不需要另一張對照表；新增變數時用錯旗標，不必改這個檔案測試就會失敗。
 *
 * <p><strong>為何放在 Acceptance 層。</strong>「每個 Tool」只有在 wire 之上的 listTools()
 * 才看得到；更下層的測試只能反射 @McpTool 或手列呼叫點，會綁死實作細節。這一層也拿得到
 * argv：替身 gh 會把它記下來。成本低：一個 Server、一個替身、每個 Tool 呼叫一次。
 */
class ArgvFlagAcceptanceTest {

    @TempDir Path tmp;

    /** JSON 形式不是字串的純量，因此必須用 {@code -F}。 */
    private static final Set<String> SENT_TYPED = Set.of("Int", "Float", "Boolean");

    /** 操作參數列中的 {@code $name:Type}；使用處不帶 {@code :Type}。 */
    private static final Pattern DECLARATION = Pattern.compile("\\$(\\w+)\\s*:\\s*(\\w+)");

    /** 比對 {@code call<n>.arg<i>}，即某次呼叫的某個 argv 元素。 */
    private static final Pattern CAPTURED = Pattern.compile("call(\\d+)\\.arg(\\d+)");

    /**
     * 記下每個 argv 元素後再回應的 {@code gh}。
     *
     * <p>每個元素存成一個檔案，完全不用分隔符。GraphQL 文件有好幾行，用
     * {@code printf '%s\n' "$@"} 會把一個 argv 元素拆成多行且無法還原；改用 NUL 分隔則要
     * 假設本機與 CI 的 {@code /bin/sh} 是同一種 shell，而兩者並不相同。
     *
     * <p>替身會正常回應而不是失敗，因為 {@code add_issue_comment} 呼叫 gh 兩次，只有第二次
     * 帶 body：若替身在查 id 時就失敗，mutation 的 argv 根本不會被記下，測試會在檢查錯的
     * 那一半時通過。
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

    /** 替身記下的每次呼叫，依記錄順序排列。 */
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

    /** {@code name -> declared type}，從操作的參數列讀出。 */
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
     * 以一次 {@code api graphql} 呼叫自帶的 GraphQL 文件檢查它的 argv。
     *
     * <p>索引 2 之後都是旗標與 {@code key=value} 成對出現，所以逐對走訪而不是搜尋；
     * 否則某個值剛好是 {@code -f} 時，整段掃描會錯位一格。
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

                // 刻意不讀結果：Tool 回什麼由 WireAcceptanceTest 與
                // FailureContractAcceptanceTest 驗證；本測試唯一的證據是替身記下的 argv。
                client.callTool(new CallToolRequest(tool.name(), arguments));
            }
        }

        List<List<String>> calls = captured(tmp);

        // 不只檢查非空。每個 Tool 都被呼叫過，且每個 Tool 至少呼叫 gh 一次，所以總數少於
        // Tool 數，代表有 Tool 在啟動子行程前就被拒絕，它的 argv 不是錯而是缺席。
        // 只用 isNotEmpty() 的話，其他 Tool 會替它掩護。
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
                // 非 `api graphql` 的一般 gh 指令。型別規則不適用，但「不帶欄位旗標」本身
                // 就是斷言：不經 `api graphql` 傳欄位的 Tool 同樣有旗標的風險，不能在這裡
                // 被略過。
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

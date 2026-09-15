package io.github.demianli.projectmcp.wire;

import io.github.demianli.projectmcp.gh.FakeGh;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 以 Client 的方式啟動 Server，回傳已連線的 {@link McpSyncClient}。
 *
 * <p>供 Acceptance 層測試共用。{@code requestTimeout} 可調，因為寫入分流測試必須等得比
 * GhCli 預設的 30 秒更久。
 *
 * <p>從測試 classpath 啟動而非打包好的 jar，因為 {@code mvn test} 在 {@code package} 之前
 * 執行。子行程跑同一個 main class、Spring context 與 stdio transport：一個真正獨立的行程，
 * 透過 pipe 傳送真正的 JSON-RPC。
 */
final class LaunchedServer {

    /**
     * Client 放棄單一請求前等待的時間。
     *
     * <p>刻意與 {@code GhCli.TIMEOUT_SECONDS} 同為 30 秒：碰不到該逾時的測試不受影響，
     * 會碰到的那個測試則不能用它，見 {@link #withGh(Path, String, Duration)}。
     */
    static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private LaunchedServer() {
    }

    /** {@code PATH} 恰好是 {@code path}、別無其他的 Server。 */
    static McpSyncClient onPath(String path) {
        return onPath(path, DEFAULT_REQUEST_TIMEOUT);
    }

    static McpSyncClient onPath(String path, Duration requestTimeout) {
        return onPath(path, requestTimeout, System.getProperty("java.class.path"), new String[0]);
    }

    private static McpSyncClient onPath(String path, Duration requestTimeout, String classpath,
            String[] appArgs) {
        return onPath(path, requestTimeout, classpath, new String[0], appArgs);
    }

    private static McpSyncClient onPath(String path, Duration requestTimeout, String classpath,
            String[] jvmArgs, String[] appArgs) {
        var args = new ArrayList<>(List.of(jvmArgs));
        args.addAll(List.of("-cp", classpath,
                "io.github.demianli.projectmcp.ProjectMcpApplication"));
        args.addAll(List.of(appArgs));

        var params = ServerParameters.builder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString())
                .args(args)
                .env(Map.of("PATH", path))
                .build();

        var client = McpClient.sync(new StdioClientTransport(params, McpJsonDefaults.getMapper()))
                .requestTimeout(requestTimeout)
                .build();
        client.initialize();
        return client;
    }

    /**
     * PATH 上有替身 {@code gh} 的 Server，替身執行 {@code ghBody}。
     *
     * <p>替身排在 PATH 最前面，會蓋過任何真正的 gh（GitHub 託管的 Ubuntu runner 在
     * /usr/bin/gh 裝有 gh）。結尾的 /usr/bin:/bin 讓替身用得到所需的 shell 工具。
     */
    static McpSyncClient withGh(Path dir, String ghBody) throws IOException {
        return withGh(dir, ghBody, DEFAULT_REQUEST_TIMEOUT);
    }

    /**
     * 同上，供必須等過某個時限的測試使用。
     *
     * <p>唯一會傳入時限的是 {@link WritePartitionAcceptanceTest}，它刻意讓 Tool 撞上
     * {@code GhCli} 的 30 秒逾時。若用預設值，兩個時限同時到期，誰先到是競態；Client 必須
     * 等得比較久，Server 的回應才送得到。
     */
    static McpSyncClient withGh(Path dir, String ghBody, Duration requestTimeout)
            throws IOException {
        FakeGh.writing(dir, ghBody);
        return onPath(dir + ":/usr/bin:/bin", requestTimeout);
    }

    /**
     * 同上，並把 Server 的 log 檔導向測試指定的位置。
     *
     * <p>{@link TraceContractAcceptanceTest} 用它斷言 log 裡有什麼、沒有什麼。導向獨立檔案，
     * 讓本次測試的 log 不與本機其他執行混在一起。以程式參數傳入，因為 {@link #onPath} 會
     * 清掉 PATH 以外的環境變數。
     */
    static McpSyncClient withGhLoggingTo(Path dir, String ghBody, Path logFile)
            throws IOException {
        FakeGh.writing(dir, ghBody);
        return onPath(dir + ":/usr/bin:/bin", DEFAULT_REQUEST_TIMEOUT, withoutTestClasses(),
                new String[] {"--logging.file.name=" + logFile});
    }

    /**
     * 限制 heap 的 Server，用來測記憶體耗盡的路徑。
     *
     * <p>測試 {@code ToolResults} 的 fatal 分支：Server 記憶體耗盡、必須停止接受呼叫時觸發。
     * 這是從 wire 走到這條路徑的唯一方式。
     */
    static McpSyncClient withGhAndHeap(Path dir, String ghBody, String heap, Path logFile)
            throws IOException {
        FakeGh.writing(dir, ghBody);
        return onPath(dir + ":/usr/bin:/bin", DEFAULT_REQUEST_TIMEOUT, withoutTestClasses(),
                new String[] {"-Xmx" + heap},
                new String[] {"--logging.file.name=" + logFile});
    }

    /**
     * 子行程的 classpath：Server 的 class 與相依套件，不含測試 class。
     *
     * <p>測試用的 Logback 設定會關掉測試執行時的 log。test-classes 在子行程的 classpath 上時，
     * Logback 會讀到它，Spring Boot 也就不裝檔案 appender，所以需要讀 log 的測試用這個方法
     * 排除 test-classes。結果與 Client 實際載入的相同：只有正式程式碼，PATH 上另有替身 gh，
     * fixture 由測試 JVM 讀取。
     */
    private static String withoutTestClasses() {
        return Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(entry -> !entry.endsWith("test-classes"))
                .collect(Collectors.joining(File.pathSeparator));
    }
}

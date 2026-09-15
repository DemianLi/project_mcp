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
 * Launches the Server as a Client would and returns a connected {@link McpSyncClient}.
 *
 * <p>Shared by Acceptance layer tests. Factored out when a second file needed it, with
 * {@code requestTimeout} parameterized because write-partition tests must wait longer than
 * GhCli's default 30 seconds.
 *
 * <p>Launched from the test classpath rather than the packaged jar, since {@code mvn test}
 * runs before {@code package}. The subprocess runs the same main class, Spring context and
 * Stdio transport — a real separate process with real JSON-RPC over a pipe.
 */
final class LaunchedServer {

    /**
     * What a Client waits before giving up on one request.
     *
     * <p>Deliberately the same 30 seconds as {@code GhCli.TIMEOUT_SECONDS}, which is fine
     * for every test that never reaches it and wrong for the one that does — see
     * {@link #withGh(Path, String, Duration)}.
     */
    static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private LaunchedServer() {
    }

    /** A Server whose {@code PATH} is exactly {@code path}, and nothing else. */
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
     * A Server with a stand-in {@code gh} on PATH that runs {@code ghBody}.
     *
     * <p>The stand-in is first on PATH so it shadows any real gh. GitHub-hosted Ubuntu
     * runners have gh at /usr/bin/gh, so the trailing /usr/bin:/bin ensures shell utilities
     * the stand-in needs are available.
     */
    static McpSyncClient withGh(Path dir, String ghBody) throws IOException {
        return withGh(dir, ghBody, DEFAULT_REQUEST_TIMEOUT);
    }

    /**
     * The same, for a test that has to outwait something.
     *
     * <p>The only caller passing anything here is {@link WritePartitionAcceptanceTest},
     * which drives a Tool into {@code GhCli}'s 30-second timeout on purpose. At the default
     * the two budgets expire together and which one lands first is a race; the Client has to
     * be the patient one for the Server's answer to arrive at all.
     */
    static McpSyncClient withGh(Path dir, String ghBody, Duration requestTimeout)
            throws IOException {
        FakeGh.writing(dir, ghBody);
        return onPath(dir + ":/usr/bin:/bin", requestTimeout);
    }

    /**
     * The same, with the Server's log file redirected to a test-specified location.
     *
     * <p>{@link TraceContractAcceptanceTest} uses this to assert what the log contains and
     * what it does not. Redirecting keeps the test's lines separate from other runs on the
     * machine. Passed as a program argument because {@link #onPath} clears the environment
     * except for PATH.
     */
    static McpSyncClient withGhLoggingTo(Path dir, String ghBody, Path logFile)
            throws IOException {
        FakeGh.writing(dir, ghBody);
        return onPath(dir + ":/usr/bin:/bin", DEFAULT_REQUEST_TIMEOUT, withoutTestClasses(),
                new String[] {"--logging.file.name=" + logFile});
    }

    /**
     * A Server with constrained heap to test out-of-memory paths.
     *
     * <p>Tests {@code ToolResults}' fatal branch, which fires when the Server has exhausted
     * memory and must stop accepting calls. Only way to reach this path from the wire.
     */
    static McpSyncClient withGhAndHeap(Path dir, String ghBody, String heap, Path logFile)
            throws IOException {
        FakeGh.writing(dir, ghBody);
        return onPath(dir + ":/usr/bin:/bin", DEFAULT_REQUEST_TIMEOUT, withoutTestClasses(),
                new String[] {"-Xmx" + heap},
                new String[] {"--logging.file.name=" + logFile});
    }

    /**
     * Classpath for the child process: the Server classes and dependencies, but not test
     * classes.
     *
     * <p>Logback test config silences logging in test runs. When test-classes is on the
     * child's path, Logback finds it and Spring Boot does not install its file appender.
     * For tests that read logs, this method excludes test-classes. The result matches what
     * a Client would load: production code only, with stand-in gh on PATH and fixtures
     * read by the test JVM.
     */
    private static String withoutTestClasses() {
        return Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(entry -> !entry.endsWith("test-classes"))
                .collect(Collectors.joining(File.pathSeparator));
    }
}

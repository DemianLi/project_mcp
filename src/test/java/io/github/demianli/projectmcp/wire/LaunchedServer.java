package io.github.demianli.projectmcp.wire;

import io.github.demianli.projectmcp.gh.FakeGh;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * Starts the Server the way a Client starts it, and hands back a connected Client.
 *
 * <p>Shared by every test in the Acceptance layer. It was two private helpers on
 * {@link WireAcceptanceTest} until a second file in this layer needed them, and the thing
 * that made it a seam rather than a copy is {@code requestTimeout}: the write-partition
 * tests have to outwait {@code GhCli}'s own 30-second budget, and every other test must not.
 * A copied helper would have grown that parameter on one side only.
 *
 * <p>Started from the test's own classpath rather than the packaged jar: {@code mvn test}
 * runs before {@code package}, so requiring the jar would make the suite depend on a build
 * step that has not happened yet. Everything that matters is identical — same main class,
 * same Spring context, same Stdio transport, a genuinely separate process, and a real
 * JSON-RPC conversation across a pipe.
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
        var params = ServerParameters.builder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString())
                .args("-cp", System.getProperty("java.class.path"),
                        "io.github.demianli.projectmcp.ProjectMcpApplication")
                .env(Map.of("PATH", path))
                .build();

        var client = McpClient.sync(new StdioClientTransport(params, McpJsonDefaults.getMapper()))
                .requestTimeout(requestTimeout)
                .build();
        client.initialize();
        return client;
    }

    /**
     * A Server that finds a stand-in {@code gh} running {@code ghBody}, and nothing else
     * worth finding.
     *
     * <p>The stand-in comes <em>first</em> on the {@code PATH}, and that — not the absence
     * of a real {@code gh} — is the guarantee. GitHub-hosted Ubuntu runners ship the GitHub
     * CLI at {@code /usr/bin/gh}, so assuming the trailing directories are empty of it would
     * be true on a laptop and false in CI. Shadowing holds either way. The trailing
     * {@code /usr/bin:/bin} is there for the shell utilities the stand-in itself uses,
     * nothing more.
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
}

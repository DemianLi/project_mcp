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
        return onPath(path, requestTimeout, System.getProperty("java.class.path"), new String[0]);
    }

    private static McpSyncClient onPath(String path, Duration requestTimeout, String classpath,
            String[] appArgs) {
        var args = new ArrayList<>(List.of(
                "-cp", classpath,
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

    /**
     * The same, with the Server's log file redirected somewhere a test can read it.
     *
     * <p>The one caller is {@link TraceContractAcceptanceTest}, which asserts on what the
     * file contains and — more to the point — on what it does not. Redirected rather than
     * read from {@code logs/}: the real file is appended to by every other run on this
     * machine, so a test reading it would be asserting about someone else's lines, and a
     * test asserting a string is <em>absent</em> from it would pass or fail on history.
     *
     * <p>Passed as a program argument rather than an environment variable because
     * {@link #onPath} replaces the environment wholesale, and {@code PATH} is the only entry
     * that belongs in it.
     */
    static McpSyncClient withGhLoggingTo(Path dir, String ghBody, Path logFile)
            throws IOException {
        FakeGh.writing(dir, ghBody);
        return onPath(dir + ":/usr/bin:/bin", DEFAULT_REQUEST_TIMEOUT, withoutTestClasses(),
                new String[] {"--logging.file.name=" + logFile});
    }

    /**
     * The classpath a Client would start this Server on, which is not the one the tests run.
     *
     * <p>{@code src/test/resources/logback-test.xml} silences {@code GhCli} and gives the
     * root logger no appender at all, on purpose — the suite provokes failures by the dozen
     * and every one of them would otherwise print. A child launched with
     * {@code target/test-classes} on its path finds that file, and Logback having a
     * configuration of its own means Spring Boot never installs the file appender
     * {@code application.yml} describes. The Server starts, answers, and writes nothing
     * anywhere, which is invisible to every test that does not read the log.
     *
     * <p>So the one test that reads it drops that directory. What is left is exactly what a
     * Client's {@code java -cp} would contain: the Server's own classes and its
     * dependencies. Nothing in the child ever came from the test tree — the stand-in
     * {@code gh} arrives on {@code PATH} and the fixtures are read by the test JVM.
     */
    private static String withoutTestClasses() {
        return Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(entry -> !entry.endsWith("test-classes"))
                .collect(Collectors.joining(File.pathSeparator));
    }
}

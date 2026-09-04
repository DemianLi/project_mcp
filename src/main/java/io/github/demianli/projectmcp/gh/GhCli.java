package io.github.demianli.projectmcp.gh;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

/**
 * Runs the {@code gh} binary and hands back its stdout.
 *
 * <p>This is the Server's only route to GitHub — there is no REST or GraphQL client.
 * Authentication is entirely {@code gh}'s concern: this class never reads a token and
 * never sets one.
 *
 * <p>Arguments are passed as separate argv elements and no shell is involved, so a
 * repository name containing shell metacharacters is inert.
 */
@Component
public class GhCli {

    private static final long TIMEOUT_SECONDS = 30;

    /**
     * Runs {@code gh} with the given arguments and returns its stdout.
     *
     * @throws RuntimeException if {@code gh} is missing, exits non-zero, or outlives the
     *     timeout. The shape of that failure as the Client sees it is deliberately
     *     undecided — see the "what a Tool returns when {@code gh} fails" entry on the map
     *     (issue #1). Until it is decided, the exception carries {@code gh}'s own stderr,
     *     which is the most informative thing available.
     */
    public String run(List<String> args) {
        List<String> command = new ArrayList<>(args.size() + 1);
        command.add("gh");
        command.addAll(args);

        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException e) {
            throw new RuntimeException(
                    "Could not start `gh`. Is the GitHub CLI installed and on PATH?", e);
        }

        // Both pipes must be drained concurrently. Reading stdout to completion while
        // stderr fills its buffer deadlocks: `gh` blocks writing and never exits, and the
        // Tool call hangs with no error to report.
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<byte[]> stdout = executor.submit(() -> process.getInputStream().readAllBytes());
            Future<byte[]> stderr = executor.submit(() -> process.getErrorStream().readAllBytes());

            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new RuntimeException(
                        describe(command) + " did not finish within " + TIMEOUT_SECONDS + "s.");
            }

            String out = new String(stdout.get(), StandardCharsets.UTF_8);
            String err = new String(stderr.get(), StandardCharsets.UTF_8).strip();

            if (process.exitValue() != 0) {
                throw new RuntimeException(describe(command) + " failed (exit "
                        + process.exitValue() + "): " + (err.isEmpty() ? "no stderr output" : err));
            }
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new RuntimeException(describe(command) + " was interrupted.", e);
        } catch (ExecutionException e) {
            process.destroyForcibly();
            throw new RuntimeException("Could not read the output of " + describe(command) + ".", e);
        }
    }

    private static String describe(List<String> command) {
        return "`" + String.join(" ", command) + "`";
    }
}

package io.github.demianli.projectmcp.gh;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs the {@code gh} binary and hands back its stdout.
 *
 * <p>This is the Server's only route to GitHub — there is no REST or GraphQL client.
 * Authentication is entirely {@code gh}'s concern: this class never reads a token.
 *
 * <p>Arguments are passed as separate argv elements and no shell is involved. stderr
 * classification is {@link GhStderr}'s concern at the single point where there is stderr to
 * read. Every Tool inherits the failure contract by calling {@link #run} or
 * {@link #runWrite}, with no per-Tool code.
 *
 * <p>On a read, a timeout or unreadable pipe means nothing happened—retry. On a write,
 * those same exits mean the result is unconfirmed—check before retrying. A Tool says which
 * by choosing {@link #run} or {@link #runWrite}.
 */
@Component
public class GhCli {

    private static final Logger log = LoggerFactory.getLogger(GhCli.class);

    /**
     * Timeout for a single {@code gh} call in seconds. Part of the failure contract; see
     * docs/design.md#bounds. The two-argument constructor can override this default.
     */
    static final int TIMEOUT_SECONDS = 30;

    /**
     * Maximum bytes accepted from one {@code gh} call. Responses exceeding this are
     * rejected with {@link Remedy#FIX_REQUEST}. See docs/design.md#bounds.
     */
    static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

    private final String executable;
    private final int timeoutSeconds;

    public GhCli() {
        this("gh", TIMEOUT_SECONDS);
    }

    /**
     * Lets a test point at a stand-in binary and shorten the timeout. Public because it is
     * a real configuration point — a deployment with {@code gh} elsewhere can use it.
     */
    public GhCli(String executable, int timeoutSeconds) {
        this.executable = executable;
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * What a caller is told after a write this Server could not confirm.
     */
    private static final String CHECK_INSTEAD_OF_RETRYING =
            " The comment could not be confirmed. It may already have been posted. Before "
                    + "writing it again, check with `list_issue_comments` whether a comment "
                    + "of yours with this body is already on the issue.";

    /**
     * Runs a {@code gh} call that only reads, and returns its stdout.
     *
     * @throws ToolFailure if {@code gh} is missing, exits non-zero, or outlives the timeout.
     */
    public String run(List<String> args) {
        return run(args, false);
    }

    /**
     * Runs a {@code gh} call that changes something, and returns its stdout.
     *
     * <p>On unconfirmed writes (timeout, interrupt, unreadable pipe), the caller is told
     * {@link Remedy#CHECK_BEFORE_RETRY} instead of {@link Remedy#RETRY}. See
     * docs/design.md#writes.
     *
     * @throws ToolFailure on every failure {@link #run} throws for, with the three
     *     unconfirmed-write exits reclassified.
     */
    public String runWrite(List<String> args) {
        return run(args, true);
    }

    private String run(List<String> args, boolean write) {
        List<String> command = new ArrayList<>(args.size() + 1);
        command.add(executable);
        command.addAll(args);

        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException e) {
            // Not a non-zero exit: the process never existed. This is the only failure that
            // arrives without any stderr to classify, so it is classified by its path here.
            throw failure(command, new ToolFailure(Remedy.ASK_OPERATOR,
                    "The GitHub CLI (`gh`) could not be started. It is probably not "
                            + "installed, or not on this Server's PATH.",
                    "", null));
        }

        // Both pipes must be drained concurrently. Reading stdout to completion while
        // stderr fills its buffer deadlocks: `gh` blocks writing and never exits, and the
        // Tool call hangs with no error to report.
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<byte[]> stdout = executor.submit(() -> process.getInputStream().readAllBytes());
            Future<byte[]> stderr = executor.submit(() -> process.getErrorStream().readAllBytes());

            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                kill(process);
                stdout.cancel(true);
                stderr.cancel(true);
                throw failure(command, new ToolFailure(
                        write ? Remedy.CHECK_BEFORE_RETRY : Remedy.RETRY,
                        "The GitHub CLI did not answer within " + timeoutSeconds + " seconds."
                                + (write ? CHECK_INSTEAD_OF_RETRYING : ""),
                        "", null));
            }

            byte[] out = stdout.get();
            String err = new String(stderr.get(), StandardCharsets.UTF_8).strip();

            // Classify stderr before checking size: a failure reason is worth more than bytes.
            if (process.exitValue() != 0) {
                throw failure(command, GhStderr.classify(err));
            }
            if (out.length > MAX_RESPONSE_BYTES) {
                throw failure(command, tooLarge(out.length));
            }
            return new String(out, StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            kill(process);
            throw failure(command, new ToolFailure(
                    write ? Remedy.CHECK_BEFORE_RETRY : Remedy.RETRY,
                    "The GitHub CLI call was interrupted before it finished."
                            + (write ? CHECK_INSTEAD_OF_RETRYING : ""),
                    "", null));
        } catch (ExecutionException e) {
            kill(process);
            throw failure(command, new ToolFailure(
                    write ? Remedy.CHECK_BEFORE_RETRY : Remedy.UNKNOWN,
                    "The output of the GitHub CLI could not be read."
                            + (write ? CHECK_INSTEAD_OF_RETRYING : ""),
                    String.valueOf(e.getCause()), null));
        }
    }

    /**
     * Failure for a response exceeding {@link #MAX_RESPONSE_BYTES}. Always
     * {@link Remedy#FIX_REQUEST}. This class invents the failure since {@code gh}
     * succeeded but the Server refuses to carry the response.
     */
    private static ToolFailure tooLarge(int bytes) {
        return new ToolFailure(Remedy.FIX_REQUEST,
                "GitHub returned " + bytes + " bytes, over this Server's limit of "
                        + MAX_RESPONSE_BYTES + ". Nothing was lost and nothing was changed; "
                        + "the response was refused rather than carried. If this Tool takes "
                        + "a `limit`, ask for fewer items, or page with a cursor. If it does "
                        + "not, this Tool cannot return this particular subject.",
                "", null);
    }

    /**
     * Kills the process and everything it spawned. {@link Process#destroyForcibly()} alone
     * kills only the direct child; children of that child keep the pipe open, so
     * {@code readAllBytes} blocks forever and the timeout never fires.
     */
    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    /**
     * Logs the argv and returns the failure to throw.
     *
     * <p>The argv is the one piece of diagnosis deliberately kept out of the caller's
     * payload, so this is where it survives. It goes to the log file only — the console
     * appender is off, because stdout belongs to JSON-RPC.
     */
    private static ToolFailure failure(List<String> command, ToolFailure failure) {
        log.warn("`{}` failed [{}]: {}", argv(command), failure.remedy(),
                failure.stderr().isEmpty() ? failure.getMessage() : failure.stderr());
        return failure;
    }

    /**
     * The GraphQL variables that carry <em>content</em> rather than shape.
     * Only {@code body} is elided from logs; see docs/design.md#logging.
     */
    private static final Set<String> CONTENT_VARIABLES = Set.of("body");

    /**
     * The argv as a line, with content elided. The body length is kept because it diagnoses
     * failures without leaking the text.
     */
    private static String argv(List<String> command) {
        List<String> safe = new ArrayList<>(command.size());
        for (String arg : command) {
            int equals = arg.indexOf('=');
            String name = equals < 0 ? "" : arg.substring(0, equals);
            safe.add(CONTENT_VARIABLES.contains(name)
                    ? name + "=<" + (arg.length() - equals - 1) + " chars>"
                    : arg);
        }
        return String.join(" ", safe);
    }
}

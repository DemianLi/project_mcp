package io.github.demianli.projectmcp.gh;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *
 * <p>It is also the only place that knows how {@code gh} fails, which is why classification
 * lives here rather than in the Tools. Recognising {@code gh}'s stderr wording, knowing
 * that a missing binary arrives as an {@link IOException} rather than a non-zero exit,
 * knowing the timeout budget — all of it is already this class's and nothing else's. Every
 * Tool inherits the contract by calling {@link #run}, with no per-Tool code. It also puts
 * every string comparison in one file, which is the only place that needs changing when
 * {@code gh}'s wording drifts.
 */
@Component
public class GhCli {

    private static final Logger log = LoggerFactory.getLogger(GhCli.class);

    /**
     * How long a single {@code gh} call may take.
     *
     * <p>Part of the failure contract, not a private tuning knob: a timeout reports this
     * number as the wait already spent, so changing it changes what callers are told.
     */
    static final int TIMEOUT_SECONDS = 30;

    private final String executable;
    private final int timeoutSeconds;

    public GhCli() {
        this("gh", TIMEOUT_SECONDS);
    }

    /**
     * Lets a test point at a stand-in binary and shorten the timeout.
     *
     * <p>Public because it is a real configuration point — a deployment with {@code gh}
     * somewhere other than the PATH can use it — but its reason for existing is the test
     * suite.
     *
     * <p>The substitution deliberately happens at the executable name and nowhere deeper:
     * everything below it — the spawn, the concurrent pipe draining, the exit code, the
     * timeout — stays real, so the tests exercise the machinery rather than replace it. A
     * mock in front of {@link ProcessBuilder} would make a missing binary, a timeout and a
     * full pipe buffer untestable, which is most of what can actually go wrong here. See
     * issue #9.
     */
    public GhCli(String executable, int timeoutSeconds) {
        this.executable = executable;
        this.timeoutSeconds = timeoutSeconds;
    }

    /** {@code gh} sometimes names a wait; the wording is unverified, so this is best-effort. */
    private static final Pattern RETRY_AFTER =
            Pattern.compile("retry after (\\d+)", Pattern.CASE_INSENSITIVE);

    /**
     * Runs {@code gh} with the given arguments and returns its stdout.
     *
     * @throws GhFailure if {@code gh} is missing, exits non-zero, or outlives the timeout.
     *     The shape the Client sees is fixed by
     *     {@code docs/adr/0002-failure-contract-for-gh-calls.md}.
     */
    public String run(List<String> args) {
        List<String> command = new ArrayList<>(args.size() + 1);
        command.add(executable);
        command.addAll(args);

        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException e) {
            // Not a non-zero exit: the process never existed. This is the only failure that
            // arrives without any stderr to classify, so it is classified by its path here.
            throw failure(command, new GhFailure(Remedy.ASK_OPERATOR,
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
                process.destroyForcibly();
                throw failure(command, new GhFailure(Remedy.RETRY,
                        "The GitHub CLI did not answer within " + timeoutSeconds + " seconds.",
                        "", timeoutSeconds));
            }

            String out = new String(stdout.get(), StandardCharsets.UTF_8);
            String err = new String(stderr.get(), StandardCharsets.UTF_8).strip();

            if (process.exitValue() != 0) {
                throw failure(command, classify(err));
            }
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw failure(command, new GhFailure(Remedy.RETRY,
                    "The GitHub CLI call was interrupted before it finished.", "", null));
        } catch (ExecutionException e) {
            process.destroyForcibly();
            throw failure(command, new GhFailure(Remedy.UNKNOWN,
                    "The output of the GitHub CLI could not be read.",
                    String.valueOf(e.getCause()), null));
        }
    }

    /**
     * Reads {@code gh}'s stderr and decides what the caller should do about it.
     *
     * <p>Best-effort by construction: these are substrings of messages {@code gh} chooses,
     * not an API. Two things bound the damage when a match is wrong or missing — the
     * verbatim stderr always travels alongside, so nothing is lost, and anything unmatched
     * becomes {@link Remedy#UNKNOWN} rather than a confident wrong answer.
     */
    private static GhFailure classify(String stderr) {
        String s = stderr.toLowerCase(Locale.ROOT);

        if (s.contains("rate limit")) {
            Matcher m = RETRY_AFTER.matcher(stderr);
            Integer wait = m.find() ? Integer.valueOf(m.group(1)) : null;
            return new GhFailure(Remedy.RETRY,
                    "GitHub is rate limiting this token."
                            + (wait == null ? "" : " Wait " + wait + " seconds before retrying."),
                    stderr, wait);
        }
        if (s.contains("connection refused") || s.contains("no such host")
                || s.contains("network is unreachable") || s.contains("i/o timeout")
                || s.contains("tls handshake timeout") || s.contains("dial tcp")) {
            return new GhFailure(Remedy.RETRY,
                    "GitHub could not be reached. The network looks unavailable.", stderr, null);
        }
        if (s.contains("http 401") || s.contains("bad credentials")
                || s.contains("gh auth login")) {
            return new GhFailure(Remedy.ASK_OPERATOR,
                    "The GitHub CLI is not authenticated, or its token is no longer valid. "
                            + "Someone with access to this Server has to run `gh auth login`.",
                    stderr, null);
        }
        if (s.contains("could not resolve to a repository")) {
            return new GhFailure(Remedy.FIX_REQUEST,
                    "No such repository. Check `owner` and `repo` — note that a private "
                            + "repository this token cannot see looks the same as one that "
                            + "does not exist.",
                    stderr, null);
        }
        if (s.contains("owner/repo\" format") || s.contains("owner/repo' format")) {
            return new GhFailure(Remedy.FIX_REQUEST,
                    "`owner` and `repo` did not compose a usable repository name. Neither "
                            + "may be empty or contain a slash.",
                    stderr, null);
        }
        if (s.contains("disabled issues")) {
            return new GhFailure(Remedy.FIX_REQUEST,
                    "That repository has issues turned off, so it has none to list.",
                    stderr, null);
        }
        return new GhFailure(Remedy.UNKNOWN,
                "The GitHub CLI failed in a way this Server does not recognise.", stderr, null);
    }

    /**
     * Logs the argv and returns the failure to throw.
     *
     * <p>The argv is the one piece of diagnosis deliberately kept out of the caller's
     * payload, so this is where it survives. It goes to the log file only — the console
     * appender is off, because stdout belongs to JSON-RPC.
     */
    private static GhFailure failure(List<String> command, GhFailure failure) {
        log.warn("`{}` failed [{}]: {}", String.join(" ", command), failure.remedy(),
                failure.stderr().isEmpty() ? failure.getMessage() : failure.stderr());
        return failure;
    }
}

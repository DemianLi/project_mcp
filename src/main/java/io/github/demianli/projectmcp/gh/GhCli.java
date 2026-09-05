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
 *
 * <p>Since ADR-0008 it also knows which calls <em>change something</em>. Three of the exits
 * below kill the process without learning what it did, and that means "nothing happened,
 * try again" on a read and "something may have happened" on a write — one failure, two
 * different actions for the caller. A Tool says which it is by choosing {@link #run} or
 * {@link #runWrite}, and nothing else about the distinction leaves this class. Rewriting
 * the Remedy in a Tool's {@code catch} would move half the contract to exactly where the
 * paragraph above says it must not live, and every future write Tool would copy it.
 */
@Component
public class GhCli {

    private static final Logger log = LoggerFactory.getLogger(GhCli.class);

    /**
     * How long a single {@code gh} call may take.
     *
     * <p>The default only: what a call actually gets is {@link #timeoutSeconds}, which the
     * two-argument constructor sets.
     *
     * <p>Part of the failure contract, not a private tuning knob — though no longer for the
     * reason ADR-0002 gave. It is no longer reported as a wait to observe (ADR-0008 removed
     * that), but it is still named in the timeout's sentence, and on a write whatever this
     * budget is set to decides how often a caller is told to go and check. Shortening it
     * buys responsiveness and costs unconfirmed writes.
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

    /**
     * What a caller is told after a write this Server could not confirm, quoted from
     * ADR-0008.
     *
     * <p>The tension is deliberate and recorded rather than designed away: this is the
     * first per-Tool string in a class whose javadoc promises every Tool inherits the
     * contract "with no per-Tool code", and it names {@code list_issue_comments} by hand,
     * so renaming that Tool silently falsifies this sentence with nothing at compile time
     * noticing. ADR-0008 accepted both costs — the whole reason recovery is left with the
     * Client is that this Server provides the means, and a means the Client is not told
     * about is a hope rather than a contract. The second write Tool will collide with it;
     * that is the point at which to parameterise, not before.
     */
    private static final String CHECK_INSTEAD_OF_RETRYING =
            " The comment could not be confirmed. It may already have been posted. Before "
                    + "writing it again, check with `list_issue_comments` whether a comment "
                    + "of yours with this body is already on the issue.";

    /** {@code gh} sometimes names a wait; the wording is unverified, so this is best-effort. */
    private static final Pattern RETRY_AFTER =
            Pattern.compile("retry after (\\d+)", Pattern.CASE_INSENSITIVE);

    /**
     * Runs a {@code gh} call that only reads, and returns its stdout.
     *
     * @throws ToolFailure if {@code gh} is missing, exits non-zero, or outlives the timeout.
     *     The shape the Client sees is fixed by
     *     {@code docs/adr/0002-failure-contract-for-gh-calls.md}.
     */
    public String run(List<String> args) {
        return run(args, false);
    }

    /**
     * Runs a {@code gh} call that changes something, and returns its stdout.
     *
     * <p>The same spawn, the same draining, the same {@link #classify}. The one difference
     * is what the caller is told when the call is abandoned before its result could be read:
     * those three exits carry {@link Remedy#CHECK_BEFORE_RETRY} instead of advice to call
     * again. See ADR-0008.
     *
     * <p>A sibling method rather than a parameter on {@code run}, so that the four calls
     * that read say nothing at all — a read is the unmarked case, and marking it would put
     * a {@code false} at four call sites whose only job is to stay quiet.
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
                // Nothing is read after a timeout, and waiting for the readers would undo
                // the timeout: see kill(Process).
                stdout.cancel(true);
                stderr.cancel(true);
                // No wait travels with this. `retryAfterSeconds` means "do not retry
                // before this", and the budget already spent is a fact about the past; a
                // Client obeying the documented meaning waited it out and then retried,
                // which on a write is what schedules the duplicate. See ADR-0008.
                throw failure(command, new ToolFailure(
                        write ? Remedy.CHECK_BEFORE_RETRY : Remedy.RETRY,
                        "The GitHub CLI did not answer within " + timeoutSeconds + " seconds."
                                + (write ? CHECK_INSTEAD_OF_RETRYING : ""),
                        "", null));
            }

            String out = new String(stdout.get(), StandardCharsets.UTF_8);
            String err = new String(stderr.get(), StandardCharsets.UTF_8).strip();

            if (process.exitValue() != 0) {
                throw failure(command, classify(err));
            }
            return out;
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
            // The worst of the three on a write: the process may have run to completion and
            // the failure be nothing but this Server not reading the bytes back. On a read
            // it stays UNKNOWN, which is where a Java exception string standing in for
            // stderr comes from.
            throw failure(command, new ToolFailure(
                    write ? Remedy.CHECK_BEFORE_RETRY : Remedy.UNKNOWN,
                    "The output of the GitHub CLI could not be read."
                            + (write ? CHECK_INSTEAD_OF_RETRYING : ""),
                    String.valueOf(e.getCause()), null));
        }
    }

    /**
     * Kills the process <em>and everything it spawned</em>.
     *
     * <p>{@link Process#destroyForcibly()} alone kills only the direct child. Anything that
     * child started inherits the same pipes and keeps the write end open, so
     * {@code readAllBytes} goes on blocking and a timeout stops being a timeout — the call
     * runs for as long as the grandchild does. That is precisely the "hangs with no error to
     * report" failure this class exists to avoid, arriving through a different door.
     *
     * <p>Found by CI on its first run: the timeout test asserts it really waited about a
     * second, and on the Ubuntu runner it took the grandchild's full 30. It did not
     * reproduce on macOS, where the shell disposes of the child differently — so the test
     * had to be right about wall-clock time for the bug to show up at all.
     */
    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    /**
     * Reads {@code gh}'s stderr and decides what the caller should do about it.
     *
     * <p>Best-effort by construction: these are substrings of messages {@code gh} chooses,
     * not an API. Two things bound the damage when a match is wrong or missing — the
     * verbatim stderr always travels alongside, so nothing is lost, and anything unmatched
     * becomes {@link Remedy#UNKNOWN} rather than a confident wrong answer.
     */
    private static ToolFailure classify(String stderr) {
        String s = stderr.toLowerCase(Locale.ROOT);

        if (s.contains("rate limit")) {
            Matcher m = RETRY_AFTER.matcher(stderr);
            Integer wait = m.find() ? Integer.valueOf(m.group(1)) : null;
            return new ToolFailure(Remedy.RETRY,
                    "GitHub is rate limiting this token."
                            + (wait == null ? "" : " Wait " + wait + " seconds before retrying."),
                    stderr, wait);
        }
        if (s.contains("connection refused") || s.contains("no such host")
                || s.contains("network is unreachable") || s.contains("i/o timeout")
                || s.contains("tls handshake timeout") || s.contains("dial tcp")) {
            return new ToolFailure(Remedy.RETRY,
                    "GitHub could not be reached. The network looks unavailable.", stderr, null);
        }
        if (s.contains("http 401") || s.contains("bad credentials")
                || s.contains("gh auth login")) {
            return new ToolFailure(Remedy.ASK_OPERATOR,
                    "The GitHub CLI is not authenticated, or its token is no longer valid. "
                            + "Someone with access to this Server has to run `gh auth login`.",
                    stderr, null);
        }
        // Before the repository case on purpose. The two strings cannot both match, so
        // the order is free — but the repository one reads as the more general of the
        // two, and a later reader scanning this chain should not have to work out that
        // first-match-wins does not matter here.
        if (s.contains("could not resolve to an issue or pull request")) {
            return new ToolFailure(Remedy.FIX_REQUEST,
                    "That repository has no issue with that number. Check `number` — note "
                            + "that `gh` says \"issue or pull request\" because GitHub "
                            + "numbers both from one sequence, so this also means there is "
                            + "no pull request with it either.",
                    stderr, null);
        }
        // The same condition, worded differently because it arrives from a different
        // route. `gh api graphql` says "an Issue with the number of", singular and without
        // the "or pull request" clause the porcelain commands use, so it misses the branch
        // above and would otherwise land on UNKNOWN. The two strings cannot both match, so
        // neither branch disturbs the other -- which matters, because get_issue and
        // list_issues depend on the wording above.
        //
        // One sentence covers a number that does not exist and a number that is a pull
        // request, because GraphQL reports both with these same words and separating them
        // would cost a second call on the failure path. ADR-0002 classifies by the action
        // available rather than by the cause, and the action here is identical: change
        // `number`. See ADR-0005.
        //
        // DO NOT tidy the pull-request half of that sentence away as read-specific wording.
        // Since ADR-0007 this branch is also the whole pull-request guard on the write
        // route: `add_issue_comment` looks an issue up with `repository.issue(number:)`,
        // which cannot resolve a pull request's id, and this is where that refusal is
        // turned into something a Client can act on. Both `gh issue comment` and the REST
        // endpoint were measured writing a comment into a pull request; this branch is what
        // stands between a Client's typo and that side effect.
        if (s.contains("could not resolve to an issue with the number of")) {
            return new ToolFailure(Remedy.FIX_REQUEST,
                    "That repository has no issue with that number. It may not exist at "
                            + "all, or it may be a pull request \u2014 GitHub numbers both "
                            + "from one sequence, and this Server's issue Tools take issues "
                            + "only.",
                    stderr, null);
        }
        // A cursor that is not a cursor. The wrapper Cursors puts around one catches a
        // cursor belonging to a different issue before the call is made; it cannot catch a
        // correctly-addressed wrapper whose inner half is corrupt, which reaches GitHub and
        // fails here. See ADR-0006.
        if (s.contains("does not appear to be a valid cursor")) {
            return new ToolFailure(Remedy.FIX_REQUEST,
                    "That `cursor` is not one GitHub recognises. Pass back the "
                            + "`nextCursor` from a previous response unchanged, or omit it "
                            + "to start from the newest comments.",
                    stderr, null);
        }
        if (s.contains("could not resolve to a repository")) {
            return new ToolFailure(Remedy.FIX_REQUEST,
                    "No such repository. Check `owner` and `repo` — note that a private "
                            + "repository this token cannot see looks the same as one that "
                            + "does not exist.",
                    stderr, null);
        }
        if (s.contains("owner/repo\" format") || s.contains("owner/repo' format")) {
            return new ToolFailure(Remedy.FIX_REQUEST,
                    "`owner` and `repo` did not compose a usable repository name. Neither "
                            + "may be empty or contain a slash.",
                    stderr, null);
        }
        if (s.contains("disabled issues")) {
            return new ToolFailure(Remedy.FIX_REQUEST,
                    "That repository has issues turned off, so it has none to list.",
                    stderr, null);
        }
        return new ToolFailure(Remedy.UNKNOWN,
                "The GitHub CLI failed in a way this Server does not recognise.", stderr, null);
    }

    /**
     * Logs the argv and returns the failure to throw.
     *
     * <p>The argv is the one piece of diagnosis deliberately kept out of the caller's
     * payload, so this is where it survives. It goes to the log file only — the console
     * appender is off, because stdout belongs to JSON-RPC.
     */
    private static ToolFailure failure(List<String> command, ToolFailure failure) {
        log.warn("`{}` failed [{}]: {}", String.join(" ", command), failure.remedy(),
                failure.stderr().isEmpty() ? failure.getMessage() : failure.stderr());
        return failure;
    }
}

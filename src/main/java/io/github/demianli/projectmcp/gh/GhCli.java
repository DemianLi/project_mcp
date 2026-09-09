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
 * Authentication is entirely {@code gh}'s concern: this class never reads a token and
 * never sets one.
 *
 * <p>Arguments are passed as separate argv elements and no shell is involved, so a
 * repository name containing shell metacharacters is inert.
 *
 * <p>How {@code gh} fails is this package's knowledge and no Tool's. Knowing that a missing
 * binary arrives as an {@link IOException} rather than a non-zero exit, and knowing the
 * timeout budget, are this class's; recognising {@code gh}'s stderr wording is
 * {@link GhStderr}'s, one call away at the single point where there is stderr to read. Every
 * Tool inherits the whole contract by calling {@link #run}, with no per-Tool code.
 *
 * <p>That split is an internal seam and not a change of interface. It exists because the two
 * halves are worked on at different rates — the stderr table has gained branches in three
 * separate commits since it was written, the process machinery has changed twice — and
 * because the table has an invariant that an {@code if} chain inside this class could only
 * assert in prose. {@link GhStderr} carries the argument.
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

    /**
     * The most this Server will accept from one {@code gh} call.
     *
     * <p>Eight megabytes, and both ends of that were measured rather than guessed.
     *
     * <p><strong>Above.</strong> The largest response GitHub's own shapes can produce here is
     * a full page of comments at its documented ceiling — 100 nodes of 65,536 characters —
     * which came back at 6.57 MB and crossed the wire in 64 ms. Eight leaves that untouched.
     * A limit that ordinary traffic can reach is a limit that gets raised until it means
     * nothing.
     *
     * <p><strong>Below.</strong> Driving {@code get_issue} against manufactured bodies on a
     * 256 MB heap: 40 MB passed through whole and was delivered to the Client; 60 MB threw
     * {@code OutOfMemoryError}. The break sits near a fifth of the heap, because the bytes
     * are decoded to a {@code String}, parsed to a tree, mapped to records and serialised
     * back to JSON, each step holding its own copy. Eight is far enough below any plausible
     * heap's fifth that <em>this</em> limit is reached first — which is the ordering that
     * matters, since this one comes back as a {@link Remedy} and an {@code OutOfMemoryError}
     * comes back as nothing at all.
     *
     * <p>Not configurable, for the reason {@link #TIMEOUT_SECONDS} and {@code Limits.MAX}
     * are not: every bound in this Server is a constant with an ADR behind it. See
     * {@code docs/adr/0015-a-ceiling-on-one-response.md}.
     */
    static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

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
     * <p>The same spawn, the same draining, the same {@link GhStderr#classify}. The one difference
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

            byte[] out = stdout.get();
            String err = new String(stderr.get(), StandardCharsets.UTF_8).strip();

            // Before the size check. A `gh` that failed and also wrote a great deal has a
            // reason in its stderr, and that reason is worth more to a caller than the
            // number of bytes it managed to produce on the way to it.
            if (process.exitValue() != 0) {
                throw failure(command, GhStderr.classify(err));
            }
            if (out.length > MAX_RESPONSE_BYTES) {
                throw failure(command, tooLarge(out.length));
            }
            // Decoded only once the size is known to be sane: this is where the payload
            // stops being bytes and starts being amplified. See MAX_RESPONSE_BYTES.
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
     * The failure for a response this Server will not carry.
     *
     * <p>{@code FIX_REQUEST} for every Tool, and the sentence has to serve two callers whose
     * available action differs. A {@code list_*} caller can ask for fewer items or page with
     * a cursor. A {@code get_issue} caller cannot make the issue smaller — but the action is
     * still theirs, and it is to stop asking this Tool for this issue. Neither is
     * {@code UNKNOWN}: that Remedy means this Server does not recognise the failure, and this
     * is a failure it invented, named and measured in bytes.
     *
     * <p>The sentence is written once, here, rather than per Tool. This class knows how many
     * bytes arrived and does not know which Tool asked — and giving it that knowledge would
     * undo the boundary the whole package rests on, that how {@code gh} fails is this
     * package's business and no Tool's. ADR-0015 records the cost.
     *
     * <p>{@code stderr} is empty because there was none: {@code gh} succeeded. This is the
     * fourth failure this Server invents rather than inherits.
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
     *
     * <p>One entry, and a new one is not optional. ADR-0013 draws the line this set enforces:
     * the log records which Tool ran against which repository and how it ended, never what
     * was written or read. Every other value in an argv here is shape — {@code owner},
     * {@code name}, {@code number}, {@code subjectId}, the query document itself,
     * {@code --repo}, {@code --limit}, {@code --json} — and stays legible because diagnosis
     * needs it. {@code body} is the one that is the Client's text.
     */
    private static final Set<String> CONTENT_VARIABLES = Set.of("body");

    /**
     * The argv as a line, with content elided.
     *
     * <p>Measured before it was written: an {@code add_issue_comment} whose mutation failed
     * put the entire comment into {@code logs/project-mcp.log}, because the argv it logs
     * ends in {@code -f body=<the comment>}. The failure needed to be the mutation rather
     * than the lookup for it to happen, which is why four Tools' worth of green tests never
     * saw it.
     *
     * <p>The length survives. It is shape, and it is the half of the value that diagnoses
     * anything: a body of 0 characters and a body of 60000 fail for different reasons, and
     * neither reason is legible from the text itself.
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

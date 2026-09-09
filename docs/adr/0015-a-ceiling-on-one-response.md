# 15. A ceiling on one response, and what to do when there is no answer left

Date: 2026-09-09

## Status

Accepted. Adds a fourth invented failure to
[ADR-0002](0002-failure-contract-for-gh-calls.md)'s contract, and a third ending to
[ADR-0013](0013-what-a-call-leaves-behind.md)'s two.

## Context

`docs/reviews/commercial-readiness.md` listed "無回應大小上限，極端情況 OOM 風險" as a
pre-launch item, at low cost, with two suggested fixes. Driving the Server found the
direction right and the weight wrong, and found something else underneath it.

**What was measured**, `get_issue` against manufactured bodies, 256 MB heap:

| Response | What happened |
| --- | --- |
| 1 MB, 10 MB, 20 MB, 40 MB | passed through whole and delivered to the Client |
| 60 MB | `OutOfMemoryError` |

The break sits near a fifth of the heap, because the bytes are decoded to a `String`,
parsed to a tree, mapped to records and serialised back to JSON, each step holding its own
copy of the payload.

**And what GitHub can actually produce.** A full page of comments at the documented body
ceiling — 100 nodes of 65,536 characters — came back at **6.57 MB**, crossed the wire in
64 ms, and was reported as a success. So on any ordinary heap the OOM is not the everyday
harm. The everyday harm is 6.5 MB of text arriving in a model's context as one tool result,
with nothing in this Server having an opinion about it.

**The OOM's failure mode is the serious part.** At 60 MB the Client received no response of
any kind — not `isError`, not a Remedy, not a protocol error. Nothing was written to the log
but Reactor's own stack trace, so the file could not say the call had happened. And the
process **stayed alive after its stdin closed**: measured at ten minutes and still holding
the pipe, until it was killed by hand. Under stdio a Client that gives up restarts the
Server; the abandoned one stays for as long as the machine does.

That is precisely the failure `GhCli.kill(Process)` exists to prevent — "the 'hangs with no
error to report' failure this class exists to avoid" — arriving through a different door.

## Decision

### A ceiling of 8 MB on one `gh` response

`GhCli.MAX_RESPONSE_BYTES = 8 * 1024 * 1024`. Over it, the call fails with `FIX_REQUEST` and
a sentence naming both numbers.

**Eight**, because it must sit above what GitHub can ordinarily produce (6.57 MB measured)
and far below the heap's fifth, so that this limit is reached first. That ordering is the
whole point: the ceiling comes back as a Remedy, and an `OutOfMemoryError` comes back as
nothing at all. A limit ordinary traffic can reach is a limit that gets raised until it means
nothing.

**In `GhCli`, on the raw bytes**, after `readAllBytes` and before the `String`. That is where
the payload stops being bytes and starts being amplified, and it is the only place a check
sits ahead of the whole chain. `ToolResults` already computes `resultBytes`, but by then the
peak has been paid. The two numbers are also not the same — `resultBytes` is what the Client
receives, after mapping has dropped fields — and the ceiling is deliberately on the larger,
earlier one.

**After the exit-code check.** A `gh` that failed *and* wrote a great deal has a reason in
its stderr, and that reason is worth more than the byte count it reached on the way there.

**`FIX_REQUEST` for every Tool.** A `list_*` caller can ask for fewer items or page with a
cursor. A `get_issue` caller cannot make the issue smaller, but the action is still theirs
and it is to stop asking this Tool for this subject. `UNKNOWN` was rejected: that Remedy
means this Server does not recognise the failure, and this is one it invented, named, and
counted in bytes.

**Not configurable.** Every bound here is a constant with an ADR behind it —
`GhCli.TIMEOUT_SECONDS`, `Limits.DEFAULT`, `Limits.MAX`. Adding the first property would open
which others should be properties, and nothing measured says this one needs adjusting.

### A fatal `Error` ends the process

`ToolResults.attempt` gains a third branch. An `Error` is not a failure this Server can
report: the process is no longer trustworthy, and building a `CallToolResult` to say so needs
the memory that has just run out. So the branch writes the call's line with
`outcome: "fatal"` and calls `Runtime.getRuntime().halt(70)`.

`halt` rather than `exit`, because shutdown hooks are more of the untrustworthy process. The
line survives it: Logback's file appender flushes on write.

The asymmetry that decides this is stdio's. **A Client can restart a Server that died. It can
only wait out its own timeout against one that is running and will never answer** — and the
abandoned process outlives the Client that started it.

### And the JVM flags, which are not redundant

`-XX:+ExitOnOutOfMemoryError -XX:+DisplayVMOutputToStderr`, in the `Dockerfile` and in
`docs/deploying.md`.

**They have to be the pair.** `ExitOnOutOfMemoryError` alone prints `Terminating due to
java.lang.OutOfMemoryError` — to **stdout**, which is the JSON-RPC stream, breaking the one
`MUST NOT` the stdio transport states. `-XX:OnOutOfMemoryError="kill -9 %p"` prints four
lines there. `DisplayVMOutputToStderr` moves the JVM's own output to stderr, where the
specification says a stdio server may write. All three measured.

**And they are not belt-and-braces — which is a measurement, not an argument.** The same
provocation, the same 32 MB heap, produced two different endings. On the host the fatal
branch won: a line with `outcome: "fatal"` and an `OutOfMemoryError` recorded beside it. In
the container the flag won: `Terminating due to...` on stderr, and the log file holding its
six startup lines and nothing else — no trace line, no `OutOfMemoryError`, no record that
`get_issue` had ever been called.

Which one gets there first is not something this Server decides. What can be said is what was
seen: **there are occurrences the code branch does not cover, and occurrences the flags are
not present for.** Keeping both is the only arrangement where every occurrence is covered by
something. What cannot be said from these two runs is *why* they differed — whether the catch
was never reached, or reached and outrun. That would need a JVM-level trace nobody has run.

## Consequences

**The measured failure is gone.** 60 MB now comes back as `FIX_REQUEST` in milliseconds,
the process exits when its stdin closes, and stdout stays clean. 6.57 MB of comments is
untouched — the ceiling does not touch ordinary traffic, which is the point.

**Beyond the ceiling there is still a cliff, and it is now reported.** A response so large
that `readAllBytes` itself cannot hold it — 200 MB on a 256 MB heap — surfaces through
`GhCli`'s existing `ExecutionException` branch as `UNKNOWN`, and the process exits cleanly.
Not ideal (the Remedy is vague for a failure whose cause is known) and no longer dangerous.
Bounding the read itself would mean stopping short and killing a `gh` that is still writing,
which turns a size failure into the timeout it is not.

**`ADR-0013`'s list of calls that leave no line is unchanged at one.** A schema-rejected call
still leaves nothing, because the SDK answers before dispatch. A fatal `Error` used to leave
nothing and now leaves a line — that is what the third branch bought.

**`outcome` has three values now**: `ok`, `error`, `fatal`. A reader filtering on `error`
will not see the fatal ones.

**The two boundaries are pinned at the wire.** `ResponseCeilingAcceptanceTest` sends 9 MB
against the 8 MB ceiling and asserts the refusal, the Remedy and that the Server survives it;
then gives a real Server 32 MB of heap and 7 MB of body and asserts that it dies, and that
the line naming the call is on disk before it does. The second test runs a JVM out of memory
on purpose, because the branch is about what a JVM does afterwards and nothing else provokes
that honestly.

## Alternatives considered

**Lower `Limits.MAX` from 100.** It bounds the item count, not the bytes: one comment at
GitHub's 65,536-character ceiling is large at any count, and `get_issue` returns one item by
definition. It would also make every ordinary paging call worse to buy nothing at the top.

**Check the size in `ToolResults`.** The number is already there. It is also already too
late: the decode and the parse have happened, which is where the memory went.

**Return an `isError` result from the fatal branch instead of halting.** It needs an
allocation from a heap that has just failed to allocate, on a thread that has just thrown,
in a process whose other threads may be in any state. And a Server that answers once more and
then goes on being wrong is worse for a Client than one that is gone.

**A JVM flag alone, no code branch.** It would leave the log with no line saying which call
died — and it prints to stdout unless paired, which is how the pairing was discovered.

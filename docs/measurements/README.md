# Measurements

The scripts behind numbers that appear in this repository's documents. They are primary
sources, not tests: they assert nothing and pass nothing. Each one prints what it saw, and
a person decides what it means.

They live here because a number in a document with no way to reproduce it is a number the
next reader has to take on faith — and because two of the numbers in `docs/deploying.md`
were wrong the first time I took them, in ways only re-running caught.

## Running them

```sh
mvn package                       # they drive the packaged jar, never `mvn spring-boot:run`
JAVA_HOME=/path/to/jdk-25 docs/measurements/tool-latency.sh
JAVA_HOME=/path/to/jdk-25 docs/measurements/concurrency-and-cancellation.sh
```

`JAVA_HOME` is optional if `java` is already on `PATH`. Output — logs, raw stdout, the
JSON-RPC that came back — is written under `target/measurements/`, which is ignored by git.
`python3` is used to summarise the first one.

| Script | Network | Writes | Takes | Verdict |
| --- | --- | --- | --- | --- |
| `tool-latency.sh` | real GitHub, authenticated `gh` | nothing — read-only Tools only | ~1 min | prints |
| `concurrency-and-cancellation.sh` | none — a stand-in `gh` on `PATH` | nothing | ~45 s | prints |
| `gh-compatibility.sh` | real GitHub, authenticated `gh` | nothing — every probe is a read | ~30 s | **exits non-zero** |

## What each one answers

**`tool-latency.sh`** — what one Tool call costs end to end. It reads `durationMs` out of
the Server's own trace line ([ADR-0013](../adr/0013-what-a-call-leaves-behind.md)) rather
than timing from the shell, because that field covers the whole Tool body: the `gh` round
trip, the parse, and the serialise, which together are what the Client waits for. Its table
is in [deploying.md](../deploying.md) under *Two clocks*, and it is the evidence that 30
seconds is two orders of magnitude away from ordinary traffic rather than merely "about
long enough".

**`concurrency-and-cancellation.sh`** — what happens to a call the Client stopped waiting
for, and to the calls beside it. A stand-in `gh` sleeping 20 seconds makes the three
questions visible at once: whether a slow call blocks the next one (it does not), whether
its late response interleaves (it does not — responses leave in completion order with their
ids), and what this Server does with `notifications/cancelled` (ignores it, and logs the
Client's own reason text). See
[ADR-0016](../adr/0016-a-cancelled-call-is-not-cancelled-here.md).

**`gh-compatibility.sh`** — whether the `gh` in front of this Server still fails in the
words `GhStderr` recognises. Those markers are substrings of messages `gh` chooses, not an
API, and a release that rephrases one degrades this Server quietly: the marker stops
matching, the failure falls to `UNKNOWN`, and the Client gets a well-formed response with no
recovery advice in it. [ADR-0002](../adr/0002-failure-contract-for-gh-calls.md) accepts that
risk; this script is what turns *accepted* into *checked*. It provokes seven of the ten rows
through the Server's own Tools, reads the Remedy and the row's own sentence back off the
wire, and names the three it cannot provoke along with why.

This is the one script here with a right answer, so it is also the one that fails: it exits
non-zero when a row stops matching, and prints the stderr to paste into that row's samples.
Run it after upgrading `gh`, and before changing the `gh` version in the
[`Dockerfile`](../../Dockerfile).

Two rows are checked by more than the Remedy on purpose. Six of the seven answer
`FIX_REQUEST`, so a Remedy alone cannot tell a matching row from a *different* matching row —
the script also looks for a fragment of the sentence that row alone writes.

## What these numbers are not

One machine, one network, one afternoon. Latency here is a Mac talking to `github.com` over
a home connection; treat the figures as an order of magnitude and not as an SLO — the trap
[ADR-0014](../adr/0014-no-metrics-and-who-would-have-to.md) names. The concurrency result is
the SDK's scheduling rather than this Server's, so it is an observation about MCP Java SDK
2.0.0 and Spring AI 2.0.1, not a property this repository promises to keep. Neither is
pinned by a test, deliberately: a test over the first would assert the weather, and a test
over the second would assert someone else's internals.

## What is not here

The measurements behind [ADR-0015](../adr/0015-a-ceiling-on-one-response.md) — the 8 MB
ceiling, the out-of-memory break near a fifth of the heap, the 6.57 MB worst shape GitHub
produces — were taken with throwaway probes against manufactured payloads and are not in
this directory. What survives of them is the ADR, the two wire tests that pin the ceiling
and the fatal path, and the numbers in the javadoc of `GhCli.MAX_RESPONSE_BYTES`.

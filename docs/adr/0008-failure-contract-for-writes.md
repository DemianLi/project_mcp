# 8. The failure contract for writes

Date: 2026-09-05

## Status

Accepted. Resolves [#28](https://github.com/DemianLi/project_mcp/issues/28); binds the
implementation in [#31](https://github.com/DemianLi/project_mcp/issues/31). Builds on
ADR-0007, which added the first write.

**Supersedes ADR-0002 in part.** Two of its rulings are withdrawn — that the four Remedies
are enough, and that a timeout reports the budget it spent. Its principle is untouched and
is the basis of everything below: **failures are classified by the action available, not by
the cause.**

## Context

ADR-0002 was written when every Tool was a read, and retrying a read is free. It put
timeouts in `Remedy.RETRY`, whose javadoc says "Try the same call again". ADR-0007 added a
Tool that writes. `GhCli.TIMEOUT_SECONDS = 30` is short enough to expire over a write that
GitHub has already accepted, and a Client obeying `RETRY` then posts the comment twice.

Three facts were measured while resolving #28 and shape everything below.

**GitHub offers no idempotency key for this mutation.** `AddCommentInput` has
`clientMutationId`, which is the obvious candidate; GitHub's own schema describes it as "A
unique identifier for the client performing the mutation" — Relay's echo field, naming the
client rather than the mutation. Sending the same key with the same body twice produced two
distinct comments (`issuecomment-5552768939`, `issuecomment-5552769082`). The duplicate
cannot be prevented at GitHub's end; it can only be detected afterwards.

**Three of `GhCli.run`'s exits kill the process and can leave a side effect behind** — the
timeout, `InterruptedException`, and `ExecutionException` from an unreadable pipe. Only the
first was named on the ticket.

**The spec manufactures this case and does not help with it.**
[#26](https://github.com/DemianLi/project_mcp/issues/26) found the normative timeout path is
SHOULD cancel on expiry → the cancellation may arrive after the work is done → the Client
SHOULD ignore a response arriving afterwards. No detection mechanism is offered.

## Decision

### The Client recovers, not the Server

On an unconfirmed write the Server reports; the caller acts. It does **not** silently
re-query GitHub to find out what happened.

This Server has something a generic MCP server does not: it already exposes
`list_issue_comments`, which is exactly the Tool needed to answer "did my comment land?".
Recovery is therefore available to the Client without anything new being built.

Two reasons to leave it there rather than doing it inside the failed call. The spec's own
sequence has the Client abandoning the call at its deadline and discarding whatever arrives
afterwards — a Server-side check runs *after* that moment, so its answer is the one thing
the spec advises the Client to throw away. And the check would be racing the write it is
checking on: `kill` stops this Server's process, not GitHub's processing, so "not there yet"
does not mean "not written". A Client's verification is a fresh call it chose to make, under
no one's expired deadline.

The counter-argument — that this trusts a model to act on a Remedy — applies to the whole of
ADR-0002 equally. A Client that ignores `CHECK_BEFORE_RETRY` would ignore `FIX_REQUEST` too.

### The contract distinguishes writes from reads, and `GhCli` is where it knows

ADR-0002's contract was uniform: one shape, one `classify()`, no per-Tool variation. That
was right while every Tool was a read and is not right now — the same expired timeout means
"nothing happened, try again" on a read and "something may have happened" on a write.

The distinction lives in `GhCli`, not in the Tools. `GhCli`'s own javadoc says it is "the
only place that knows how `gh` fails, which is why classification lives here rather than in
the Tools"; rewriting the failure in a Tool's `catch` would move half the contract to
exactly where that sentence says it must not go, and every future write Tool would copy it.
Whether `run` takes a parameter or gains a `runWrite` sibling is the implementation's to
choose.

### A fifth Remedy: `CHECK_BEFORE_RETRY`

The action available after an unconfirmed write is: *look whether it landed, then decide.*
None of the four existing constants says that, and each says something wrong:

| | says | on an unconfirmed write |
| --- | --- | --- |
| `RETRY` | call again | **wrong** — that is the duplicate |
| `FIX_REQUEST` | change the arguments | **wrong** — nothing is wrong with them |
| `ASK_OPERATOR` | nothing the caller can change | **wrong** — the caller can check |
| `UNKNOWN` | unrecognised; the stderr is all there is | **wrong** — this is recognised, and the stderr is empty |

So the fifth constant is not precision for its own sake. Without it every unconfirmed write
carries advice that is actively harmful.

`Remedy`'s javadoc forbids adding a constant that names a *cause* — `NOT_FOUND`,
`RATE_LIMITED`. `CHECK_BEFORE_RETRY` names an action, which is the axis this enum does have.
Its javadoc carries both outcomes: if the comment is there, the write succeeded and its
`url` is in hand; if it is not, treat this as `RETRY`.

The cost is real and is recorded rather than argued away: **one Tool can produce it and every
Client has to learn it.** ADR-0002 chose four constants partly to stay teachable, and this is
a 25% increase in that surface for a case only writes reach.

### It covers every write whose result this Server could not read

Not only timeouts. Scoping it to "timeout" would be scoping by cause, which is the axis
ADR-0002 rejects; the three exits below hand the caller an identical action.

| exit | was | becomes (writes only) |
| --- | --- | --- |
| timeout expired | `RETRY` + wait 30 | `CHECK_BEFORE_RETRY` |
| `InterruptedException` | `RETRY`, no wait | `CHECK_BEFORE_RETRY` |
| `ExecutionException` (output unreadable) | `UNKNOWN` | `CHECK_BEFORE_RETRY` |

The third is the worst of the three today. Its process may have run to completion — the
failure is that this Server could not read the bytes — and it reports `UNKNOWN`, whose
javadoc promises the stderr is all there is, while carrying a Java exception string that
tells a Client nothing about the comment it may have just posted.

On a read, all three keep their current classification. Nothing about the read contract
changes here except the wait, below.

### A timeout carries no wait

`retryAfterSeconds` becomes a single-meaning field: *do not retry before this*, populated
only from what `gh` reports about rate limiting.

ADR-0002 line 94 filled it on timeouts with "the budget actually spent" — a number that
looks backwards inside a field that two javadocs (`Remedy.RETRY`, `retryAfterSeconds()`)
document as looking forwards. That is not a documentation slip. A Client obeying the
documented meaning waits the 30 seconds and then retries, so on a write the field was
*scheduling* the duplicate rather than failing to prevent it.

The number it carried had no action in it either: it is a constant the Client learns once.

This changes behaviour for the four read Tools already shipped — their timeouts stop
carrying `retryAfterSeconds: 30`. That is a removal, not an addition, and it is deliberate:
the value was never a legitimate instruction on a read either, only a harmless one.

### The message names `list_issue_comments`

The failure sentence tells the Client how to check, by name:

> The comment could not be confirmed. It may already have been posted. Before writing it
> again, check with `list_issue_comments` whether a comment of yours with this body is
> already on the issue.

This is the first user-facing sentence in this Server that names another Tool — until now,
Tool names appear in failure-path code only as comments. The coupling is real: renaming
`list_issue_comments` silently falsifies this sentence, and nothing at compile time notices.
It is accepted because the entire reason for leaving recovery with the Client is that this
Server provides the means, and a means the Client is not told about is a hope rather than a
contract. The Server has five Tools, their names are fixed in ADRs, and a rename already
requires opening them.

## Known limitations

- **The verification key is only usually right.** "A comment of mine whose body equals what
  I sent" misfires in both directions: a model that legitimately posts identical text twice
  will find the *earlier* one and conclude wrongly that this write landed, and a body
  carrying a timestamp or a nonce can never be found. There is no better key available —
  see the `clientMutationId` result above — and this is the residual risk the whole design
  carries.
- **Detection is after the fact, so a window remains.** A Client that checks immediately can
  see "not there" for a write GitHub has not finished processing, and retrying then still
  duplicates. Nothing in this design closes that; it narrows it.
- **Five constants to teach, one of which one Tool produces.**
- **A Client that ignores Remedies is unaffected by any of this.**
- **The failure sentence is coupled to a Tool name by hand.**

## What ADR-0002 keeps

Classification by action rather than cause is not weakened here — it is the argument for
every step above. The fifth constant is admitted *because* it names an action, and it covers
three exits *because* scoping it to timeouts would have been scoping by cause. `isError`
carrying structured content, the verbatim stderr travelling alongside, `UNKNOWN` as the
floor rather than a confident guess, and one shared conversion in `ToolResults` are all
unchanged.

## Out of scope

- **Preventing the duplicate rather than detecting it.** Measured as unavailable: GitHub has
  no idempotency key here.
- **Server-side recovery**, including the `--edit-last` / `--create-if-none` upsert. It is
  the second half of a mechanism whose first half — knowing the earlier comment is yours and
  is this one — is the hard part, and it is the part this ADR hands to the Client.
- **Retry policy inside the Server.** This Server still makes one attempt per Tool call.
- **What a successful write leaves in this Server's log.** Still fog on the map.

## Amendments

**2026-09-06, after [#35](https://github.com/DemianLi/project_mcp/issues/35).** The last Out
of scope bullet — "What a successful write leaves in this Server's log. Still fog on the
map" — points at [#32](https://github.com/DemianLi/project_mcp/issues/32), which has closed
without taking it up. It ruled the question out of its own scope on audience grounds: the
gate that map was charting is for whoever deploys this Server, a trace is for whoever asks
afterwards. The question is still open and still unspecified; it is simply not on a map any
more. The contract this ADR sets is untouched.

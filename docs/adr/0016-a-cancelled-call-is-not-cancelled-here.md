# 16. A cancelled call is not cancelled here

Date: 2026-09-09

## Status

Accepted. The third in the shape [ADR-0012](0012-no-rate-limiting-and-why.md) started and
[ADR-0014](0014-no-metrics-and-who-would-have-to.md) continued: a thing not built, named,
with an owner. Unlike ADR-0012 this one is not a departure — the specification permits it in
so many words.

## Context

`docs/reviews/commercial-readiness.md` §7.2 asked for the timeout policy to be written down.
Writing it down turned up a second clock nobody had looked at.

**The specification's timeout clause is not about this Server's budget.** `2025-11-25`'s
`basic/lifecycle.mdx`, under *Timeouts*, binds the sender of a request: a sender SHOULD
establish a timeout, and SHOULD issue a cancellation notification when it expires. This
Server is the receiver on `tools/call`. `GhCli.TIMEOUT_SECONDS = 30` is a subprocess budget
the specification never mentions. Reading that clause as an obligation on the budget would
be the same mistake §4.2 made when it cited a document that does not exist under this
revision.

**The clause that does address this Server is `basic/utilities/cancellation.mdx`.** A
receiver of `notifications/cancelled` SHOULD stop processing, free resources, and send no
response — and MAY ignore the notification when the request is unknown, already complete, or
cannot be cancelled.

**Nothing in the stack implements it.** `cancel` does not occur in `mcp-core` 2.0.0,
`mcp-json-jackson3` 2.0.0, `spring-ai-mcp` 2.0.1 or
`spring-ai-autoconfigure-mcp-server-common` 2.0.1, and `McpSchema`'s table of method
constants — which has one for every notification the SDK does handle — has no
`notifications/cancelled`. The notification is not a type the SDK knows.

**What happens instead, measured rather than read.** A cancellation sent at a real Server
while its `gh` was still running reaches `McpServerSession.handleIncomingNotification`,
finds no handler, and takes this branch:

```java
var handler = notificationHandlers.get(notification.method());
if (handler == null) {
    logger.warn("No handler registered for notification method: {}", notification);
    return Mono.empty();
}
```

One `WARN` in the log file, no reply, and nothing on stdout — the protocol stream stayed
clean. The `gh` ran to completion and its response was written 20.36 seconds after the
request, correctly tagged with its own id, after a later request that had already been
answered.

## Decision

**This Server does not implement cancellation.** A cancellation notification is ignored,
which is one of the three cases the specification explicitly permits a receiver to ignore.

Implementing it would take more than a handler. Stopping the work means killing the `gh`
subprocess that belongs to the cancelled request, which needs a map from the request's id to
that `Process` — and **the SDK does not give a Tool method its request id**. The exchange
carries `sessionId()` and `loggingNotification()`; `requestId` does not occur anywhere in
the server package. Building that map means reading the JSON-RPC frames before the SDK does,
which puts protocol parsing inside a Server whose whole premise is that the SDK owns the
protocol and this code owns `gh`.

The budget bounds the cost of not doing it. An abandoned call runs at most 30 seconds more
per `gh` invocation — twice that for `add_issue_comment`, which makes two —
and `GhCli.kill(Process)` disposes of the subprocess and its descendants when it does
expire. There is no unbounded work to stop.

## Consequences

**Work continues after the Client stops waiting.** Up to the 30-second budget per `gh`
invocation, or twice that for `add_issue_comment`. On a read that is wasted GitHub quota. On
a write it is worse in a way the caller cannot see: the comment may land after the Client
gave up on it, which is the same "may already exist" the write contract is built around
([ADR-0008](0008-failure-contract-for-writes.md)) arriving through the Client's own clock
instead of this Server's.

**A response arrives for a request nobody is waiting for.** Harmless under the
specification, which tells the sender to ignore a response that arrives after its
cancellation, and measured not to disturb the stream: responses leave in completion order
with their ids, and the abandoned one did not interleave into the middle of another message.

**The Client's own text lands in the log.** The `WARN` above prints the whole notification,
including the `reason` string the Client wrote. [ADR-0013](0013-what-a-call-leaves-behind.md)
draws its line around *GitHub's* content; this is the Client's, and it is on the far side of
that line. Named there too, so a reader of either document finds it.

**One thing this is not.** Ignoring cancellation is not why the process survived an
`OutOfMemoryError` in ADR-0015, and fixing it would not have helped there. Different
failure, different door.

## Who has to

Whoever needs a cancelled call to stop costing something — a deployment paying per GitHub
call, or one where a write landing after abandonment is unacceptable.

The place for it is not `GhCli`. `GhCli` sees a subprocess and not a request, exactly as it
sees a subprocess and not a Tool invocation in ADR-0014. It belongs at the SDK boundary: a
notification handler registered for `notifications/cancelled`, plus whatever gives a running
Tool call its request id — today that means either an SDK version that exposes one, or a
transport wrapper that reads the id before handing the frame on.

## Out of scope

**Task-augmented requests.** The 2025-11-25 revision routes those through `tasks/cancel`
rather than this notification, and this Server implements no tasks.

**The Client's timeout value.** How long a Client waits is the Client's decision. What this
Server owes it is the number to base that on, which is in
[docs/deploying.md](../deploying.md) under *Two clocks*: one `gh` invocation per Tool call
except `add_issue_comment`, which makes two.

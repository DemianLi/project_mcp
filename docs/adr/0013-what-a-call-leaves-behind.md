# 13. What a call leaves behind

Date: 2026-09-09

## Status

Accepted. Amends the argv clause of
[ADR-0002](0002-failure-contract-for-gh-calls.md), and settles the question
[ADR-0007](0007-add-issue-comment-parameters-return-and-annotations.md) left open under
Known limitations ("whether that is right for a write is flagged on the map as not yet
specified"). Extended by [ADR-0014](0014-no-metrics-and-who-would-have-to.md), which
records what this Server does *not* measure and why.

## Context

Until now this Server's entire observable surface was one statement: `GhCli` logged the
argv of a call that failed, at `WARN`, to a file. A call that succeeded left nothing at
all — not a line, not a count. The file could answer *what went wrong when something did*
and could not answer *was this Server used*, *how often*, *against what*, or *how long it
took*.

The shape this Server is deployed in decides what can be built on top of that. It speaks
stdio: the Client starts it as a subprocess, and it dies when the Client closes the pipe.
There is no port, nothing to scrape, no address a collector could reach, and no long-lived
process to hold a counter. One process is one desk.

So the honest ceiling is the file. Everything below is about making that file worth
reading.

## Decision

### One line per call, from the one entry

`ToolResults.attempt` already existed as the single seam every Tool's work passes through
— that is how the failure contract became Server-wide rather than five copies of a shape.
The trace is the same argument applied a second time: one entry means one line per call,
in one shape, without five Tools each deciding what is worth recording.

Its signature gains the call's identity:

```java
static CallToolResult attempt(String tool, String owner, String repo, Supplier<?> body)
```

Every call writes exactly one `INFO` line, whichever way it goes. `INFO` and not `DEBUG`:
this file is the only record this process keeps, and `DEBUG` does not reach it under any
setting this Server ships.

### What that line carries

Seven fields, beside what Elastic Common Schema contributes (`@timestamp`, `log.level`,
`log.logger`, `process.pid`, `process.thread.name`, `service.name`, `message`):

| Field | Present | What it is for |
| --- | --- | --- |
| `tool` | always | which Tool ran |
| `callId` | always | joins the lines one call produces |
| `repo` | always | `owner/repo`, the target |
| `outcome` | always | `ok` or `error` |
| `durationMs` | always | the only latency figure that exists here |
| `resultBytes` | success | what the Client actually received |
| `remedy` | failure | the classification, never the detail |

`repo` is a judgement and is recorded as one. It is an argument the Client sent, not text
GitHub returned, and without it the file cannot answer which repositories this Server has
touched — which is most of the reason to keep it. `number` is shape by the same reading and
is deliberately *not* a field: the write line's `url` already carries it, and no read Tool's
diagnosis has yet needed it. Its absence is a decision, not an oversight.

### Shape, never content

The line records the **shape** of a call — which Tool, which repository, how long, how
big, how it ended. It never records its **content**: no issue title, no issue body, no
comment text, no label name.

This is a boundary rather than a convention. A log that mirrors GitHub's content is a copy
of that content on a disk outside the protocol, that nothing in this Server ever deletes,
and whose access controls are the filesystem's rather than GitHub's. It is a disclosure
surface that has nothing to do with MCP and would be created entirely by accident.

**It was already broken when this was written.** `GhCli` logged the argv verbatim, and
`add_issue_comment`'s argv ends in `-f body=<the whole comment>`. The failure had to land
on the *mutation* rather than the lookup for it to show, which is why five Tools' worth of
green tests never saw it. `GhCli.argv()` now elides the values of the variables named in
`CONTENT_VARIABLES` — one entry, `body` — and keeps the length, because a body of 0
characters and one of 60000 fail for different reasons and neither reason is legible from
the text.

### The write leaves its own line

`add_issue_comment` emits a second line, `comment written`, carrying `commentUrl` — the
permalink, which identifies owner, repository, issue and comment in one field and contains
no part of what was written.

It is emitted by `CommentTools`, at the site, and not by `ToolResults`. The shared entry
takes a `Supplier<?>`; teaching it to recognise `NewComment` would open a branch that every
future write Tool adds to, and would make the one class whose value is that it knows no
Tool know one. The two lines cohere because `tool`, `callId` and `repo` are in the MDC
around the lambda — which is also why `GhCli`'s argv warning arrives tagged with the call
it belongs to, without `GhCli` being handed anything.

### ECS, in a file, going nowhere

`logging.structured.format.file: ecs`. Spring Boot 4.1.1 builds it; no
`logstash-logback-encoder`, no `logback-spring.xml`. ECS over Boot's other two formats
because its field names are a published schema, so a collector that appears later reads
this file without a translation layer.

Nothing ships it anywhere. There is no agent, no exporter, no endpoint. Whoever deploys
this past one desk owns that, and the file is shaped so they can.

### The `logging` capability stays declared and unused

The MCP `logging` capability is advertised in `InitializeResult` and never used — no code
path in `src/` emits `notifications/message`.

The alternative to leaving it on is not "turn it off" but "start using it", and using it
means sending this Server's log to the Client, where it lands is the Client's choice, and
one of the choices is the model's context. That is pollution wearing observability's
clothes, and the reader this file is for is a person at a terminal. The direction of the
specification agrees: 2026-07-28 deprecates MCP Logging and points stdio servers at
`stderr` instead.

Turning it off would cost an `McpSyncServerCustomizer` replacing the whole capability set,
and a deliberate red line in `SdkBoundaryAcceptanceTest`, to buy one absent empty object.

## Consequences

### A call the schema rejects leaves no line at all

This is the most surprising thing in the contract and it follows from
[ADR-0011](0011-the-failure-contract-begins-at-the-tool-method.md). The SDK validates a
Tool's input *before* dispatch, builds its own `CallToolResult`, and returns it. Nothing
reaches `ToolResults`, so nothing is recorded — no `tool`, no `outcome`, no line.

A Client sending malformed calls all day produces a log file that says the Server was idle.
`durationMs` measures the Tool method body, not the request, for the same reason. This is
the second consequence of ADR-0011's boundary; the first was the missing `structuredContent`.

### Amended by ADR-0015: a call ends three ways, not two

[ADR-0015](0015-a-ceiling-on-one-response.md) adds a third value to `outcome`. A call now
reports `ok`, `error`, or **`fatal`** — the last when an `Error` escapes the Tool's work and
the process halts rather than staying up and mute. A reader filtering on `error` will not
see those.

That branch also removes one entry from the list below: a fatal `Error` used to leave the
file with nothing but Reactor's stack trace, and now leaves a line naming the call. The
schema-rejected call remains the only ending that leaves nothing at all.

### `stderr` is a route this contract does not close

`GhCli` logs `gh`'s stderr verbatim, and this Server does not write it. If GitHub ever
answers a rejected write by quoting the body back, content reaches the file by a route the
redaction does not touch.

Eliding stderr instead would leave a failure with nothing to diagnose it by — that line is
the only place the reason survives, since ADR-0002 keeps the argv out of the caller's
payload. So the claim this Server makes is narrower than "no content in the file": it is
*no content this Server puts there*. Recorded rather than left to be discovered, and not
provoked against the real endpoint, because no rejection observed so far has echoed a body.

### The Client's own text arrives by a second route

The section above narrows the claim to *GitHub's* content. There is a second author whose
text reaches the file, found while writing
[ADR-0016](0016-a-cancelled-call-is-not-cancelled-here.md): the Client's.

`notifications/cancelled` is not implemented anywhere in this stack, so the SDK takes its
unknown-notification branch and logs the whole notification — including the free-text
`reason` the Client wrote:

```
No handler registered for notification method: JSONRPCNotification[jsonrpc=2.0,
  method=notifications/cancelled, params={requestId=101, reason=probe gave up}]
```

Measured against a running Server, not deduced. It is the SDK's line rather than this
Server's, and the boundary this ADR draws was never about the caller's own words — but a
reader who takes "shape, never content" as covering the whole file would be wrong twice, not
once, and the second one is worth the same paragraph as the first.

### `durationMs` and `resultBytes` read back as strings

The MDC holds strings and has no other type, so `"durationMs":"12"`, not `12`. A `jq`
filter needs `tonumber`. Emitting real numbers means a
`StructuredLoggingJsonMembersCustomizer`; the cost was judged higher than the `tonumber`.

### The file grows differently now

A line per call instead of a line per failure. `application.yml` now states the rollover
policy rather than inheriting it, and adds `total-size-cap: 100MB` — Boot's default is
unlimited, and nothing here deletes this directory: not the Client that starts the process,
not the process itself.

### The trace is pinned, and the boundary more than the format

`TraceContractAcceptanceTest` drives the Server at the wire and asserts both. The second
assertion is the one that matters: a field recorded wrongly inconveniences a reader, and a
body recorded at all is a disclosure. It was confirmed to fail by putting the leak back.

It needed one thing no other Acceptance test needs: `target/test-classes` off the child's
classpath. `logback-test.xml` silences the suite's deliberate failures, and Logback having a
configuration of its own means Spring Boot never installs the file appender at all — so
every acceptance test until now ran against a Server that logged nothing, and could not have
noticed.

## Alternatives considered

**Log only writes.** Coherent with ADR-0009 — a gated action deserves an audit — and it
would produce a file that cannot answer whether the four read Tools were ever called, which
is the first question anyone asks of a Server that seems idle.

**Emit from an interceptor.** There is none. MCP Java SDK 2.0.0 ships no interceptor or
filter type, and Spring AI exposes no hook for a Tool call; the framework route means
wrapping `ToolCallback` registration by hand. An AOP aspect means
`spring-boot-starter-aop` and a proxy, for one line.

**Correlate on the MCP request id.** Not reachable. `McpSyncServerExchange` exposes
`sessionId()` and no per-request id — `requestId` does not appear anywhere in the SDK's
`server` package. `sessionId()` is available by declaring an `McpSyncServerExchange`
parameter, which the schema generator excludes from the Client-facing schema, but under one
process per desk it is close to a constant. The self-generated `callId` solves the real
problem, which is that a failing write emits up to three lines and a pooled dispatcher does
not keep them adjacent.

**Two appenders, text for a person and JSON for a machine.** The same data written twice,
two ways to drift, and a `logback-spring.xml` to maintain — to avoid `jq`.

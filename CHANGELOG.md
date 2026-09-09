# Changelog

Notable changes to this Server. The reasoning behind them lives in
[docs/adr/](docs/adr/) — this file records what changed and what it is compatible with,
not why.

## 0.1.0 — 2026-09-09

First release. Five Tools over stdio, driven end to end against real GitHub.

### Tools

| Tool | Reads or writes | Route |
| --- | --- | --- |
| `list_issues` | reads | `gh issue list` |
| `get_issue` | reads | `gh issue view` |
| `list_labels` | reads | `gh label list` |
| `list_issue_comments` | reads | `gh api graphql`, paged by cursor |
| `add_issue_comment` | **writes** | `gh api graphql`, two calls |

Pull requests are refused rather than half-answered: GitHub numbers issues and pull
requests from one sequence, and a pull request number is turned away
([ADR-0003](docs/adr/0003-get-issue-parameters-and-return-shape.md)). There are no
Resources, and that is a result rather than a gap — a Resource read has no way to report a
failure ([ADR-0004](docs/adr/0004-list-labels-tool-shape-parameters-and-return.md)).

### Failure contract

Every Tool call returns either a result or a failure carrying one of five Remedies —
`RETRY`, `CHECK_BEFORE_RETRY`, `FIX_REQUEST`, `ASK_OPERATOR`, `UNKNOWN` — classified by the
action available to the caller rather than by the cause
([ADR-0002](docs/adr/0002-failure-contract-for-gh-calls.md)). A write that could not be
confirmed carries `CHECK_BEFORE_RETRY` rather than advice to call again, because GitHub
offers no idempotency key ([ADR-0008](docs/adr/0008-failure-contract-for-writes.md)).

### Bounds

| Bound | Value | Where |
| --- | --- | --- |
| One `gh` invocation | 30 s (`add_issue_comment` makes two, so ~60 s per call) | `GhCli.TIMEOUT_SECONDS` |
| One `gh` response | 8 MB, refused above with `FIX_REQUEST` | `GhCli.MAX_RESPONSE_BYTES` |
| `limit` on a list | default 30, clamped to 1–100 | `Limits` |

All three are constants with an ADR behind them rather than configuration
([ADR-0015](docs/adr/0015-a-ceiling-on-one-response.md),
[docs/deploying.md](docs/deploying.md)).

### Observability

One JSON line per Tool call in `logs/project-mcp.log` — which Tool, which repository, how
long, how it ended, how many bytes — and a second line with the permalink when a comment is
written. No content of any kind on the paths this Server controls: no issue body, no comment
text, no label name ([ADR-0013](docs/adr/0013-what-a-call-leaves-behind.md)). Nothing ships
the file anywhere; it rotates at 10 MB with a 100 MB cap.

### Deployment

A [`Dockerfile`](Dockerfile) that was built and driven before it was committed, and
[docs/deploying.md](docs/deploying.md) for what a deployment has to provide and decide.

## Compatibility

| | |
| --- | --- |
| Protocol revision | **2025-11-25** — the ceiling of the SDK this release depends on |
| Transport | stdio only; Streamable HTTP is not implemented |
| Java | 25 |
| Spring Boot | 4.1.1 |
| Spring AI | 2.0.1 (`spring-ai-starter-mcp-server`) |
| MCP Java SDK | 2.0.0 |
| `gh` | measured against **2.91.0** on the host and **2.100.0** in the container image |

**The `gh` row is the one that can bite.** `GhStderr.classify()` recognises `gh`'s failures
by matching the wording of its stderr. A `gh` release that rephrases a message does not
break this Server loudly — the marker stops matching, the failure lands in `UNKNOWN`, and
the caller gets a correct response with no recovery advice in it. That risk is accepted by
design and recorded in
[ADR-0002](docs/adr/0002-failure-contract-for-gh-calls.md); the versions above are the ones
it has actually been exercised against.

Moving to protocol revision 2026-07-28 waits on the MCP Java SDK, not on this repository.

## Known departures

Named because a departure without an owner is an oversight with better prose.

- **No rate limiting.** A knowing departure from a `MUST` in the 2025-11-25 Security
  Considerations, with the owner named
  ([ADR-0012](docs/adr/0012-no-rate-limiting-and-why.md)). GitHub's own rate limiting comes
  back as a `RETRY`, which is not the same thing.
- **No metrics and no health endpoint.** Under stdio there is nowhere to put one
  ([ADR-0014](docs/adr/0014-no-metrics-and-who-would-have-to.md)).
- **Cancellation is ignored.** `notifications/cancelled` is not implemented anywhere in the
  stack; the specification permits a receiver to ignore it, and the cost is a `gh` that runs
  to its own budget after the Client has gone
  ([ADR-0016](docs/adr/0016-a-cancelled-call-is-not-cancelled-here.md)).
- **One process is one identity.** Every call uses the same resolved `gh` login. There is no
  per-caller identity and no read-only switch; multi-tenant means one process per tenant
  ([ADR-0009](docs/adr/0009-writes-are-gated-outside-this-server.md)).
- **GitHub's content is not sanitised.** Issue bodies and comments cross the wire as GitHub
  returned them; a Client that renders them owns that.

## Not measured yet

Stated so that the untested is not mistaken for the tested: the rate-limit branch of
`GhStderr.classify()` has never been provoked against real GitHub, and concurrency has only
been measured for head-of-line blocking on a single session — not under load. See
`docs/reviews/commercial-readiness.md`.

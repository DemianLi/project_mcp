# Changelog

## 1.0.0 — 2026-09-15

First stable release. From this version on, changes to the public contract described in
the [README](README.md#stability) follow semantic versioning.

### Tools

Five, exposed over stdio: four that read — `list_issues`, `get_issue`, `list_labels`,
`list_issue_comments` — and one that writes, `add_issue_comment`. Pull request numbers are
refused, and there are no Resources. Parameters and result shapes are in
[docs/design.md](docs/design.md#tools).

### Failure contract

Every failure is a Tool result with `isError: true` carrying one of five Remedies —
`RETRY`, `CHECK_BEFORE_RETRY`, `FIX_REQUEST`, `ASK_OPERATOR`, `UNKNOWN` — chosen by what
the caller can do next rather than by the cause. A write that could not be confirmed
carries `CHECK_BEFORE_RETRY`, because GitHub offers no idempotency key
([docs/design.md](docs/design.md#failure-contract)).

### Bounds

| Bound | Value |
| --- | --- |
| One `gh` invocation | 30 s (`add_issue_comment` makes two, so ~60 s per call) |
| One `gh` response | 8 MB, refused above with `FIX_REQUEST` |
| `limit` on a list | default 30, clamped to 1–100 |
| Writes | 80 per minute and 500 per hour per process, refused above with `RETRY` and `retryAfterSeconds` |

All are constants rather than configuration ([docs/design.md](docs/design.md#bounds)).

### Observability

One JSON line per Tool call in `logs/project-mcp.log` — which Tool, which repository, how
long, how it ended, how many bytes — and a second line with the permalink when a comment is
written. No issue, comment or label content is logged. The file rotates at 10 MB with a
100 MB cap ([docs/design.md](docs/design.md#logging)).

### Deployment

A [`Dockerfile`](Dockerfile), and [docs/deploying.md](docs/deploying.md) for what a
deployment has to provide and decide.

## Compatibility

| | |
| --- | --- |
| Protocol revision | **2025-11-25**, except that reads are not rate limited (see below) |
| Transport | stdio only |
| Java | 25 |
| Spring Boot | 4.1.1 |
| Spring AI | 2.0.1 (`spring-ai-starter-mcp-server`) |
| MCP Java SDK | 2.0.0 |
| `gh` | tested with 2.91.0 and 2.100.0 |

`gh` failures are recognised by the wording of its stderr. A `gh` release that rephrases a
message does not break the Server; the failure is reported as `UNKNOWN`, without recovery
advice.

Protocol revision 2026-07-28 needs a newer MCP Java SDK than this release depends on.

## Known departures

- **Reads are not rate limited.** The 2025-11-25 specification says servers MUST rate limit
  tool invocations; only writes are limited here, because a read's cost to GitHub depends
  on the query and there is no published per-call number to apply.
- **GitHub content is not sanitised.** Issue bodies and comments cross the wire as GitHub
  returned them; a Client must treat them as untrusted.
- **Cancellation is ignored.** The specification permits a receiver to ignore
  `notifications/cancelled`; a `gh` already running finishes within its own 30 s budget.
- **No metrics and no health endpoint.** Under stdio there is nowhere to put one.
- **One process is one identity.** Every call uses the same `gh` login; multi-tenant means
  one process per tenant.

# project_mcp

An [MCP](https://modelcontextprotocol.io) server that exposes **GitHub issues and labels**
as Tools. It wraps the `gh` CLI: a Client gets five declared operations with typed inputs
and never needs shell access. It wraps `gh`, not `git` — local version control is out of
scope.

## Architecture

```
+----------------------------------+                +----------------------------------+
|           MCP Client             |                |          project_mcp             |
|  (e.g. the MCP Inspector, or an  |      stdio     |     (Spring Boot + Spring AI)    |
|   AI application's host)         | <------------> |                                  |
|                                  |     JSON-RPC   |  - Declares five Tools           |
|  - Starts the Server as a        |                |  - Shells out to the `gh` CLI    |
|    subprocess                    |                |  - Logs to a file, never stdout  |
+----------------------------------+                +----------------------------------+
```

The Server is launched _by_ the Client as a subprocess, which inverts the usual web meaning
of "server". [CONTEXT.md](./CONTEXT.md) defines each role precisely.

**Stack:** Maven, Java 25, Spring Boot 4.1, Spring AI 2.0 (`spring-ai-starter-mcp-server`),
MCP Java SDK 2.0. **Protocol revision:** 2025-11-25. **Transport:** stdio only.

## Tools

| Tool | Kind | What it does |
| --- | --- | --- |
| `list_issues` | read | Lists a repository's issues, newest first, filtered by state |
| `get_issue` | read | One issue in full, including its body; a pull request number is rejected |
| `list_labels` | read | A repository's labels |
| `list_issue_comments` | read | One issue's comments, newest first, paged with a cursor |
| `add_issue_comment` | write | Adds a comment to an issue and returns its permalink |

Pull requests are not served: GitHub numbers issues and pull requests from one sequence,
and a pull request number is refused rather than half-answered. There are no Resources,
because reading a Resource has no way to report a failure. Parameters and result shapes are
in [docs/design.md](./docs/design.md#tools).

## When a call fails

Every failure comes back as a Tool result with `isError: true`, never as a JSON-RPC error,
so the model sees it. Its `structuredContent` carries a **Remedy** — what the caller should
do next:

| Remedy | Meaning |
| --- | --- |
| `RETRY` | Try the same call again, after `retryAfterSeconds` if present |
| `CHECK_BEFORE_RETRY` | A write whose result could not be read: check whether it landed first |
| `FIX_REQUEST` | The call cannot succeed as written; change the arguments |
| `ASK_OPERATOR` | Nothing the caller can change; a person has to fix the environment |
| `UNKNOWN` | An unrecognised failure; `stderr` is all there is to go on |

See [docs/design.md](./docs/design.md#failure-contract).

## Bounds

| Bound | Value |
| --- | --- |
| One `gh` invocation | 30 s (`add_issue_comment` makes two, so up to ~60 s per call) |
| One `gh` response | 8 MB; larger is refused with `FIX_REQUEST` |
| `limit` on a list | default 30, clamped to 1–100 |
| Writes (`add_issue_comment`) | 80 per minute and 500 per hour per process, GitHub's published limit for content-generating requests; over it, `RETRY` with `retryAfterSeconds` |

## Security notes

- **GitHub content is returned verbatim.** Issue bodies, comments and label descriptions
  are written by whoever can write to the repository, and reach the model unchanged. Text
  crafted as instructions (prompt injection) arrives with them. A Client must treat Tool
  output as untrusted data, not as instructions.
- **One process is one identity.** Authentication is `gh`'s: every call uses the login `gh`
  resolves, and the Server never holds a token. What a Client can write is whatever that
  login can write. A read-only deployment means pointing `gh` at a login without write
  access; a multi-tenant deployment means one process per tenant.
- **Reads are not rate limited.** Only writes are. A read's cost to GitHub depends on the
  query, so there is no published per-call number to apply; GitHub's own limits still apply
  and come back as `RETRY`.

## Stability

From 1.0.0 this Server follows [semantic versioning](https://semver.org). The public
contract is:

- the five Tool names and their input schemas
- the shape of each successful result
- the five Remedy values, and that every failure is reported with `isError: true`

Not part of the contract: the human-readable sentences, the `stderr` text passed through
from `gh`, and the log line format.

## Building and running

Requires JDK 25, Maven, and a `gh` on `PATH` that is logged in.

```bash
mvn package
java -jar target/project-mcp-1.0.0.jar
```

**Do not use `mvn spring-boot:run`.** Maven writes its own output to stdout before the
application starts, and a Client will try to parse it as JSON-RPC and fail. Always run the
packaged jar.

Logs go to `logs/project-mcp.log` — one JSON line per Tool call, never the content of an
issue or comment — and never to the console, because stdout is the protocol channel.

## Documentation

- [docs/design.md](./docs/design.md) — how the Server works: each Tool's parameters and
  result shape, the failure contract, bounds, logging, and the known departures from the
  specification
- [docs/deploying.md](./docs/deploying.md) — what a deployment has to provide and decide.
  The [`Dockerfile`](./Dockerfile) beside it meets every condition in it
- [docs/architecture-tour.html](./docs/architecture-tour.html) — a plain-language tour of
  the whole Server on one page
- [docs/mcp-2025-11-25-conformance.html](./docs/mcp-2025-11-25-conformance.html) — what
  the 2025-11-25 specification requires of a stdio server, and where each requirement is met
- [docs/mcp-2025-11-25-commercial-primer.html](./docs/mcp-2025-11-25-commercial-primer.html)
  — the same ground for someone learning to build one, with the professional terms beside
  each clause
- [docs/mcp-client-server-dataflow.html](./docs/mcp-client-server-dataflow.html) — the
  conversation between a Client and this Server in four pictures, each term with a note
  saying where to read up on it
- [docs/mcp-2026-07-28-dataflow.html](./docs/mcp-2026-07-28-dataflow.html) — the same four
  pictures for the 2026-07-28 revision, and which of its requirements this Server does not
  meet yet
- [CONTEXT.md](./CONTEXT.md) — glossary: Server, Client, Tool, Resource, Remedy and the
  transports
- [CHANGELOG.md](./CHANGELOG.md) — what this release contains and what it is compatible with

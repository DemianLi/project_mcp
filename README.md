# project_mcp

[![CI](https://github.com/DemianLi/project_mcp/actions/workflows/ci.yml/badge.svg?branch=develop)](https://github.com/DemianLi/project_mcp/actions/workflows/ci.yml?query=branch%3Adevelop)

An [MCP](https://modelcontextprotocol.io) server that exposes **GitHub platform
operations** — issues and labels — as Tools. Pull requests are deliberately not among
them: GitHub numbers issues and pull requests from one sequence, and this Server rejects
a pull request number rather than answering with half a pull request
([ADR-0003](./docs/adr/0003-get-issue-parameters-and-return-shape.md)). Think of it as a guardrailed,
typed subset of the `gh` CLI: callers get a declared set of operations with typed
inputs, and never need shell access. The official MCP Inspector acts as its test Client
during development.

It wraps `gh`, not `git` — local version control is deliberately out of scope.

## Architecture

```
+----------------------------------+                +----------------------------------+
|          MCP Inspector           |                |        Your Java Server          |
|      (official test Client)      |      Stdio     |     (Spring Boot + Spring AI)    |
|                                  | <------------> |                                  |
|  - Web / terminal UI             |     JSON-RPC   |  - Shells out to the `gh` CLI    |
|  - One-click Tool invocation     |                |  - Declares Tools, no Resources  |
+----------------------------------+                +----------------------------------+
```

The roles above are defined precisely in [CONTEXT.md](./CONTEXT.md). One thing worth
flagging up front: the Server is launched _by_ the Client as a subprocess, which inverts
the usual web meaning of "server".

## Stack

Maven, Java 25, Spring Boot 4.1.x, Spring AI 2.0.x
(`org.springframework.ai:spring-ai-starter-mcp-server`).

Authentication is `gh`'s problem, not this Server's — it shells out to the `gh` binary
and inherits whatever login that CLI resolves. The Server never holds a token.

Permission travels the same pipe, and stays there. There is no read-only switch to throw:
what an instance can write is whatever that login can write, and this Server's part is to
report clearly when GitHub refuses. Handing out a read-only instance therefore means
pointing `gh` at a login without the permission — a choice made outside this Server. See
[ADR-0009](./docs/adr/0009-writes-are-gated-outside-this-server.md).

Two consequences that were measured rather than reasoned about, and that a deployer needs
before an operator does.

**One process is one identity.** Every call this Server makes uses the same resolved
login, so two people sharing one instance share one set of permissions and one view of
which repositories exist. That is a simplification worth having on one desk and a wall in
front of multi-tenant deployment: the answer there is one process per tenant, not one
process with a switch. `docs/reviews/commercial-readiness.md` §7.1 costs it out.

**A locked issue does not stop it.** Driving the Server against a locked issue with an
owner's login posted the comment, because GitHub's lock refuses people without write
access and the login had it. Locking is not an access control this Server can be leaned
on to respect — the login is the only gate there is, which is ADR-0009 seen from the other
side.

**It writes a file, and the file is not empty.** Every Tool call leaves one JSON line in
`logs/project-mcp.log` — which Tool, which repository, how long, how it ended — and a write
leaves a second one with the comment's permalink. What the line never carries is content:
no issue body, no comment text, no label name, on any path this Server controls. That is a
boundary rather than a habit, and
[ADR-0013](./docs/adr/0013-what-a-call-leaves-behind.md) explains why, including the leak it
was written after finding. Nothing ships the file anywhere and nothing deletes it beyond a
100 MB cap, so a deployment that wants these lines collected owns that part.

**One response has a ceiling, and running out of memory is fatal on purpose.** No single
`gh` response over 8 MB is carried — over that the call is refused with a Remedy rather than
delivered or crashed on, and ordinary traffic never meets it (the largest shape GitHub
produces here measured 6.57 MB). If the heap does run out anyway, the Server writes the line
naming the call and then stops: measured, a Server that survives its own
`OutOfMemoryError` answers nothing and outlives the Client that started it, and under stdio a
dead Server is one a Client can restart.
[ADR-0015](./docs/adr/0015-a-ceiling-on-one-response.md) has the numbers.

**One write, and it is deliberate.** `add_issue_comment` is the only Tool that changes
anything on GitHub; everything else reads. Deletion, editing an existing comment, creating
issues and anything on the pull-request side are all out of scope — see
[ADR-0007](./docs/adr/0007-add-issue-comment-parameters-return-and-annotations.md).

## Transport

**Stdio only.** If a second transport is ever needed it will be Streamable HTTP;
SSE is its deprecated predecessor and is not a target.

## Building and running

Requires a JDK 25 and Maven.

```bash
mvn package
java -jar target/project-mcp-0.1.1.jar
```

**Do not use `mvn spring-boot:run`.** Maven writes its own build output to stdout
before the application starts, and an MCP client will try to parse that as JSON-RPC
and fail. Always run the packaged jar.

Logs go to `logs/project-mcp.log`, never to the console — see `application.yml`.

## Status

Five Tools. Four read — **`list_issues`**, **`get_issue`**, **`list_labels`** and
**`list_issue_comments`** — and one writes: **`add_issue_comment`**. Each has been driven
end to end against real GitHub; the write's side effects land in a sandbox repository kept
for the purpose.

`list_issue_comments` was the first to reach GitHub over `gh api graphql` rather than
porcelain, and the first whose Envelope carries more than `items` / `count` / `truncated` —
it pages, because an issue's comments are the one list here with no narrowing parameter to
fall back on. See [ADR-0005](./docs/adr/0005-comments-are-read-over-graphql.md) and
[ADR-0006](./docs/adr/0006-list-issue-comments-parameters-and-return-shape.md).

`add_issue_comment` takes the same route, and not for consistency: resolving the issue with
`repository.issue(number:)` is what makes writing into a pull request impossible, where both
porcelain and REST were measured doing it happily. A write that cannot be confirmed —
a timeout, an interrupt, an unreadable pipe — carries `CHECK_BEFORE_RETRY` rather than
advice to call again, because GitHub offers no idempotency key and retrying is how the
duplicate gets written. See
[ADR-0007](./docs/adr/0007-add-issue-comment-parameters-return-and-annotations.md) and
[ADR-0008](./docs/adr/0008-failure-contract-for-writes.md).

**Zero Resources, and that is a result rather than a gap.** Fetching an MCP Resource has
no way to report a failure — `ReadResourceResult` carries no `isError` — so a failure
would have to travel as a protocol error the model never sees, or be disguised as ordinary
content. Every read operation here is therefore a Tool. See
[ADR-0004](./docs/adr/0004-list-labels-tool-shape-parameters-and-return.md).

Work is charted on wayfinder maps in this repo's issues. Six are complete:
[#1](https://github.com/DemianLi/project_mcp/issues/1) — a working Stdio server and
`list_issues`; [#7](https://github.com/DemianLi/project_mcp/issues/7) — the failure
contract and an offline test suite that proves it;
[#12](https://github.com/DemianLi/project_mcp/issues/12) — `get_issue` and `list_labels`;
[#19](https://github.com/DemianLi/project_mcp/issues/19) — `list_issue_comments`, which
settles the debt `get_issue` left when it excluded comments;
[#24](https://github.com/DemianLi/project_mcp/issues/24) — `add_issue_comment`, the first
Tool here that changes anything; and
[#32](https://github.com/DemianLi/project_mcp/issues/32) — whether this Server should gate
that write itself, which it decided against.

## Documentation

- [docs/architecture-tour.html](./docs/architecture-tour.html) — a plain-language tour of
  the whole Server on one page: what it sits between, the five Tools, the single pipe out,
  the five things it says when it breaks, and where it is actually wrong. A snapshot — its
  line counts are measured, and pinned to the commit named in its footer
- [docs/mcp-2025-11-25-conformance.html](./docs/mcp-2025-11-25-conformance.html) — which
  revision of the MCP specification this Server answers to, what that revision requires of
  a stdio server, and a layered diagram of where each requirement is met. Five checklists,
  every row given a verdict — including the one outright gap, and the reason behind each
  deliberate departure
- [docs/mcp-2025-11-25-commercial-primer.html](./docs/mcp-2025-11-25-commercial-primer.html) —
  the same ground pitched at someone learning to build one: eleven clauses in two parts,
  six the specification states and five it never mentions but a customer asks about, each
  with the professional terms beside it. Written from this Server, including the places it
  is measurably not clean
- [docs/deploying.md](./docs/deploying.md) — what a deployment has to provide and what it
  has to decide: the identity every call shares, where `gh` looks for a credential and why
  rotating it needs a restart in one arrangement and not the other, the `gh` output that
  reaches a model when a call fails, and the JVM flags without which a Server that runs out
  of memory stays up and mute. It also separates the two clocks a deployment has to set —
  the Client's request deadline, which the specification talks about, and this Server's
  30-second `gh` budget, which it does not — with the measured round trips behind that
  number and the one Tool whose worst case is twice it. The [`Dockerfile`](./Dockerfile)
  beside it satisfies every condition in it, and was built and driven before it was
  committed
- [docs/measurements/](./docs/measurements/) — the two scripts behind the numbers in
  `deploying.md`: what one Tool call costs against real GitHub, and what happens to a call
  the Client stopped waiting for. Primary sources rather than tests — they assert nothing,
  they print what they saw
- [CHANGELOG.md](./CHANGELOG.md) — what each release contains, what it is compatible with
  (down to the `gh` versions it has been exercised against), and the departures it ships with
- [CONTEXT.md](./CONTEXT.md) — domain glossary: the precise meaning of Server, Client,
  Inspector, Tool, Resource and the transports
- [AGENTS.md](./AGENTS.md) — branch strategy and the configuration AI agents read
- [docs/adr/](./docs/adr/) — the architecture decision records. Every Tool's parameters,
  return shape and known limitations are settled there, along with the failure contract
  every Tool call carries

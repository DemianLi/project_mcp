# project_mcp

An MCP server that exposes GitHub platform operations as Tools. It is written in Java
on Spring Boot, and during development is exercised by the MCP Inspector acting as its
client.

## Language

### Domain

**GitHub operation**:
An action against the GitHub platform — issues, pull requests, labels, reviews. This is
what the Server's Tools wrap. It is _not_ version control: local git work (commit,
branch, merge) is deliberately outside this Server.
_Avoid_: version control, git operation, 版控

**Pull request**:
A GitHub pull request. It shares one number space with issues, and GitHub's data model
makes every pull request an issue — though not the reverse. In this Server's vocabulary
the two are nevertheless distinct: an issue is never a pull request. Handing a pull request
number to any issue Tool is a rejected request, not a variant read — on a read, allowing it
costs the wrong thing returned; on a write, an irreversible side effect on an object nobody
asked for.
_Avoid_: treating a PR as a kind of issue, 把 PR 当成 issue 的一种

**Label**:
A named tag a repository can put on an issue. A Label plays two roles here, and they are
not interchangeable. As an **attribute** of an issue it is just its name — that name is
also what a Client passes back to filter by, so nothing else about it is load-bearing. As
an **object of choice**, when a Client is deciding which label to filter on, the name alone
is often not enough to tell two labels apart, and the description is what distinguishes
them. The Server therefore reports a Label in two shapes depending on which role it is
in; that asymmetry is deliberate, not an inconsistency to be tidied away. See ADR-0004.
_Avoid_: tag, category, 分类

**Comment**:
A remark someone wrote on an issue. Three different things on GitHub answer to the word, and
only this one is a Comment here. A **timeline event** — a label added, an assignee set, the
issue closed — is not a remark and is not one. A **review comment** hangs off a line of a
diff and belongs to the pull request side of GitHub, which this Server's read Tools do not
cover. See ADR-0006.
_Avoid_: event, activity, note, 留言

### Roles

**Server**:
The Java program in this repo. It declares Tools and Resources and answers JSON-RPC
requests. Note the inversion from ordinary web vocabulary: an MCP Server is normally
started _by_ its Client as a subprocess, not a long-lived host that clients dial into.
_Avoid_: service, backend, API server

**Client**:
Whatever connects to the Server and invokes its Tools. In this project that is always
the Inspector.
_Avoid_: consumer, caller, frontend

**Inspector**:
The official MCP Inspector, used here as the test Client — a UI for hand-sending
requests and reading Tool output. It is a Client, not a debugger attached to the Server.
_Avoid_: test tool, debugger, test harness

### Primitives

**Tool**:
An operation the Server declares that a Client can invoke to _do_ something. Named,
with typed inputs, returning a result.
_Avoid_: function, endpoint, command, API

**Resource**:
Data the Server exposes for a Client to _read_. Contrast with Tool: a Resource is
fetched, a Tool is called.

This Server declares none, and that is a measured result rather than a gap yet to be
filled. Read literally, the definition above fits several things here — a repository's
label set most of all. What rules it out is that fetching a Resource has no way to
report a failure: there is no equivalent of the Remedy every Tool call carries, so a
failure would have to travel as a protocol error the model never sees, or be disguised
as ordinary content. Every read operation here is therefore a Tool. See
[#15](https://github.com/DemianLi/project_mcp/issues/15).
_Avoid_: document, file, context, asset

**Annotations**:
What a Tool declares about itself: whether it reads or writes, whether the write is
destructive, whether calling it twice differs from calling it once, whether it touches an
open world beyond this Server. They are the Server talking about its own Tools, and the
protocol's one requirement of a Client is that it **not** trust them — nothing verifies an
annotation, so a Client gating on one is gating on an assertion. See
[#26](https://github.com/DemianLi/project_mcp/issues/26).

`readOnlyHint` plays three roles here and they are not interchangeable. As a
**declaration** it is what a Client is told about one Tool. As a **switch** it is what
gives the other two hints meaning at all — `destructiveHint` and `idempotentHint` say
nothing until it is false (see ADR-0007). And this Server reads its own **partition** off
it: which Tools count as writes is that field, not a second list kept alongside. The first
role is a claim about a Tool; a Read-only instance is a claim about something else
entirely, so neither is a stronger grade of the other.
_Avoid_: guarantee, permission, enforcement, 保证

### Server design

**Envelope**:
The outer structure every `list_*` Tool returns. `items`, `count` and `truncated` are on all
of them, with fixed meanings; a Tool may add keys beside those three, but never remove or
redefine one — so a Client learns the core once and it holds across Tools. It is this
Server's own design, _not_ part of the MCP protocol, which is why it sits here rather than
under Primitives. See `docs/adr/0001-list-issues-parameters-and-return-shape.md` and its
amendment.
_Avoid_: wrapper, response object, payload, 外层

**Remedy**:
What a caller should _do next_ about a failure — retry, check whether a write landed before
retrying it, fix the request, or ask a human. Every failure this Server reports carries one. It classifies by the action available, not
by the cause: two failures with different causes and the same action share a Remedy. Like
the Envelope it is this Server's own design, learned once and holding across every Tool.
See `docs/adr/0002-failure-contract-for-gh-calls.md`.
_Avoid_: error code, error type, failure kind, 错误码

### Deployment

**Read-only instance**:
A running Server from which no write reaches GitHub. The unit is the instance, not the
Server: the Server can be running twice, one instance writing and one not.

Neither promise is what a Tool's `readOnlyHint` declares. That is one Tool telling a
Client it does not write; this is a claim about a whole instance, made to whoever deploys
it. A deployer reading `readOnlyHint = true` has not been handed a weaker grade of either
promise below — they have been handed a promise about a different thing. See Annotations.

The word names **two different promises**, and which one is meant depends on where the
constraint lives.

**Ungranted**: the ability to write was never handed to this instance. Whatever login
`gh` resolves lacks the scope, so GitHub refuses — a bug in this Server, or a caller
arriving by a path nobody anticipated, still produces no comment. The constraint sits
outside the Server, which can only report having run into it.

**Withheld**: the instance holds a login that could write, and does not. The constraint is
the Server's own, so it covers everything the Server does — and nothing that goes around
the Server.

The two are not grades of one promise. `Ungranted` covers every route to GitHub but was
never this Server's to hand out; `Withheld` is this Server's to hand out but covers only
what travels through it.

Today this Server hands out neither promise. `Ungranted` is reachable anyway — anyone
deploying this Server can pick a login without the scope — but what comes back is this Server
misdescribing it: the refusal matches nothing known and arrives with the `UNKNOWN` Remedy,
while the nearest Remedy that would match tells the reader to log in again, which does not
fix a scope. `Withheld` does not exist here at all. Which of the two, if either, this
Server should offer is the question of
[#32](https://github.com/DemianLi/project_mcp/issues/32).
_Avoid_: read-only mode, safe mode, sandboxed, 只读模式 — an instance's identity is fixed
when it starts, not a mode it can be put into. Also avoid the bare "the Server is
read-only" without saying which of the two is meant: it read as true while every Tool
read, and ADR-0001, ADR-0003 and ADR-0004 still carry it frozen as a constraint of their
day.

### Testing

**Acceptance layer**:
The thin outer layer of the test suite, which drives the Server across its wire boundary
as a real Client would. It exists because the shape of a result — whether it is an error,
and what the Client actually receives — has no existence below that boundary, so no
test beneath it can observe the shape. It proves the shape; it is not where coverage
lives.
_Avoid_: end-to-end test, integration test, e2e

**Coverage layer**:
The inner layer of the test suite, which walks every case without crossing the wire
boundary. Named as a counterpart to the Acceptance layer: the two are a division of
labour, not two names for the same tests.
_Avoid_: unit test, fast test

### Transport

**Transport**:
How Client and Server exchange JSON-RPC messages. JSON-RPC is the protocol; the
Transport is the pipe it travels down. This Server currently speaks Stdio only.
_Avoid_: protocol, connection

**Stdio**:
The Transport where the Client launches the Server as a subprocess and speaks over
stdin/stdout. The only Transport this Server implements.

**Streamable HTTP**:
The Transport where the Server runs as an HTTP process. In `java-sdk` 2.x this is
`HttpServletStreamableServerTransportProvider`. Not implemented here — the term is
defined so it stays consistent if it lands.

**SSE**:
The deprecated predecessor to Streamable HTTP, using Server-Sent Events. Defined here
only because older tutorials and 1.x-era `java-sdk` code are full of it — recognise it,
don't reach for it.
_Avoid_: using it for new work; say Streamable HTTP instead.

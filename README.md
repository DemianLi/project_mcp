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

**Read-only for now.** Write and destructive operations are out of scope for the first
milestone.

## Transport

**Stdio only.** If a second transport is ever needed it will be Streamable HTTP;
SSE is its deprecated predecessor and is not a target.

## Building and running

Requires a JDK 25 and Maven.

```bash
mvn package
java -jar target/project-mcp-0.1.0-SNAPSHOT.jar
```

**Do not use `mvn spring-boot:run`.** Maven writes its own build output to stdout
before the application starts, and an MCP client will try to parse that as JSON-RPC
and fail. Always run the packaged jar.

Logs go to `logs/project-mcp.log`, never to the console — see `application.yml`.

## Status

Three Tools, all read-only: **`list_issues`**, **`get_issue`** and **`list_labels`**.
Each has been driven end to end in the Inspector against real GitHub.

**Zero Resources, and that is a result rather than a gap.** Fetching an MCP Resource has
no way to report a failure — `ReadResourceResult` carries no `isError` — so a failure
would have to travel as a protocol error the model never sees, or be disguised as ordinary
content. Every read operation here is therefore a Tool. See
[ADR-0004](./docs/adr/0004-list-labels-tool-shape-parameters-and-return.md).

Work is charted on wayfinder maps in this repo's issues. Three are complete:
[#1](https://github.com/DemianLi/project_mcp/issues/1) — a working Stdio server and
`list_issues`; [#7](https://github.com/DemianLi/project_mcp/issues/7) — the failure
contract and an offline test suite that proves it;
[#12](https://github.com/DemianLi/project_mcp/issues/12) — `get_issue` and `list_labels`.

## Documentation

- [CONTEXT.md](./CONTEXT.md) — domain glossary: the precise meaning of Server, Client,
  Inspector, Tool, Resource and the transports
- [AGENTS.md](./AGENTS.md) — branch strategy and the configuration AI agents read
- [docs/adr/](./docs/adr/) — the architecture decision records. Every Tool's parameters,
  return shape and known limitations are settled there, along with the failure contract
  every Tool call carries

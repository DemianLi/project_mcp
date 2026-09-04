# project_mcp

[![CI](https://github.com/DemianLi/project_mcp/actions/workflows/ci.yml/badge.svg?branch=develop)](https://github.com/DemianLi/project_mcp/actions/workflows/ci.yml?query=branch%3Adevelop)

An [MCP](https://modelcontextprotocol.io) server that exposes **GitHub platform
operations** — issues, pull requests, labels — as Tools. Think of it as a guardrailed,
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
|  - One-click Tool invocation     |                |  - Declares Tools / Resources    |
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

Early. The server starts, registers zero Tools, and keeps stdout clean; the first
Tool (`list_issues`) is not implemented yet. Progress is tracked on the wayfinder
map in [issue #1](https://github.com/DemianLi/project_mcp/issues/1).

## Documentation

- [CONTEXT.md](./CONTEXT.md) — domain glossary: the precise meaning of Server, Client,
  Inspector, Tool, Resource and the transports
- [AGENTS.md](./AGENTS.md) — branch strategy and the configuration AI agents read

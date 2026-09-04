# project_mcp

An [MCP](https://modelcontextprotocol.io) server written in Java with the official
`java-sdk`. It declares the Tools and Resources that carry this project's business
logic; the official MCP Inspector acts as its test Client during development.

## Architecture

```
+----------------------------------+                +----------------------------------+
|          MCP Inspector           |                |        Your Java Server          |
|      (official test Client)      |      Stdio     |        (built on java-sdk)       |
|                                  | <------------> |                                  |
|  - Web / terminal UI             |     JSON-RPC   |  - Business logic and API        |
|  - One-click Tool invocation     |                |  - Declares Tools / Resources    |
+----------------------------------+                +----------------------------------+
```

The roles above are defined precisely in [CONTEXT.md](./CONTEXT.md). One thing worth
flagging up front: the Server is launched _by_ the Client as a subprocess, which inverts
the usual web meaning of "server".

## Transport

**Stdio only.** SSE is planned but not implemented yet.

## Status

Early. No Server code has been committed yet — the repo currently holds the domain
glossary and the conventions that agents and contributors work from.

## Documentation

- [CONTEXT.md](./CONTEXT.md) — domain glossary: the precise meaning of Server, Client,
  Inspector, Tool, Resource and the transports
- [AGENTS.md](./AGENTS.md) — branch strategy and the configuration AI agents read

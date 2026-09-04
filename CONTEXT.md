# project_mcp

An MCP server written in Java with the official `java-sdk`. It declares the Tools and
Resources that carry this project's business logic, and during development is exercised
by the MCP Inspector acting as its client.

## Language

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
_Avoid_: document, file, context, asset

### Transport

**Transport**:
How Client and Server exchange JSON-RPC messages. JSON-RPC is the protocol; the
Transport is the pipe it travels down. This Server currently speaks Stdio only.
_Avoid_: protocol, connection

**Stdio**:
The Transport where the Client launches the Server as a subprocess and speaks over
stdin/stdout.

**SSE**:
The Transport where the Server runs as an HTTP process and streams responses over
Server-Sent Events. _Not implemented yet_ — the term is defined here so it stays
consistent when it lands, but only Stdio exists today.

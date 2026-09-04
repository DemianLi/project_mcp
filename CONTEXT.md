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

### Server design

**Envelope**:
The fixed outer structure every `list_*` Tool returns — `items`, `count`, `truncated`. A
Client learns it once and it holds across Tools. It is this Server's own design, _not_ part
of the MCP protocol, which is why it sits here rather than under Primitives. See
`docs/adr/0001-list-issues-parameters-and-return-shape.md`.
_Avoid_: wrapper, response object, payload, 外层

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

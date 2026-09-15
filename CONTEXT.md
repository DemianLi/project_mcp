# CONTEXT.md — Server domain and architecture terms

An MCP server that exposes GitHub platform operations as Tools. Written in Java on Spring Boot with stdio transport.

## Domain

**GitHub operation:**
An action on the GitHub platform — issues, pull requests, labels, comments. Local git work (commits, branches, merges) is outside this Server's scope.

**Pull request:**
A GitHub pull request. Shares the same number space as issues; GitHub's data model makes every PR an issue, but this Server treats them as distinct. Passing a PR number to an issue Tool is a rejected request, not an alternate read.

**Label:**
A named tag on an issue. Has two roles: as an issue attribute it is just its name; as a choice object when filtering, the description distinguishes similar labels. The Server returns Labels in different shapes for these two roles.

**Comment:**
A remark written on an issue. Distinct from timeline events (label added, issue closed) and from review comments (which belong to pull requests and are not covered here).

## Roles

**Server:**
The Java program in this repo. Declares Tools and Resources, answers JSON-RPC requests over stdio. Started by its Client as a subprocess (inverted from web server terminology).

**Client:**
Connects to the Server and invokes its Tools over stdin/stdout.

**Inspector:**
The official MCP Inspector — a UI for testing Servers by hand-sending requests and reading Tool output. A Client, not a debugger.

## Primitives

**Tool:**
An operation the Server declares for the Client to invoke. Named, with typed inputs, returning a result.

**Resource:**
Data the Server exposes for the Client to read. Contrast: a Resource is fetched, a Tool is called.

This Server declares no Resources. Every read operation is a Tool instead because Resources have no failure reporting. A Resource fetch cannot carry a Remedy, so a failure would have to corrupt the protocol stream or masquerade as data. This Server's read operations are Tools.

**Annotations:**
What a Tool declares about itself: whether it reads or writes, destructiveness, idempotency, scope. A Client should not trust these declarations; nothing verifies them, and the Server does not enforce them. See the writeOnlyHint / readOnlyHint specification.

The `readOnlyHint` field serves multiple roles:
- **Declaration:** Tells a Client whether this Tool reads or writes.
- **Switch:** Controls whether the other hints (`destructiveHint`, `idempotentHint`) have meaning.
- **Partition:** Marks which Tools the Server classifies as writes (for testing and auditing).

A Tool declaring itself read-only should not write to GitHub. The test suite verifies this; if a Tool claims read-only but calls the write path, tests catch it. This check is internal auditing, not Server enforcement — the Server does not act on annotations.

## Server design

**Envelope:**
The standard structure returned by every `list_*` Tool. Contains `items`, `count`, and `truncated` with fixed meanings across all list operations. A Tool may add keys but not remove or redefine these three. Server-specific, not part of MCP protocol.

**Remedy:**
What a caller should do next about a failure. One of: retry, check whether a write landed before retrying, fix the request, or ask a human. Classifies by available action, not by cause — two different failures with the same remedy share a Remedy type.

**Shape vs. Content:**
A call has two aspects:
- **Shape:** Which Tool ran, against which repository, how long it took, response size, outcome. Logged.
- **Content:** Issue bodies, comments, label names — text from Client or GitHub. Not logged.

The log records shape only. Content is not deleted by the Server; if content appears in logs, it persists on disk outside the protocol with filesystem-level access control. This is an accident to prevent. Comment bodies are elided from logged argv by name.

## Deployment

**Read-only instance:**
A running Server from which no write reaches GitHub. The unit is the instance, not the Server itself — the same Server binary can run twice, one instance writing and one not.

A read-only instance makes a promise about the deployment, not about the Server or its Tools. The term means one of two different constraints, depending on where the boundary is:

1. **Ungranted:** The authenticated login lacks write permission at GitHub. GitHub refuses the write. The Server cannot enforce this; it is a property of the login and external to the Server.

2. **Withheld:** The instance holds a login with write permission, but the Server refuses to use it. The Server enforces this boundary. It covers all writes the Server makes, but not writes that bypass the Server.

These are not grades of the same promise. An "ungranted" read-only instance protects against every route to GitHub but requires the login to lack permission — a choice the deployer makes outside this Server. A "withheld" read-only instance protects only what goes through this Server.

This Server does not offer either promise. Ungranted read-only is reachable (deploy with a login that lacks permission), but the Server does not implement withheld read-only (the Server makes no choice to refuse writes if the login could write).

## Testing

**Acceptance layer:**
The thin outer layer of the test suite. Drives the Server across its wire boundary as a real Client would. Tests protocol behavior (what a Client actually sees), and the complete set of Tools (only visible through `listTools()` at the wire boundary). Some rules can only be tested here because they have no existence below the wire.

**Coverage layer:**
The inner layer of the test suite. Walks every case without crossing the wire boundary. A division of labor with the Acceptance layer, not a second name for the same tests.

## Transport

**Transport:**
How Client and Server exchange JSON-RPC messages. JSON-RPC is the protocol; the Transport is the pipe.

**Stdio:**
The Transport where the Client launches the Server as a subprocess and speaks over stdin/stdout. This Server implements stdio only.

**Streamable HTTP:**
The Transport where the Server runs as an HTTP process. Not implemented here. MCP Java SDK provides `HttpServletStreamableServerTransportProvider` for this.

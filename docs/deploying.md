# Deploying this Server

What a deployment must provide and how the Server behaves when started.

For what the Server *is*, read [README.md](../README.md). For implementation decisions, see [design.md](design.md).

## One process, one identity

Every GitHub call this Server makes uses the same login, resolved when the Server starts. There is no per-caller identity and no way to give two users of one instance different permissions. Choose a deployment model accordingly:

- **One desk, one instance.** Each user gets their own Server instance.
- **Several people, one instance.** They share one login and its permissions; whatever one can write, all can.
- **Multi-tenant.** One process per tenant, or not at all. The design does not support mixed identity.

## Authentication and credential rotation

The Server spawns `gh` with no explicit environment modification, so the child inherits the Server process's environment. The `gh` CLI looks for credentials in this order:

1. `GH_TOKEN` environment variable (highest precedence)
2. `GITHUB_TOKEN` environment variable
3. Stored credentials from `gh auth login`, located at `$GH_CONFIG_DIR`, else `$XDG_CONFIG_HOME/gh`, else `$HOME/.config/gh`

Credential rotation depends on where you store it:

- **Token in environment:** Fixed when the process starts. Changing the environment variable takes effect only after the Server restarts. A Client may need to restart the Server to pick up a new token.
- **Token in `gh`'s config:** Re-read on each `gh` invocation. Rotation takes effect immediately on the next Tool call, with no Server restart.

**For stored credentials** (route 3), the config directory must be set and readable. A container without `HOME`, `XDG_CONFIG_HOME`, or `GH_CONFIG_DIR` leaves `gh` with nowhere to read the credential from.

For GitHub Enterprise Server, use `GH_ENTERPRISE_TOKEN` (instead of `GH_TOKEN`) and set `GH_HOST` to the host. This Server has not been tested against Enterprise instances.

## Required dependencies and configuration

A deployment must provide:

1. **Java 25 or later.** The build targets Java 25; earlier runtimes cannot load the classes.
2. **The `gh` CLI on the Server's PATH.** If `gh` cannot be started, every Tool call fails with `ASK_OPERATOR`.
3. **A GitHub credential** via one of the routes described above.
4. **A writable log directory** or `logging.file.name` set to a writable path. The default is `logs/project-mcp.log` relative to the start directory. The Server writes one JSON line per Tool call and rotates at 100 MB.
5. **Clean stdout.** stdout is the JSON-RPC protocol; anything else corrupts it. No banners, version strings, or shell greetings in the startup path. The Server itself suppresses startup output (`banner-mode: off`, `web-application-type: none`).
6. **Exclusive stdin/stdout.** The Server reads line-delimited JSON-RPC from stdin and writes to stdout. No other process may write to either. There is no health endpoint or port to expose.

The [`Dockerfile`](../Dockerfile) provides a reference implementation. Use `java -jar app.jar`, not `mvn spring-boot:run` (Maven prints to stdout before the app starts).

## JVM flags for out-of-memory handling

```
-XX:+ExitOnOutOfMemoryError -XX:+DisplayVMOutputToStderr
```

Pass both flags together, not just one. If the JVM runs out of memory without these flags, the process stops responding but does not exit, hanging indefinitely. The Server includes its own 8 MB response ceiling to refuse payloads before they exhaust the heap.

The two flags work together:
- `-XX:+ExitOnOutOfMemoryError` terminates on out-of-memory, but by default prints to stdout (which corrupts JSON-RPC).
- `-XX:+DisplayVMOutputToStderr` redirects JVM messages to stderr instead, which the protocol allows a stdio server to write to.

Without `-XX:+DisplayVMOutputToStderr`, the error message goes to stdout and breaks the protocol stream.

## Timeouts: the Client's deadline and the Server's budget

Two different timeouts apply:

- **Client timeout:** The deadline the Client sets for a request. When exceeded, the Client stops waiting and may retry or cancel. The MCP specification asks Clients to set this; this Server does not require it.
- **Server timeout:** This Server kills any `gh` invocation that takes longer than 30 seconds. A subprocess that never returns is a hang the Client cannot recover from; this timeout prevents that.

These timeouts are independent. A Client can set any deadline it wants; the Server's 30-second budget applies to each `gh` call regardless.

### Timeout budgets per Tool

Each Tool makes one or more `gh` calls, each with a 30-second budget:

| Tool | `gh` calls | worst case |
| --- | ---: | ---: |
| `list_issues`, `get_issue`, `list_labels`, `list_issue_comments` | 1 | ~30 s |
| `add_issue_comment` | 2 | ~60 s |

`add_issue_comment` makes two calls because GraphQL cannot feed a query result into a mutation in the same document; it must query for a `subjectId` first, then post the comment. Set your Client timeout above 60 seconds to avoid expiring over an `add_issue_comment` write.

### When the Client times out

If the Client's deadline expires before the Server finishes, the Client stops waiting. The `gh` call keeps running until its own 30-second budget expires or completes. The Server will eventually send a response, but the Client is no longer listening. The response is still written to the log and, on a write, the operation may have succeeded at GitHub even though the Client never saw the confirmation. See the [Remedy section](design.md#failure-contract) for how to recover from unconfirmed writes.

The Server does not support the MCP `notifications/cancelled` message, so cancellation notifications from the Client are logged as unrecognized but do not stop the `gh` call early.

## Environment variables for `gh`

Set these to prevent `gh` from writing non-operational messages to stderr:

```sh
GH_NO_UPDATE_NOTIFIER=1
GH_NO_EXTENSION_UPDATE_NOTIFIER=1
GH_TELEMETRY=false
GH_PROMPT_DISABLED=1
NO_COLOR=1
```

When a `gh` call fails, this Server passes the entire stderr to the Client (where a model reads it). Update notices and telemetry messages written to stderr can interfere with failure classification. Disabling them keeps the signal clean.

The failure classifier uses substring matching on stderr, so after upgrading `gh`, verify that failure messages still match the patterns the Server expects. A `gh` version change might rephrase error messages and break recovery advice.

## Limits and constraints

- **8 MB response ceiling:** Responses larger than 8 MB are refused with a `FIX_REQUEST` Remedy rather than delivered. This protects the heap from amplification during parsing. Ordinary GitHub queries return far below this limit.

- **Rate limiting on writes:** `add_issue_comment` is rate-limited to 80 calls per minute and 500 per hour, matching GitHub's published limit for content-generating requests. Requests over the limit are refused with `RETRY` and the number of seconds to wait. Reads are not rate-limited. The limit is per Server process; if multiple processes share the same GitHub login, their calls all count toward the same GitHub account limit.

- **No Client authentication:** Whoever can write to the Server's stdin can call any Tool. Authentication is the operating system's responsibility under stdio.

- **No health endpoint or metrics:** The Server produces no metrics or health signals.

- **No issue lock enforcement:** The Server cannot refuse to comment on a locked issue if the authenticated login has write permission. GitHub's lock only applies to users without write access.

- **No content sanitization:** Issue bodies and comments are returned as GitHub provides them. A Client that renders them must handle sanitization.

# Design

## Overview

A Server that exposes GitHub platform operations—issues, labels, and comments—as Tools. The Server wraps the `gh` CLI and shells out to it for every call. It has no local version control, no direct HTTP or GraphQL client, and no means to hold a token: authentication and authorization travel entirely through `gh`.

The Server runs as a subprocess under stdio. A Client launches it and speaks JSON-RPC over stdin/stdout. One process is one resolved `gh` login—one identity and one set of permissions. There is no per-caller authentication and no read-only switch to throw inside the Server. Multi-tenant means one process per tenant.

## Tools

The five Tools are:

| Tool | Route | Effect |
| --- | --- | --- |
| `list_issues` | `gh issue list` | reads |
| `get_issue` | `gh issue view` | reads |
| `list_labels` | `gh label list` | reads |
| `list_issue_comments` | `gh api graphql` | reads |
| `add_issue_comment` | `gh api graphql` | **writes** |

All return results on success. All fail with the contract below. Pull-request numbers are rejected rather than half-answered: GitHub numbers issues and pull requests from one sequence, and returning one alongside the other is not the job of an issue Tool.

### `list_issues`

List issues in a repository, newest-created first. Returns an Envelope carrying `items`, `count` (items in this response), and `truncated` (whether more exist beyond this response).

**Parameters:**
- `owner` (string, required): Repository owner alone, e.g. "DemianLi". Must not contain a slash.
- `repo` (string, required): Repository name alone, e.g. "project_mcp". Must not contain a slash.
- `state` (enum, optional): OPEN, CLOSED or ALL. Defaults to OPEN.
- `labels` (array of strings, optional): Label names to filter by. Multiple labels intersect: an issue must carry every one to be returned.
- `limit` (integer, optional): Maximum issues to return. Defaults to 30, clamped to 1–100. Asking for more than 100 yields 100 with `truncated` true.

**Success shape (per item):** number, title, state, labels (array), assignees (array of login names), url, updatedAt (ISO 8601).

**Limitation:** An issue's body is not returned. Use `get_issue` for the full text.

### `get_issue`

Read one issue in full.

**Parameters:**
- `owner` (string, required): Repository owner alone. Must not contain a slash.
- `repo` (string, required): Repository name alone. Must not contain a slash.
- `number` (integer, required): Issue number. Pull request numbers are rejected.

**Success shape:** number, title, state, labels (array), assignees (array), url, updatedAt, body (the full text), author (login name), createdAt, closedAt (null if open), stateReason (if closed: "completed", "not planned", or null if closed before that enum existed).

**Limitation:** Comments are not included. Use `list_issue_comments` for the discussion.

### `list_labels`

List a repository's labels, alphabetically by name. Returns an Envelope carrying `items`, `count`, and `truncated`.

**Parameters:**
- `owner` (string, required): Repository owner alone. Must not contain a slash.
- `repo` (string, required): Repository name alone. Must not contain a slash.
- `limit` (integer, optional): Maximum labels to return. Defaults to 30, clamped to 1–100. The cap is this Server's own; `gh` imposes none.
- `search` (string, optional): Case-insensitive substring filter, matched against label names and descriptions. Omit or leave blank for no filter. While a search is active the alphabetical ordering does not hold; GitHub's own match order applies instead.

**Success shape (per item):** name (exactly the string `list_issues` accepts in its `labels` parameter), description.

**Limitation:** On a repository with hundreds of labels, paging with `limit` does not get you the vocabulary—the alphabetical head is not representative. Use the `search` parameter instead.

### `list_issue_comments`

Read the comments on one issue, newest first: the first response holds the most recent comments, in ascending time order among themselves. Returns an Envelope carrying `items`, `count`, `truncated`, `totalCount` (total comments on the issue), and `nextCursor` (null once the oldest comment is reached; pass it back to continue).

**Parameters:**
- `owner` (string, required): Repository owner alone. Must not contain a slash.
- `repo` (string, required): Repository name alone. Must not contain a slash.
- `number` (integer, required): Issue number. Pull request numbers are rejected.
- `limit` (integer, optional): Maximum comments per page. Defaults to 30, clamped to 1–100.
- `cursor` (string, optional): Cursor from a prior response's `nextCursor`, to continue paging backwards. Omit on the first call. A cursor from a different issue is an error.

**Success shape (per item):** author (login name), authorAssociation (e.g. "OWNER", "CONTRIBUTOR", "NONE"), createdAt (ISO 8601), body (the comment text), url.

**Limitation:** Timeline events (label added, assigned, closed) are not returned. Only remarks are comments.

### `add_issue_comment`

Write a comment on an issue. The only Tool that changes anything on GitHub.

**Parameters:**
- `owner` (string, required): Repository owner alone. Must not contain a slash.
- `repo` (string, required): Repository name alone. Must not contain a slash.
- `number` (integer, required): Issue number. Pull request numbers are rejected. GitHub will not allow a comment on a locked issue unless the login can override the lock.
- `body` (string, required): Comment text. Must not be blank.

**Success shape:** url (permalink to the written comment).

**Limitations:** Pull requests cannot be written to (the first call resolves the number to an issue ID, and that lookup rejects pull request numbers). There is no idempotency key—the same body posted twice produces two comments. Editing or deleting comments is not supported.

## Failure contract

Every Tool call returns either a success result or `isError: true` with `structuredContent` carrying one of five Remedies and a `content` field with human-readable text.

**structuredContent shape:**
- `remedy` (string, required): the Remedy name
- `retryAfterSeconds` (integer, optional): seconds to wait before retrying. Present only for `RETRY` when rate-limited; absent otherwise.
- `message` (string, required): the sentence naming the Remedy
- `stderr` (string, required): the verbatim stderr from `gh`, or an empty string where there was none

The five Remedies and what the caller should do:

| Remedy | Action |
| --- | --- |
| `RETRY` | Try the same call again. If `retryAfterSeconds` is present, wait that long first. |
| `CHECK_BEFORE_RETRY` | Writes only. The result could not be read. Look whether the comment landed (e.g. via `list_issue_comments` with the same body), then decide: if it is there, success and use its url; if not, retry. |
| `FIX_REQUEST` | The request cannot succeed as written. Change the arguments. |
| `ASK_OPERATOR` | Nothing in the request is wrong. The environment has to be fixed: the login is missing, lacks permission, or the `gh` binary is not installed. |
| `UNKNOWN` | `gh` failed in a way this Server does not recognize. The stderr is the only diagnostic. |

**Classification by gh stderr:** Every failure `gh` produces is matched against known patterns. No pattern matches → `UNKNOWN`. Everything else is classified once by the pattern it matches. The `content` field carries a sentence stating the Remedy plus the verbatim stderr.

**The single exit:** Every Tool method hands its work to `ToolResults.attempt`, which catches `ToolFailure`, logs the call, and returns a `CallToolResult` with either the value or `isError: true` and the failure shape above. All work that can fail must run inside that lambda: a `ToolFailure` thrown outside it is caught by Spring AI instead, which answers `isError: true` with a text message and no `structuredContent`, so the Remedy is lost.

**Schema validation:** A call rejected by the input schema (a missing required argument, a wrong type) is answered by the SDK before any Tool method runs. The result has `isError: true` and a text message but no Remedy, and the call leaves no log line.

## Writes

`add_issue_comment` makes two `gh api graphql` calls:

1. A query to resolve the issue number to its ID. The lookup rejects pull request numbers: `repository.issue(number:)` cannot resolve a pull request's ID.
2. A mutation to add the comment, supplying the ID and the body.

Every string variable goes out with `-f`, which `gh` sends literally. `-F` is used only for the issue number, an integer. Under `-F`, `gh` would read a value such as `123` as a JSON number, `{owner}` as a placeholder for the current repository, and `@path` as a local file to upload — so a Client's `body` starting with `@` could post a file from the Server's machine.

The failure contract distinguishes this from reads:

- **On a read:** a timeout, interrupt, or unreadable pipe means nothing happened. The Remedy is `RETRY` after any wait.
- **On a write:** any of those three exits means the result is unconfirmed. The Remedy is `CHECK_BEFORE_RETRY`.

GitHub offers no idempotency key. The mutation's `clientMutationId` names the client, not the mutation, and the same key with the same body twice produces two distinct comments. Duplicate detection is not attempted by the Server; it belongs to the Client, which has `list_issue_comments` available.

## Bounds

| Bound | Value | Enforcement |
| --- | --- | --- |
| One `gh` call | 30 seconds | `GhCli.TIMEOUT_SECONDS` |
| One `gh` response | 8 MB | `GhCli.MAX_RESPONSE_BYTES`, refused above with `FIX_REQUEST` |
| `limit` on a list | default 30, clamped to 1–100 | `Limits` |
| Writes per minute | 80 | `WriteLimiter`, GitHub's published secondary limit for content-generating requests |
| Writes per hour | 500 | `WriteLimiter`, GitHub's published secondary limit for content-generating requests |

On `add_issue_comment` the two calls may together consume up to 60 seconds of wall time (30 per call). Write rate limiting is per-process, in memory only. Every admitted call is counted regardless of outcome; the check runs after input validation and before any `gh` invocation. GitHub's limit is per account, so writes made with the same login elsewhere are invisible here—this guarantees only that this Server stays inside the limit, not that the account does. Reads are not rate-limited; their cost to GitHub depends on the query, with no published per-call number.

## Input validation

Input types are validated by the SDK before dispatch (the `owner`, `repo`, `number` annotations and the `IssueState` enum).

Additional validation:

- `owner` and `repo` must not contain a slash. Refusal happens with `FIX_REQUEST` and names the reason: a slash is read as a hostname by `gh --repo`, routing this Server to a different host. Checked on every Tool, not only the ones that compose `--repo`, so the same bad parameter gets the same answer from `list_issue_comments` as from `list_issues`.
- `labels` in `list_issues` are passed as-is to `gh`, which validates them.
- `cursor` is validated by `Cursors` before decoding: a cursor from a prior response's `nextCursor` is the only one accepted. A cursor that does not parse or was issued by a different issue fails with `FIX_REQUEST`.
- `body` in `add_issue_comment` must not be blank.

## Identity and permissions

Every call runs `gh` with the Server's environment, so every call uses the same login: `GH_TOKEN` if it is set in the Server's environment (fixed when the process starts), otherwise the login `gh` has stored (read again on every call). There is no per-caller identity and no read-only switch. [deploying.md](deploying.md) covers credential rotation.

Permissions flow from the login to `gh` to GitHub. This Server has no access controls of its own:

- A write that GitHub refuses returns the reason as `ASK_OPERATOR`, which names the environment as the problem.
- A read that GitHub refuses is the same.
- This Server does not gate writes itself; whatever the login can write, the Server will attempt.

Read-only deployment means pointing `gh` at a login without write permission. Locking an issue does not prevent a write if the login can override locks—both the query and the mutation go through, and the lock's role is on GitHub's side.

## Logging

One JSON line per Tool call in `logs/project-mcp.log`. The line records the shape of the call—which Tool, which repository, how long, how many bytes, how it ended—and never its content. No issue title, no issue body, no comment text, no label name reaches the log file on any path this Server controls.

**Fields** (beside ECS standard fields like `@timestamp`, `log.level`, `service.name`):
- `tool`: which Tool ran
- `callId`: a unique ID for this call, joins multiple lines the same call produces
- `repo`: the target, `owner/repo` as an argument the Client sent
- `outcome`: "ok" or "error"
- `durationMs`: wall-clock duration of the Tool method body
- `resultBytes` (success only): what the Client received
- `remedy` (failure only): the classification (not the stderr detail)

**Write-specific line:** When `add_issue_comment` succeeds, a second line carries the comment's permalink in the `commentUrl` field. The permalink identifies the comment without containing any of its text.

**Rotation:** A new file every 10 MB; archives are kept for up to 7 days and 100 MB in total. Nothing ships the log anywhere; a deployment that wants these lines collected owns that part.

**Halt on Error:** A Java `Error` during a call (for example `OutOfMemoryError`) writes the call's line and halts the process. Under stdio a dead Server is one the Client can restart; a live Server that never answers is one it can only time out against.

## Cancellation

`notifications/cancelled` is not implemented. The specification permits a receiver to ignore cancellation, and this Server does. When a Client abandons a call at its own deadline, `gh` continues running on this Server's 30-second budget and may complete after the Client has disconnected.

## Why there are no Resources

The MCP specification defines a Resource as data the Server exposes for a Client to fetch. This Server has none. A label set and an issue's comments are both shaped like Resources; they are implemented as Tools instead.

The reason: reading a Resource has no way to report a failure. The spec provides no `isError` field on the read result. A failure would have to travel as a protocol error the model never sees, or be disguised as ordinary content. Since every read operation here can fail and the Client needs to know why and what to do, every read is a Tool.

## Known departures

**Reads are not rate-limited.** The specification requires rate limiting on tool invocations. Only writes are rate-limited here, at GitHub's published limits. Reads are not limited because their cost to GitHub depends on the query, with no published per-call number. This is a deliberate departure from the specification, not a temporary gap.

**Tool output is not sanitized.** Issue bodies and comments are returned verbatim from GitHub. A Client is responsible for safe handling if it renders them. This is deliberate: the model needs the text, and the risk lands on the Client, not this Server.

**Cancellation is ignored.** When a Client stops waiting for a Tool call, this Server does not stop it. The call continues under the 30-second `gh` budget after the Client has disconnected.

**No metrics or health endpoint.** Under stdio the Server has no port, and stdout belongs to the protocol; there is nowhere to put one.

**One process is one identity.** Every call uses the same resolved `gh` login. Multi-tenant deployment means one process per tenant.

**Failure text is a sentence plus stderr.** The specification suggests serialized `structuredContent`. The `content` field carries a human-readable sentence stating the Remedy, followed by the verbatim stderr, rather than repeating the structure in prose.

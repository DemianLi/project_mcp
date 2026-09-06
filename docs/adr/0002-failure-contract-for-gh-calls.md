# 2. Failure contract for `gh` calls

Date: 2026-09-05

## Status

Accepted. Resolves [#8](https://github.com/DemianLi/project_mcp/issues/8); binds the
implementation in [#10](https://github.com/DemianLi/project_mcp/issues/10) and the suite
in [#11](https://github.com/DemianLi/project_mcp/issues/11).

**Superseded in part by ADR-0008**, which was written once the Server gained a Tool that
writes. Two rulings below no longer hold: that four Remedies are enough (a fifth,
`CHECK_BEFORE_RETRY`, exists for a write whose result could not be read), and that a
timeout reports the budget it spent in the optional wait (it now carries none). The
principle this ADR is built on — classify by the action available, not by the cause — is
untouched, and is what ADR-0008 argues from.

## Context

The first map deferred this deliberately: `list_issues` reached its acceptance condition
while every `gh` failure came out as a `RuntimeException`, and
[#5](https://github.com/DemianLi/project_mcp/issues/5) chose not to settle the failure
shape in passing. This ADR settles it, for the whole Server rather than one Tool.

What that produced, measured in [#6](https://github.com/DemianLi/project_mcp/issues/6):
`isError: true` with a single `TextContent` carrying `gh`'s stderr **twice**, full argv
included. The duplication is not ours —
`AbstractSyncMcpToolMethodCallback.createSyncErrorResult()` joins `e.getMessage()` and
`rootCause.getMessage()` with a line separator, and a directly-thrown `RuntimeException`
is its own root cause. It cannot be fixed by rewording; only by changing how the Tool
returns.

### What `gh` actually does

Measured, not assumed:

| Reachable? | Failure | What `gh` emits |
| --- | --- | --- |
| yes | repo does not exist | `GraphQL: Could not resolve to a Repository with the name '...'` |
| yes | not logged in / bad token | `HTTP 401: Bad credentials` + `Try authenticating with: gh auth login` |
| yes | network down | `Post "https://api.github.com/graphql": ... connection refused` |
| yes | repo has issues disabled | `the '<owner>/<repo>' repository has disabled issues` |
| yes | `owner`/`repo` compose a malformed slug | `expected the "[HOST/]OWNER/REPO" format, got "..."` |
| yes | timeout (ours, 30s) | our own message; no stderr |
| yes | `gh` not installed | **no stderr at all** — an `IOException` from `ProcessBuilder.start()` |
| no | `--state banana`, `--json nosuchfield` | a multi-line usage blob |

Two of these rows matter more than the rest. **`gh` not installed** never produces a
non-zero exit — it fails before the process exists, on a structurally different path from
every other row. And the last row is **unreachable through the Tool's typed surface**: the
`IssueState` enum and the `FIELDS` constant make those arguments impossible to send, so
they could only ever mean a bug in this Server.

Rate limiting belongs in the reachable set but is **unmeasured** — it could not be
provoked locally (the graphql budget was untouched at 5000/5000). The contract is shaped
to accommodate it without knowing its exact wording.

### What the runtime permits

Measured against `mcp-core` 2.0.0 sources:

- A Tool method may return a `CallToolResult` **directly** —
  `AbstractMcpToolMethodCallback.convertValueToCallToolResult()` has an `instanceof`
  branch that passes it through — so `isError`, `content` and `structuredContent` all come
  under this Server's control.
- Throwing an `McpError` produces a JSON-RPC **protocol error** instead, which is a
  different thing semantically: the call did not happen.
- `McpAsyncServer` line 403: `if (Boolean.TRUE.equals(result.isError())) { return result; }`
  — **an error result skips output-schema validation entirely** and is forwarded
  untouched.
- `CallToolResult`'s constructor asserts `content` is non-null. `structuredContent` is a
  free `Object`; `_meta` exists alongside both.

## Decision

### The contract is Server-wide

One failure contract, inherited by every Tool this Server will ever declare — the same
call [#5](https://github.com/DemianLi/project_mcp/issues/5) made for the Envelope, and for
the same reason: a Client should learn it once. The asymmetry with parameters is real —
parameters differ per Tool, but the ways `gh` fails do not.

### Failures are classified by remedy, not by cause

Every failure carries a **Remedy** — what the caller should do next:

| Remedy | Failures |
| --- | --- |
| `RETRY` | network down · timeout · rate limiting |
| `FIX_REQUEST` | repo does not exist · malformed `owner`/`repo` · issues disabled on the repo |
| `ASK_OPERATOR` | not logged in / bad token · `gh` not installed |
| `UNKNOWN` | anything whose stderr matches nothing known |

The audience decides this. The contract is written for **an LLM agent that will act on
it**, not for a human reading the Inspector — the Inspector is the only Client today, but
that is a fact about now, not a specification. Classifying by cause (`NOT_FOUND`, `AUTH`,
`NETWORK`, `RATE_LIMITED`) would be more precise along an axis on which nothing behaves
differently: an agent does the same thing with `NETWORK` and `TIMEOUT`, so splitting them
only moves the grouping work to the caller.

`RETRY` may carry an optional **wait in seconds** — populated for rate limiting (from
whatever `gh` reports) and for timeouts (the budget actually spent), left empty for a
dropped network. A bare `RETRY` invites an immediate retry, which is exactly wrong for a
rate limit.

The unreachable class gets **no Remedy of its own**. `INTERNAL` would name a distinction
no caller acts on differently from `ASK_OPERATOR`, and the verbatim stderr identifies it
unmistakably anyway — nothing else `gh` emits looks like a usage blob.

### Classification lives in `GhCli`

`GhCli` classifies and throws a `GhFailure` carrying the Remedy, the verbatim stderr, and
the optional wait. One shared conversion turns that into the wire shape; Tools do not each
write their own.

This is where the knowledge is. Recognising `gh`'s stderr wording, knowing that a missing
binary arrives as an `IOException`, knowing the timeout budget — all of it is already
`GhCli`'s and nothing else's. Classifying in the Tool layer would leak `gh`'s vocabulary
into every Tool and make "Server-wide" a matter of discipline rather than structure. It
also puts every string comparison in one class, which is the only place that will need
changing when `gh`'s wording drifts.

### Everything is `isError`, never a protocol error

No failure takes the `McpError` path, including `gh` not installed. A protocol error is a
transport-level failure that never reaches the model as tool output — routing anything
there would deliver the Remedy where the intended reader cannot see it. `gh` missing is
still a Tool that ran and could not do its job; `ASK_OPERATOR` is the answer for it.

### The payload

`CallToolResult` with `isError: true`, and:

- **`structuredContent`** carries the Remedy, the optional wait, and the verbatim stderr.
  This is the authoritative half. Error results skip schema validation, so it is forwarded
  exactly as written.
- **`content`** carries one human-readable sentence that states the remedy in words,
  followed by the verbatim stderr. `content` is mandatory, so this is not optional —
  and it is what makes structured errors safe here, unlike the STRUCTURED success mode
  #5 deferred: the human always sees a sentence regardless of whether a Client renders
  `structuredContent`.

The two halves say the same thing to two readers. They are not two different pieces of
information.

`_meta` is not used. It is the protocol's extension space, not a place for payload.

**The `gh` argv is removed** from what the caller sees. It tells the caller nothing they
can act on — they did not write it — while making this Server's internal construction part
of its observable surface, so that changing the `FIELDS` constant would change what
Clients see.

## Consequences

- `GhCli` gains a seam it does not have today, and `TIMEOUT_SECONDS` stops being a private
  constant: the wait it reports is part of the contract, so changing it changes the
  contract.
- **Classification is string-matching `gh`'s stderr**, and it is the first casualty of the
  `gh` version drift already noted as fog on
  [#7](https://github.com/DemianLi/project_mcp/issues/7). Two things bound the damage: the
  verbatim stderr always travels alongside, so a misclassification loses nothing, and
  unmatched output falls to `UNKNOWN` rather than to a confident wrong answer.
- Dropping the argv from the payload costs nothing diagnostically. It is written to the log
  file at `WARN` instead, with the Remedy alongside. Only the *console* appender is off
  (`logging.threshold.console=OFF`, the price of stdout hygiene); `logging.file.name` has
  been pointing at `logs/project-mcp.log` since the scaffold.
- Rate limiting is contracted for but unverified. The first real rate limit will show
  whether `gh`'s wording is matchable and whether a wait can be extracted; until then it
  lands in `UNKNOWN`, which is the designed-for outcome rather than a surprise.
- `IssueMapperTest` predates this and knows nothing about failures.
  [#11](https://github.com/DemianLi/project_mcp/issues/11) subsumes it.

## Alternatives rejected

- **Keep throwing `RuntimeException`.** Cheapest, and wrong on the one axis that matters:
  the duplication is Spring AI's, so no amount of message-writing removes it, and there is
  nowhere to put a machine-readable Remedy.
- **Classify by cause.** More precise, along an axis where no caller behaves differently.
  The name `ErrorCode` was rejected for the same reason: it invites exactly the additions
  this decision rules out.
- **A separate classifier component.** One more layer, buying nothing — every input it
  would need already belongs to `GhCli`.
- **Remedy in `content` as a JSON string.** A string inside a string, for a reader that was
  chosen precisely so it would not have to parse prose.

## Amendments

**2026-09-05, during [#10](https://github.com/DemianLi/project_mcp/issues/10).** As first
written, the consequence above claimed the argv would be *gone* once removed from the
payload, because console logging is off, and that recovering it would need a file log
"beyond this map". That was wrong on a fact: `application.yml` has configured
`logging.file.name: logs/project-mcp.log` since the scaffold, and it was actively writing.
The decision is unaffected — the argv still stays out of what the caller sees — but its
stated cost was not real, so the implementation logs it rather than discarding it.

**2026-09-05, during [#13](https://github.com/DemianLi/project_mcp/issues/13).** The failure
type is renamed `GhFailure` → **`ToolFailure`**. `get_issue` rejecting a pull request number
is the first failure this Server reports that `gh` did not produce — `gh` succeeded and
returned a pull request; `IssueTools` judged it unacceptable — so a name asserting that `gh`
failed became untrue. `CONTEXT.md`'s Remedy entry already read wider than the class name
("every failure **this Server reports** carries one"). Nothing about the contract moves:
`GhCli` is still the only place that knows how `gh` fails, `stderr` is still verbatim and
still `""` where there was none, and the four keys of `structuredContent` are unchanged. The
rename lands with [#17](https://github.com/DemianLi/project_mcp/issues/17); see ADR-0003 for
the decision it came out of.

**2026-09-06, during [#36](https://github.com/DemianLi/project_mcp/issues/36).** The
`ASK_OPERATOR` row above names two failures and both are authentication: not logged in, and
`gh` not installed. It now also covers a third that is not — a login that is authenticated
and simply not permitted to do what was asked.
[#33](https://github.com/DemianLi/project_mcp/issues/33) measured it: a fine-grained PAT
with Issues: Read-only, refused by `addComment` with `gh: Resource not accessible by
personal access token`, after the same token's issue lookup had already succeeded. Before
this it landed on `UNKNOWN`, beside a Remedy whose message told the reader to run
`gh auth login` — which does not change what a login is allowed to do.

The decision does not move. This is not a sixth constant: the action is the one
`ASK_OPERATOR` already names — stop, and ask a human — and a constant for *why* the human is
needed would name a cause, which is the axis this contract deliberately does not have. What
the row understated is the reach of "the environment", which is not only whether a login
exists but what that login may do. `GhCli` matches the family (`resource not accessible
by`) rather than the one sentence measured, so an installation token — what `gh` resolves
inside GitHub Actions — reaches the same Remedy; only the PAT wording is measured, and the
message therefore carries nothing true of a PAT alone.

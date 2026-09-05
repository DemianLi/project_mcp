# 7. `add_issue_comment` parameters, return shape and annotations

Date: 2026-09-05

## Status

Accepted. Resolves [#29](https://github.com/DemianLi/project_mcp/issues/29) and binds the
implementation in [#30](https://github.com/DemianLi/project_mcp/issues/30). Builds on
ADR-0005 for the route and ADR-0002 for the failure contract. **Does not amend ADR-0001** —
the payload chosen below is what keeps its TEXT-only line intact, and that was one of the
reasons to choose it.

This is the first Tool in this Server that changes anything on GitHub.

## Context

Four Tools precede this one and all four are reads. Three questions had no prior answer.

**The trimming rule does not transfer as written.** ADR-0003: a field earns its place if the
Client can *do* something with it. On a read that has a concrete referent — `labels` feeds
back into `list_issues`' parameter, `assignees` drives the client-side filtering ADR-0001
chose, `url` is the pull-request discriminator. A write has no next read to decide. The rule
survives; what "do something" points at had to be named again.

**`annotations` take a real value for the first time.** With `readOnlyHint = true`,
`destructiveHint` and `idempotentHint` are — in the spec's own words, byte-identical in both
current `schema.ts` versions — meaningful only when `readOnlyHint == false`. The four
existing Tools have been publishing two fields nobody had reason to read.

**A wrong number now costs something irreversible.** `CONTEXT.md` says handing a pull request
number to an issue Tool is a rejected request. On a read, allowing it returns the wrong
thing. On a write it leaves a comment on an object nobody asked for — measured, not
hypothetical: while resolving [#27](https://github.com/DemianLi/project_mcp/issues/27) both
`gh issue comment` and the REST route wrote successfully into a pull request.

Every figure below was measured against real `gh` and a real repository while resolving #27
and #29. The route probe is on branch `prototype/write-route` (`53c5b51`); the side effects
landed in the sandbox from [#25](https://github.com/DemianLi/project_mcp/issues/25).

## Decision

### Parameters

| name | required | |
| --- | --- | --- |
| `owner` | yes | Repository owner. |
| `repo` | yes | Repository name. |
| `number` | yes | Must be an issue number. The pull-request clause rides in the description, as ADR-0001 and ADR-0003 do. |
| `body` | yes | The comment's Markdown. Rejected when blank. |

Four, and no fifth — see "`--edit-last` is not a parameter" below for the one that was
considered and refused.

### `body` is rejected before `gh` runs when it is blank

`gh issue comment 1 --body ""` fails with `GraphQL: Body cannot be blank (addComment)`,
exit 1 — and so do `" "`, `"  "` and `"\n"`. GitHub's predicate is blankness, not emptiness,
so this Server's is `String.isBlank()`. Validating emptiness alone would let whitespace
through to the exact failure the check exists to prevent.

The check throws `ToolFailure(Remedy.FIX_REQUEST, …)` before the call, which is the shape
`Cursors.unwrap` already uses (ADR-0006). Two reasons over letting `gh` fail. A blank body
has no possible success, so the call is a wasted round trip held open by a 30-second
timeout. And `classify()` maps that stderr to `UNKNOWN` today, so deferring it would hand
the write failure contract ([#28](https://github.com/DemianLi/project_mcp/issues/28)) a case
that one line of validation removes.

### The route is `gh api graphql`, and the pull-request guard comes with it

| route | calls | success response | writes into a pull request? |
| --- | --- | --- | --- |
| `gh issue comment` (porcelain) | 1 | 81 B | **yes — measured** |
| `gh api` (REST) | 1 | 1,727 B | **yes — measured** |
| `gh api graphql` | 2 | 139 B | no |

The two `gh` routes are not two APIs. porcelain's blank-body failure is reported as
`GraphQL: Body cannot be blank (addComment)` — `gh issue comment` is itself `addComment`.
The difference is not the write; it is **who turns a number into a `subjectId`**. porcelain
resolves a number without caring which kind it is. This Server's lookup is
`repository(owner:, name:) { issue(number:) }`, which cannot yield a pull request's id.

Writing to a pull request is ruled off the map, so the guard is mandatory, and a guard is a
call: porcelain's one-call advantage becomes two either way. What is left to choose between
them is *where the guard lives* — a property of the query that holds whether or not anyone
remembers it, or a separate step that stops working the moment someone reorders the method.
#27 measured what the second one looks like when it is absent.

The response sizes did not decide this, and are recorded so a later reader does not assume
they did.

### The guard is already implemented, and that makes a read-route branch load-bearing for a write

`repository.issue(number:)` on a pull request number returns
`Could not resolve to an Issue with the number of 3.` and `gh` exits 1. `GhCli.classify()`
already carries that substring — added for the read route in ADR-0005 — and returns
`FIX_REQUEST` with a message that already names the pull-request case. The guard costs no
new code.

The consequence has to be written down: **that branch is now what stands between a Client's
typo and an irreversible side effect.** Its comment cites ADR-0005 alone, so a future reader
tidying read-specific wording out of it would silently remove the pull-request guard from
the write Tool. The comment must name this ADR too. (This is separate from the first-match
ordering problem #27 handed to #28; that one concerns branches not yet written.)

### The payload is the new comment's `url`, and nothing else

```json
{ "url": "https://github.com/DemianLi/project-mcp-sandbox/issues/1#issuecomment-5552728459" }
```

Reading ADR-0003's rule on this side: a write has no next read, so what remains of "the
Client can do something with it" is *hand the result to a person* and *prove the write
landed*. `url` is the only field that does either.

The other four of ADR-0006's five fail the rule here. `body` is what the caller just sent.
`createdAt` is approximately now. `author` is whoever `gh` is authenticated as.
`authorAssociation` is the one field a Client genuinely cannot derive — it is what the
Server's identity is to that repository — and it is still excluded: knowing it changes
nothing the Client can do about the call it just made.

Proof of landing is not decoration.
[#26](https://github.com/DemianLi/project_mcp/issues/26) found that the spec's own timeout
path manufactures "did the write happen?" — a party SHOULD cancel on expiry, the
cancellation may arrive after the work is done, and the Client SHOULD ignore a response
arriving afterwards — while offering no mechanism to detect it. The returned `url` is the
only artifact this Tool leaves that a later call can check against. What that check is
belongs to #28; without the `url` it would have nowhere to start.

Cost did not decide this either: selecting all five costs 356 B against 139 B across the
same two calls, because the fields are already inside the mutation's response.

### One text block of JSON, and ADR-0001 is not amended

One `text` content block holding the object above, no `structuredContent`. `ToolResults.of`
is reused unchanged.

A bare URL string was rejected: it would make this the only Tool whose output a Client
cannot parse the way it parses the other four. `structuredContent` on the success path was
rejected because it does not buy anything a one-key object does not already give, and it
*would* cost an amendment to ADR-0001's line that the wire carries TEXT only — a change
worth making when something needs it, not as a side effect of the first write. It stays
available additively; nothing here forecloses it.

This is not an Envelope. The Envelope belongs to `list_*` Tools (ADR-0001), and one write is
not a list.

### annotations

| | value | |
| --- | --- | --- |
| `title` | `Add a comment to an issue` | |
| `readOnlyHint` | `false` | First time in this Server. |
| `destructiveHint` | `false` | |
| `idempotentHint` | `false` | Written out rather than defaulted. |
| `openWorldHint` | `true` | Unchanged from the read Tools. |

`destructiveHint = false` because the spec's line is additive versus destructive updates —
"If true, the tool may perform destructive updates to its environment. If false, the tool
performs only additive updates." Adding a comment overwrites nothing and removes nothing.
The tempting argument for `true` is irreversibility: this Server exposes no delete, so a
comment written is a comment kept. That is a different axis from the one the field names,
and answering by reversibility would report something a Client did not ask about. The
argument that *would* have made this destructive — a Tool that can write to an object the
caller did not name — is closed by the guard above, not by the hint.

`idempotentHint = false` is not a judgement call: two calls with the same four arguments
produce two comments, and no upsert is exposed. It is written explicitly even though the
spec's default is also `false`, and even though the value on the wire is identical either
way — Spring AI's `SyncMcpToolProvider` copies all five hints onto
`McpSchema.ToolAnnotations` unconditionally, so the four read Tools have been publishing
`idempotentHint: false` meaninglessly since the first one shipped. It is spelled out because
this is the single place in this Server where it means anything, and because the last
unexamined default in this corner was wrong for a month: #26 found `IssueTools.java:52-55`
attributing the spec's own defaults to Spring AI.

None of the four is load-bearing for a Client, and this ADR does not pretend otherwise. #26
established that annotations are non-binding self-description — the spec's only MUST here is
that Clients not trust them.

### `--edit-last` is not a parameter

`gh issue comment` carries `--edit-last` and `--create-if-none`, which together are an
upsert. Neither reaches the schema. A Tool named `add_issue_comment` that can also modify an
earlier comment makes its own name false, and leaves `idempotentHint` unanswerable — the map
fixed `false` as a premise when it was charted, and an exposed upsert contradicts it.

Two things this does not decide. It does not stop the Server using an upsert *internally* to
converge a retry, which is #28's to settle: being a parameter and being a mechanism are
different questions. And it does not make upsert porcelain-only — measured while resolving
#29, `viewer { login }` and `comments(last:) { nodes { id } }` return from the same lookup
query this route already spends, so `updateIssueComment` against `addComment` is a branch
inside the same two calls. Choosing GraphQL costs #28 nothing.

## Known limitations

- **The write is two calls and they are not atomic.** Between the lookup and the mutation the
  issue can be closed, locked or deleted; the failure then lands on the mutation, whose
  stderr is #28's to classify. The guard is unaffected — an issue cannot become a pull
  request.
- **A blank `body` never reaches GitHub**, so a Client sees this Server's sentence rather
  than GitHub's. Deliberate, and the reason is above.
- **`authorAssociation` is not reported.** A Client that needs to know what identity the
  comment was written under has to read it back with `list_issue_comments`.
- **A successful write leaves nothing in this Server's log.** ADR-0002 logs argv on failure
  only. Whether that is right for a write is flagged on the map as not yet specified.
- **The pull-request guard rests on a `classify()` branch shared with the read route**, whose
  comment must therefore name this ADR.

## Out of scope

- **The implementation** — [#30](https://github.com/DemianLi/project_mcp/issues/30), which
  also carries the read-only identity calibration.
- **The write failure contract** — [#28](https://github.com/DemianLi/project_mcp/issues/28):
  a timeout that has already written, the six write-rejection rows #27 measured that all
  classify as `UNKNOWN` today, and the first-match-wins ordering those branches introduce.
- **A global switch for write capability.** Still fog on the map; this Tool does not create
  it and does not resolve it.
- **A second write Tool, `create issue`, any write on the pull-request side, and deletion** —
  all ruled out on the map, unchanged here.

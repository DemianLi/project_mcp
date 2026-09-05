# 1. `list_issues` parameters and return shape

Date: 2026-09-04

## Status

Accepted. Resolves [#5](https://github.com/DemianLi/project_mcp/issues/5); binds the
implementation in [#6](https://github.com/DemianLi/project_mcp/issues/6).

## Context

`list_issues` is the first Tool this Server declares, and reaching it is the acceptance
condition of the map ([#1](https://github.com/DemianLi/project_mcp/issues/1)). Its
parameters and return shape are the Server's first piece of public surface — every later
`list_*` Tool will be read against it.

Constraints already settled by the map, carried in rather than re-litigated: the Server is
read-only; every Tool call carries `owner`/`repo` (stateless repo targeting); the Server
shells out to the `gh` binary and parses its JSON, so the return is bounded by what
`gh issue list --json` can actually produce; errors reach the Client by throwing a
`RuntimeException` ([#2](https://github.com/DemianLi/project_mcp/issues/2)).

The map also fixes the positioning that decides most of what follows: **a guardrailed,
typed subset of `gh`** — an explicit allowlist of operations with typed input schemas, not
a shell with a Tool-shaped lid.

## Decision

### Parameters

| Parameter | Type                          | Required | Default                     |
| --------- | ----------------------------- | -------- | --------------------------- |
| `owner`   | `String`                      | yes      | —                           |
| `repo`    | `String`                      | yes      | —                           |
| `state`   | `enum { OPEN, CLOSED, ALL }`  | no       | `OPEN`                      |
| `labels`  | `List<String>`                | no       | empty — no label filter     |
| `limit`   | `int`                         | no       | `30`, clamped to `[1, 100]` |

`state` is a Java enum rather than a `String` so the derived JSON Schema carries an `enum`
constraint: a bad value is rejected at the schema layer instead of becoming a `gh` runtime
error. This is the most concrete cash-out of the "typed subset" positioning.

`limit` is clamped at both ends. The upper bound protects the Client's context window; the
lower bound exists because `gh` rejects `--limit 0` and negatives outright (measured:
`invalid limit: 0`, non-zero exit). Clamping both ends keeps one predictable rule on one
parameter, rather than silently clamping above and throwing below.

**Not included: `assignee`.** Its filtering job is done Client-side from the `assignees`
field in the response — which is why that field is in the payload.

**Not included: `search`.** One parameter would subsume every other, but it hands the
Client GitHub's whole query language. That is the opposite of a typed subset: it is a shell
wearing a Tool's shape.

### Return shape

A **structured JSON payload**, not prose. The real caller is a model deciding what to read
next; it needs addressable fields, not a narrative. `gh` already emits JSON, so a prose
rendering would be a third representation invented in the middle — and a lossy one.

The payload is wrapped in an **Envelope** (see `CONTEXT.md`), shared by every `list_*` Tool:

```java
record ListResult<T>(List<T> items, int count, boolean truncated) {}
```

- `items` — the generic key, not `issues`. The reason to have a shared Envelope at all is
  that a Client learns it once and it holds everywhere; a per-Tool key would fold half of
  that back. The Tool name `list_issues` already says what is inside.
- `count` — the number of items in this response. Redundant with the array length by
  construction, and kept anyway: counting array elements is a step a model can get wrong,
  and one integer removes the opportunity.
- `truncated` — **"more issues exist beyond this response."** Implemented by asking `gh`
  for `limit + 1` and, on getting `limit + 1` back, setting the flag and dropping the extra
  item. The weaker reading — "your `limit` got clamped" — was rejected: a repo with 500 open
  issues queried at `limit=30` would report `truncated: false`, which is a lie, and would
  leave the Envelope barely earning its keep.

Each item carries **seven fields**:

```json
{
  "number": 6,
  "title": "Implement list_issues and verify it in the Inspector",
  "state": "OPEN",
  "labels": ["wayfinder:task"],
  "assignees": ["DemianLi"],
  "url": "https://github.com/DemianLi/project_mcp/issues/6",
  "updatedAt": "2026-09-04T13:23:33Z"
}
```

**`body` is excluded.** `list_issues` exists so a Client can decide *which* issue to read;
`get_issue` exists to read it. Folding both into one Tool means paying for full text on
every listing. Measured on this repo's six issues: the full-field payload is 11,964 bytes,
the trimmed one 1,776 — the difference is almost entirely `body`, and `body` is the one
field that grows without bound.

**`labels` and `assignees` are flattened to string arrays.** `gh` returns objects —
`{id, name, description, color}` and `{id, login, name, databaseId}` — whose extra keys are
a GraphQL node id, a UI render colour, and an internal database id; `assignees[].name` is
an empty string in practice. Flattening saves ~40% (2,368 → 1,423 bytes on this repo), but
the reason is symmetry: a flattened label is the same string the Client passes back into the
`labels` parameter. Objects would force the Client to dig out `.name` to build its next query.

`updatedAt` rather than `createdAt`: during triage the question is which issue is moving.

### Wire mode: TEXT, not STRUCTURED

Spring AI offers two paths, and this is a real fork rather than an implementation detail:

- **TEXT** (`@McpTool(generateOutputSchema = false)`, the default) — the Envelope record is
  serialised to a JSON string and placed in a single `TextContent`.
- **STRUCTURED** (`generateOutputSchema = true`) — Spring AI derives an `outputSchema` from
  the return type, and the result goes into `CallToolResult.structuredContent`.

**TEXT is chosen for the first version.** STRUCTURED is the more faithful expression of the
"typed subset" positioning — the Envelope would become a machine-readable contract published
in the Tool declaration rather than an agreement written in prose. It was set aside for one
reason: in Spring AI 2.0.1, STRUCTURED populates `structuredContent` and **does not** also
emit a `TextContent`. The map's acceptance condition is that the Inspector *shows* this
repo's issues, and that would then rest on unmeasured Inspector rendering behaviour.

Verified against `spring-ai-mcp-annotations-2.0.1-sources.jar`:

- `AbstractMcpToolMethodCallback.convertValueToCallToolResult()` — under `ReturnMode.STRUCTURED`
  the result is built with `.structuredContent(...)` alone; under `TEXT` a non-`String` return
  value is JSON-serialised into a `TextContent`, while a `String` is passed through verbatim.
- `McpTool.generateOutputSchema()` defaults to `false`.
- `SyncMcpToolProvider` picks the mode from whether an `outputSchema` was generated.
- `JacksonUtils.getDefaultJsonMapper()` sets no `NON_NULL` inclusion, so null record fields
  **do** appear in the output. Serialisation is Jackson 3 (`tools.jackson`), not
  `com.fasterxml.jackson`.

## Known limitations

To be stated in the Tool and parameter descriptions, so they are visible in the schema
rather than discovered at runtime:

- **Multiple labels intersect (AND), they do not union.** Measured: filtering on two labels
  no issue holds together returns `[]`. `gh`'s semantics are kept as-is; translating to OR
  would mean issuing N queries and merging, quietly inventing a behaviour `gh` does not have.
- **`limit` is capped at 100.** A Client asking for 500 gets 100 with `truncated: true`, and
  no way to raise the ceiling. The cap is published in the parameter description instead of
  being signalled by an extra Envelope field.
- **Ordering is `gh`'s default: created descending, not `updatedAt`.** `gh issue list` has no
  `--sort` flag; the only route is `--search "sort:updated-desc"`, which readmits the very
  parameter rejected above. Consequence worth naming: what `truncated` cuts off is always the
  *oldest* issues, regardless of activity. Server-side re-sorting was rejected as worse than
  nothing — it would present activity ordering over a population already truncated by creation
  date. Revisit if this becomes painful in practice.

## Out of scope

- **What a Tool returns when `gh` fails** — non-zero exit, `gh` absent, not logged in. Still
  listed under *Not yet specified* in #1 and left there deliberately: whether "`gh` is not
  installed" and "that repo does not exist" deserve different messages is its own decision, and
  #2's `RuntimeException` carries #6 to the acceptance condition without it.
- **STRUCTURED mode** — a known upgrade path, not a rejected option. Measuring whether the
  Inspector renders `structuredContent` is a five-minute experiment during #6; if it does, the
  reversal costs one boolean.

## Amendments

**2026-09-05, after [#18](https://github.com/DemianLi/project_mcp/issues/18).** The escape
hatch this ADR left itself is welded shut, and has been since ADR-0002 landed. *Out of
scope* above records STRUCTURED mode as "a known upgrade path, not a rejected option",
costed at "one boolean" once the Inspector was measured. The Inspector was measured during
[#17](https://github.com/DemianLi/project_mcp/issues/17) and it does render
`structuredContent` — but the boolean no longer does anything, because ADR-0002 changed
what a Tool method returns.

Verified in `spring-ai-mcp-annotations-2.0.1-sources.jar`, at two independent points:

- `SyncMcpToolProvider` skips output-schema generation outright when the method's return
  type is `CallToolResult` — the guard names the class explicitly. With no `outputSchema`,
  `useStructuredOtput` is false and the mode falls back to `TEXT`.
- `AbstractMcpToolMethodCallback.convertValueToCallToolResult()` returns a `CallToolResult`
  untouched *before* it consults `returnMode` at all, so even a Tool that somehow reached
  STRUCTURED would bypass the structured branch.

`@McpTool` has no `outputSchema` attribute either — only `generateOutputSchema`, a boolean —
so there is no way to supply a schema by hand and keep the current return type. Setting
`generateOutputSchema = true` on any of this Server's three Tools today is a **silent
no-op**: the flag is read, the guard makes it inert, and the mode stays `TEXT` with nothing
reported.

All three Tools return `CallToolResult`, and that is not incidental — ADR-0002 requires a
failure to carry both `isError` and `structuredContent`, and a Java method has one return
type. So the two ADRs are in tension: this one's deferred upgrade was priced before the
other one existed, and nobody re-priced it, because ADR-0002 was not thinking about an
upgrade path in ADR-0001.

Nothing about the decision above changes — TEXT was the right first choice and still is what
ships. What changes is its stated cost. Whoever picks STRUCTURED up is not flipping a
boolean; they are deciding how a failure travels if a Tool method stops returning
`CallToolResult`, which is ADR-0002's territory, not this one's.

# 4. `list_labels`: why a Tool, and its parameters and return shape

Date: 2026-09-05

## Status

Accepted. Resolves [#15](https://github.com/DemianLi/project_mcp/issues/15) and
[#16](https://github.com/DemianLi/project_mcp/issues/16); binds the implementation in
[#18](https://github.com/DemianLi/project_mcp/issues/18).

## Context

`list_labels` is a debt ADR-0001 incurred without naming. `list_issues` takes a `labels`
parameter and gives a Client no way to learn what may be put in it — the filter exists, the
vocabulary for it does not.

Two questions were open, and unlike ADR-0003's pair they are genuinely separate. **Is this a
Tool at all**, or the Server's first MCP Resource Template — a label set is exactly the kind
of repository-scoped reference data a Client might want to attach rather than call for. And
**what are its parameters and its return shape**, which could not be settled until the first
question was, because a Resource Template's URI variables and a Tool's JSON Schema
parameters are not the same expressive medium.

Constraints carried in rather than re-litigated: the Server is read-only; every call carries
`owner`/`repo`; the Server shells out to `gh` and parses its JSON; failures travel as
`isError` carrying a `Remedy` (ADR-0002); the Envelope belongs to `list_*` Tools (ADR-0001);
a field earns its place only if the Client can *do* something with it (ADR-0003).

## Decision

### A Tool, not a Resource Template

Settled by prototype rather than by argument. Branch
[`prototype/list-labels-shape`](https://github.com/DemianLi/project_mcp/tree/prototype/list-labels-shape)
declares the same `gh label list` three ways — as a Tool, as a strict Resource Template, and
as a lenient one that returns its failures as content — and all three were read over raw
Stdio and in the Inspector.

The Resource Template route works. Spring AI splits on `McpPredicates.isUriTemplate(uri)`,
so a URI containing `{...}` lands in `resources/templates/list` on its own; server
capabilities grow `"resources": {"subscribe": false, "listChanged": true}` with no extra
code. `resources/list` is `{"resources": []}` — the Inspector shows *URIs (0) /
Templates (2)* — which is correct for a Server that holds no repository state.

It was rejected because it collides with both contracts this Server has already written
down:

1. **ADR-0002's failure contract has no home there.** `ReadResourceResult` is
   `(contents, _meta)`; it has no `isError`. Measured, there are exactly two outcomes.
   Throwing: `SyncMcpResourceMethodCallback` converts any exception into an `McpError` with
   `INVALID_PARAMS` — a JSON-RPC protocol error, rendered by the Inspector as *Read Error*
   and carrying the Java class name and Spring AI's own `/nCause:` typo out to the user.
   Swallowing: the failure is returned as ordinary content, so the Client sees a *successful*
   read with no machine-readable signal that anything went wrong. ADR-0002 opens by ruling
   out precisely the first of these, on the grounds that a protocol error never reaches the
   model as tool output and so delivers the `Remedy` where its intended reader cannot see it.
2. **ADR-0001's "typed" has nothing to attach to.** URI template variables carry no schema
   at all: the Inspector renders two bare textboxes and the words "Still needed: owner;
   repo" — no types, no per-variable descriptions, no required/optional distinction, no
   `enum`. Every guardrail this ADR goes on to specify below — the `limit` clamp, the
   published cap, the description of what `search` actually matches — would have had nowhere
   to live.

What Resources offer that Tools do not is worth recording rather than burying: **a Resource
is attachable.** A Client can place it in context without the model choosing to call
anything, which suits reference data a model needs *before* it can phrase a query — which is
exactly what a label set is. That convenience was not worth abandoning two written
contracts for.

### Parameters

| Parameter | Type     | Required | Default                     |
| --------- | -------- | -------- | --------------------------- |
| `owner`   | `String` | yes      | —                           |
| `repo`    | `String` | yes      | —                           |
| `limit`   | `int`    | no       | `30`, clamped to `[1, 100]` |
| `search`  | `String` | no       | empty — no filter           |

**`limit` and `search` both exist because label sets are unbounded.** There was a clean route
that retires both: return the whole set and let the Client filter it, which is the move
ADR-0001 already made for `assignee` ("its filtering job is done Client-side from the
`assignees` field in the response"). At this repository's 19 labels — 1,393 bytes — that
route is obviously right. It does not survive contact with real repositories:

| Repository              | Labels | `name` + `description` | All 8 fields |
| ----------------------- | -----: | ---------------------: | -----------: |
| `DemianLi/project_mcp`  |     19 |              1,393 B   |    5,317 B   |
| `cli/cli`               |     83 |              5,568 B   |   21,370 B   |
| `golang/go`             |    143 |             10,703 B   |            — |
| `kubernetes/kubernetes` |    214 |             18,520 B   |            — |
| `microsoft/vscode`      |    726 |             48,933 B   |            — |
| `rust-lang/rust`        |    976 |             86,274 B   |  284,739 B   |

86 KB is the same order as the 110 KB comment payload ADR-0003 refused, and this is the
third time the same argument has recursed: ADR-0001 excluded `body` from `list_issues`
because it is the one field that grows without bound; ADR-0003 excluded `comments` from
`get_issue` for the same reason one level down; here the thing that grows without bound is
**the number of rows itself**.

**`search` earns inclusion on its own measurement, not by inheritance.** ADR-0001 rejected a
`search` parameter on `list_issues` because it hands over GitHub's whole query language —
"a shell wearing a Tool's shape". `gh label list --search` is a case-insensitive substring
match over names *and* descriptions, which voids that veto but does not by itself make a
case. The case is the size: on `rust-lang/rust`, `--search regression` returns **635 bytes**
against 86,274 for the full set — 1/136. Client-side filtering means paying the 86 KB first.

**`limit` is clamped at both ends**, as in ADR-0001, and for the same reason at the floor
(`gh` rejects `--limit 0` and negatives: `invalid limit: 0`). The ceiling is one rule across
both Tools and neither half of it comes from `gh`: measured on `rust-lang/rust`,
`gh label list --limit 1000` returns all 976 rows and `gh issue list --limit 300` returns
300. ADR-0001 was already explicit that the 100 there is the Server's own; the same holds
here, and it gains a second measured justification beyond context size — 4,812 ms for 976
label rows against 587 ms for 100, an eightfold difference. (`--search regression`: 776 ms.)

**Not included: `sort` and `order`,** although `gh label list` has both and `gh issue list`
has neither. See the next section — this is the one place where having more available from
`gh` led to exposing less.

### Ordering: a guarantee, not a parameter

`gh label list` defaults to `--sort created --order asc`. Passing that through would have the
two `list_*` Tools disagree by default on the *direction of the same nominal key*:
`list_issues` is documented as "newest-created first".

The Server passes **`--sort name --order asc`** and does not expose the choice. Creation
order answers a question nobody asks of this Tool: the Client is here to learn *what may be
put in `list_issues`' `labels` parameter*, and when a label was made bears on that not at
all, in either direction. Alphabetical is scannable, stable, and — when `limit` cuts — cuts
somewhere explicable.

Fixing it rather than exposing it also removes a defect that would otherwise be unavoidable.
**`gh` forbids `--search` together with `--sort`/`--order`** (`cannot specify --order or
--sort with --search`, non-zero exit), and Spring AI derives its schema from the method
signature, so it has no `oneOf` and no `dependentSchemas` — the exclusion **cannot be
expressed in the schema**. Exposing both would publish a parameter combination that looks
available and is rejected at runtime, which is a worse failure of ADR-0001's "guardrailed,
typed subset" than not offering the capability: the guardrail would be drawn in the schema
and leak in execution. With `sort` unexposed, the illegal combination is **unreachable** —
the Client has no syntax for it, and the Server simply omits the two flags when `search` is
present.

The rejected third option was to expose both and silently drop `sort` when `search` is
given. That invents behaviour `gh` does not have, which is the reasoning ADR-0001 used to
refuse translating multiple labels into an OR.

### Return shape

The **Envelope** (ADR-0001), inherited without change — `ListResult<T>` is already generic,
so `ListResult<LabelSummary>` costs nothing. `count` and `truncated` both mean here what they
mean there, and `truncated` is produced the same way: ask `gh` for `limit + 1`, and on
getting that many back set the flag and drop the spare. Verified to work under `search` too
(6 matches, `--limit 3` → 3 rows, `--limit 6` → 5 rows).

Each item carries **two fields**:

```json
{
  "name": "wayfinder:grilling",
  "description": "Wayfinder ticket: HITL conversation"
}
```

`name` is the string the Client passes straight back into `list_issues`' `labels` parameter —
the same symmetry argument ADR-0001 used to flatten labels there.

**`description` is included**, and it is the one field here that had to be argued rather than
assumed. It is not decoration: on this repository all 19 descriptions are non-empty and every
one of them is doing disambiguation a name cannot (`needs-triage` "Maintainer needs to
evaluate this issue" against `needs-info` "Waiting on reporter for more information";
`ready-for-agent` against `ready-for-human`). On `rust-lang/rust` only 9 of 976 are empty. A
model choosing which label to filter on cannot choose from names alone. The cost is roughly
threefold (462 → 1,393 bytes here; 27,432 → 86,274 on `rust-lang/rust`), and it is the reason
`limit` and `search` are not optional extras.

Six fields are excluded, by ADR-0003's rule — a field earns its place if the Client can *do*
something with it:

| Field       | Why not                                                                  |
| ----------- | ------------------------------------------------------------------------ |
| `color`     | A UI render colour. ADR-0001 already made this call when flattening labels in `list_issues`. |
| `id`        | A GraphQL node id. Nothing in this Server's surface accepts one.           |
| `isDefault` | Says only whether GitHub created the label at repo init. Does not change what a Client can filter on. |
| `createdAt` | Ordering is fixed to `name`; nothing else consumes it.                    |
| `updatedAt` | A label's own edit time. Unlike an issue's, no triage question turns on it. |
| `url`       | The label's web page. Actionable for a human, but this Server's caller is a model composing its next query. |

Keeping all eight would be 284,739 bytes on `rust-lang/rust`.

### Wire mode: TEXT, inherited

No new decision. ADR-0001 chose TEXT and #17 measured what it had deferred — the Inspector
*does* render `structuredContent` as its own block — so STRUCTURED remains a live upgrade
path for every Tool at once, not something for this ADR to reopen for one of them.

## Known limitations

To be stated in the Tool and parameter descriptions, so a Client meets them in the schema
rather than at runtime:

- **`search` is a substring match over names and descriptions, case-insensitive.** Not
  GitHub's query language, and not name-only: searching `newcomers` on this repository
  returns `good first issue`, which has that word only in its description. No match is an
  empty `items` and a successful call — `gh` prints `[]` and exits 0.
- **The `name`-ascending ordering does not hold under `search`.** `gh` refuses to sort a
  search, and its own result order is neither alphabetical nor by creation:
  `--search regression` on `rust-lang/rust` returns `regression-untriaged` before
  `perf-regression`. The guarantee is stated as holding when `search` is absent.
- **On a large repository, paging through `limit` is close to useless — use `search`.** Said
  plainly in the Tool description, because the failure is silent otherwise: alphabetically,
  27 of `rust-lang/rust`'s first 100 labels are compiler-flag labels like `-Clink-dead-code`
  and `-Zbuild-std`, and the hundredth is `A-driver`. A Client that pages once and stops has
  100 rows of noise and no sign that it has seen 10% of the vocabulary.
- **`limit` is capped at 100 and the cap is the Server's own.** `gh label list` has none.

## Out of scope

- **A repository's total label count.** It is cheaply available — `gh api -i
  repos/{owner}/{repo}/labels?per_page=1` returns a `Link` header whose `rel="last"` is
  `page=976` on `rust-lang/rust`, an exact total in one request — and it is genuinely the
  kind of fact that changes a Client's behaviour. It was left out because `truncated: true`
  plus the "use `search`" line already carries the decision the Client has to make, so by
  ADR-0003's own rule the field does not earn its place. Reopening it means deciding
  something larger: `GhCli.run` returns stdout, and reading a response header would mean
  teaching this Server to speak REST through `gh api` — a route its only GitHub-facing class
  currently documents itself as not having. That belongs in its own ticket, not smuggled in
  here.
- **Attachable reference data.** The one real advantage Resource Templates had. If a Client
  ever needs the label set in context without calling for it, that is a reason to revisit
  MCP Resources as a whole — not to make this one Tool an exception.

# 3. `get_issue` parameters and return shape

Date: 2026-09-05

## Status

Accepted. Resolves [#13](https://github.com/DemianLi/project_mcp/issues/13) and
[#14](https://github.com/DemianLi/project_mcp/issues/14); binds the implementation in
[#17](https://github.com/DemianLi/project_mcp/issues/17).

## Context

`get_issue` was named in ADR-0001 before it existed. `list_issues` excludes `body` on the
grounds that "`list_issues` exists so a Client can decide *which* issue to read; `get_issue`
exists to read it" — so this Tool's job was fixed by that sentence, and only its shape was
left open.

Two questions were open, and they turned out to be one. **What does a single read return** —
and in particular, do an issue's comments come with it? If they do, a single read contains
an unbounded, truncatable list, and the Envelope story (ADR-0001) does not cover that
nesting. **And what happens when the number is not an issue** — GitHub shares one number
space between issues and pull requests.

Constraints carried in rather than re-litigated: the Server is read-only; every Tool call
carries `owner`/`repo`; the Server shells out to `gh` and parses its JSON; failures travel
as `isError` carrying a `Remedy` (ADR-0002); the Envelope belongs to `list_*` Tools.

## Decision

### Parameters

| Parameter | Type     | Required |
| --------- | -------- | -------- |
| `owner`   | `String` | yes      |
| `repo`    | `String` | yes      |
| `number`  | `int`    | yes      |

`number`'s description carries the pull-request clause, so a Client meets the constraint in
the schema rather than at runtime — the same treatment ADR-0001 gives its three known
limitations:

> Must be an issue number. GitHub numbers issues and pull requests from one sequence; a pull request number is rejected.

### The payload is a flat object — no Envelope, no nesting

Twelve fields. The first seven are `list_issues`' seven, unchanged in name and shape, so a
Client that learned the listing reads this for free and the remaining five are purely
additive:

```json
{
  "number": 6,
  "title": "Implement list_issues and verify it in the Inspector",
  "state": "CLOSED",
  "labels": ["wayfinder:task"],
  "assignees": ["DemianLi"],
  "url": "https://github.com/DemianLi/project_mcp/issues/6",
  "updatedAt": "2026-09-04T13:23:33Z",
  "body": "## Question\n…",
  "author": "DemianLi",
  "createdAt": "2026-09-04T11:02:10Z",
  "closedAt": "2026-09-04T13:23:33Z",
  "stateReason": "COMPLETED"
}
```

`author` is flattened to a login string, the same treatment `labels` and `assignees` get in
ADR-0001. `closedAt` and `stateReason` together answer "when was this closed, and was it
done or abandoned" (`COMPLETED` / `NOT_PLANNED`) — a question only a single read asks.

### The trimming rule: a field earns its place if the Client can *do* something with it

Stated as a rule rather than a list of verdicts, because the next Tool will need it too.

`labels` feeds back into `list_issues`' `labels` parameter. `assignees` drives the
Client-side filtering ADR-0001 chose over an `assignee` parameter. `url` is the pull-request
discriminator (below) and the link a human can follow. `number` is identity.

Against that rule, nine of `gh issue view`'s twenty-one fields are excluded:

| Field                            | Why it is out                                                             |
| -------------------------------- | ------------------------------------------------------------------------- |
| `comments`                       | Unbounded — see below.                                                     |
| `closed`                         | Exactly redundant with `state`. Measured: `closed:true` ⟺ `state:"CLOSED"`. |
| `id`                             | Had one possible job — discriminating pull requests — and lost it.          |
| `milestone`                      | A dangling reference: this Server has no milestone surface.                 |
| `projectItems`                   | A dangling reference: this Server has no project-board surface.             |
| `reactionGroups`                 | 302 bytes of popularity signal with no action attached to it.               |
| `isPinned`                       | Nothing a reader acts on.                                                   |
| `closedByPullRequestsReferences` | Genuinely useful ("which PR closed this") and the closest call here. Out because it is empty in every issue measured, is a GraphQL-only field, and this Tool's map is about settling debts rather than opening the pull-request surface. |
| `projectCards`                   | **Must never be requested.** Projects (classic) is sunset; asking for this field fails the entire call with a GraphQL error rather than returning an empty value. |

### Comments are excluded

ADR-0001's own argument recurses, one level down. It excluded `body` from `list_issues`
because `body` "is the one field that grows without bound." Push the lens in one level: the
field that grows without bound *inside* `get_issue` is `comments`, and it does so far harder
than `body` ever did to the listing. Measured across six issues, bytes:

| Issue                    | Comments | `body` | `comments` JSON | Comment text alone | All other fields |
| ------------------------ | -------- | ------ | --------------- | ------------------ | ---------------- |
| `cli/cli#13840`          | 143      | 1,471  | **110,457**     | 63,467             | 2,063            |
| `cli/cli#326`            | 107      | 1,208  | 100,761         | 60,119             | 2,591            |
| `cli/cli#6175`           | 74       | 998    | 66,315          | 38,743             | 1,922            |
| `cli/cli#14256`          | 8        | 1,424  | 6,975           | 4,255              | 2,140            |
| `DemianLi/project_mcp#12`| 0        | 3,876  | 16              | 1                  | 4,567            |
| `DemianLi/project_mcp#6` | 2        | 407    | 7,109           | 6,153              | 1,177            |

Comments outweigh the body by 75× at the top of that sample, and 43% of those bytes are not
comment text at all but per-comment wrapping — eleven keys each, including
`includesCreatedEdit`, `isMinimized`, `minimizedReason`, `authorAssociation` and
`viewerDidAuthor`. For scale, the *entire* `list_issues` payload ADR-0001 measured is 1,776
bytes; one `get_issue` carrying comments is 62× that.

There is no tap at the `gh` layer. `gh issue view` has no flag to limit comments — only
`-c/--comments`, which switches the human-facing rendering. Measured asymmetry:
`gh issue list --json comments` truncates at 100 (issue #13840, 143 comments, yields 100),
while `gh issue view --json comments` returns every one (143 → 143, 107 → 107). Any limit
would therefore be applied by this Server *after* receiving all of them: it would spend the
fetch and save only the Client's context.

Excluding them also dissolves this ADR's structural question rather than answering it. With
no nested list there is no nested `truncated`, no second count to place, and no second
Envelope-like shape for a Client to learn.

The cost is real and named: reading an issue's discussion now takes two calls, and the
second Tool does not exist yet. See *Out of scope*.

### A pull request number is rejected

`gh issue view <pr-number>` **succeeds** and returns pull request data, because GitHub's
data model makes every pull request an issue — not the reverse, so `gh pr view <issue>`
fails. `gh issue list` filters pull requests out (measured on `cli/cli`: 20 of 20 newest open
issues, with none of the concurrent open PRs present), so `list_issues` is unaffected and
this is a `get_issue`-local decision.

`get_issue` **rejects** such a call: `Remedy.FIX_REQUEST`, with the message

> `#14356 is a pull request, not an issue. This Server reads issues only; it has no Tool for pull requests. If that number is what you wanted: https://github.com/cli/cli/pull/14356`

English, like every other failure sentence and parameter description this Server emits. The wording was drafted in Chinese during #13 and is corrected here rather than shipped inconsistent: the reader is a model, and one language across the surface beats a faithful transcription of the conversation that produced it. The sentence is built per call — the number is the one that was asked for and the URL comes from the payload just parsed, so it is not a constant like the sentences in `GhCli.classify()`.

Returning the pull request with a marker was rejected as the worst option: seen through the
issue lens a pull request is *half* a pull request — `isDraft`, `headRefName`, `mergeable`,
reviews and the diff have no field on `gh issue view` at all — so the marker would certify a
payload that is silently missing everything that makes a pull request one. Returning it
silently was rejected against ADR-0001's positioning: a guardrailed subset exists precisely
so this does not happen quietly.

`FIX_REQUEST` is used despite a real tension — a caller who genuinely wanted that pull
request has nothing to fix, since this Server has no Tool for it. It is still the right
Remedy: it covers the common case (a mistyped number), the uncommon case is handled by the
message text, and a fourth enum constant for one Tool's one edge would turn `Remedy` back
into an error code, which the warning in its own javadoc exists to prevent.

**The discriminator is `url` containing `/pull/`.** Two alternatives were measured and
rejected. Node-id prefixes (`PR_` vs `I_`) are an observable regularity, not a contract —
GitHub treats node ids as opaque and has changed their format before. REST's `pull_request`
key is the documented marker and semantically the cleanest, but reaching it means moving
from `gh issue view --json` to `gh api`, which replaces the entire field source. `url`'s
protection is that `github.com/{owner}/{repo}/pull/{n}` is a human-facing URL that cannot be
changed without breaking every link to a pull request on the internet.

The check lives in `IssueTools`, not `GhCli`: on this path `gh` did not fail. This is the
first failure this Server reports that `gh` did not produce, which is why `GhFailure` is
renamed **`ToolFailure`** (see ADR-0002's amendment). `GhCli` remains the only place that
knows how `gh` fails; only the type's name widens.

`stderr` is `""` on this path, and the pull request URL travels in `message` only —
`structuredContent` keeps ADR-0002's four keys, rather than growing a fifth for one edge.

### Wire mode: TEXT, inherited

Not reopened here. Wire mode is a cross-Tool property: two Tools on different modes would
force a Client to learn both, and the Envelope and Remedy earn their keep precisely by being
learned once. ADR-0002 has since put `structuredContent` on the failure path, so STRUCTURED
is no longer unmeasured ground — but changing it is a decision covering every Tool, not one
`get_issue` takes in passing.

## Known limitations

- **An issue's discussion cannot be read at all.** `get_issue` returns the issue; nothing
  returns its comments. This is the price of the exclusion above, not an oversight.
- **`projectCards` must never be requested**, by this Tool or any future one. It is not a
  field that returns nothing — it fails the whole call.
- **The pull-request discriminator is a URL substring**, the weakest of the three measured
  options. It was chosen on cost, not on cleanliness.

## Out of scope

- **A Tool for issue comments.** Comments are the issue surface, not another GitHub surface,
  so this is in scope for the map and sits in its *Not yet specified* section — a known next
  question, deliberately unspecified until this Tool exists.
- **STRUCTURED mode**, as in ADR-0001: an upgrade path, and now a cross-Tool one.

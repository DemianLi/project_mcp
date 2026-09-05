# 6. `list_issue_comments` parameters and return shape

Date: 2026-09-05

## Status

Accepted. Resolves [#22](https://github.com/DemianLi/project_mcp/issues/22) and binds the
implementation in [#23](https://github.com/DemianLi/project_mcp/issues/23). Builds on
ADR-0005, which fixed the route. **Amends ADR-0001** — the Envelope grows for the first
time; the rule that replaces "learned once, holds everywhere" is written there, not here.

## Context

ADR-0005 settled *what is reachable* over `gh api graphql`. This ADR settles what is
returned. Every figure below was measured against real `gh` while resolving #22; the route
comparison it builds on lives on branch `prototype/comments-route` (`8dbdb16`).

One property separates this Tool from the two `list_*` Tools before it, and most of what
follows turns on it: **it has no narrowing parameter.** When `list_issues` truncates, a
Client can ask again with a different `state` or a different label set. When `list_labels`
truncates, ADR-0004 tells the Client in the Tool description to use `search` instead. An
issue's comments offer nothing of the kind — one issue, one list, one order. So a bare
`truncated: true` would be, for the first time in this Server, a door with nothing behind it.

## Decision

### Parameters

| name | required | |
| --- | --- | --- |
| `owner` | yes | Repository owner. |
| `repo` | yes | Repository name. |
| `number` | yes | Must be an issue number. A pull request number is rejected — see ADR-0005 for why the message does not distinguish it from a number that does not exist. |
| `limit` | no | Default 30, capped at 100, clamped rather than rejected. |
| `cursor` | no | Opaque. Omit for the first response; pass back the `nextCursor` you were given to continue. |

One GraphQL document serves both the first call and every continuation — `$before` is a
nullable variable and may simply be absent (measured, same document both ways):

```graphql
query($owner:String!, $name:String!, $number:Int!, $last:Int!, $before:String) {
  repository(owner:$owner, name:$name) {
    issue(number:$number) {
      comments(last:$last, before:$before) {
        totalCount
        pageInfo { hasPreviousPage startCursor }
        nodes { author { login } authorAssociation createdAt body url }
      }
    }
  }
}
```

### The window opens on the newest comments and walks backwards

`last:` / `before:`, not `first:` / `after:`.

**#21 and #22 both recorded that the window only opens forward. That is false**, and it was
believed while the route was being chosen. Measured: `comments(last:3)` returns
`hasPreviousPage: true` and a `startCursor` — the backward window is as complete as the
forward one, with its own cursor.

A model reading an issue's discussion is most often asking what the state of it is now, and
that is at the end. Ordering *within* a response stays ascending — `last:3` returned
`2026-08-27` / `2026-08-30` / `2026-09-04` in that order — so "the newest 30" is not "the
discussion backwards".

Worth setting against ADR-0001, which had to say the opposite: `list_issues` truncates the
*oldest* issues regardless of activity, because `gh issue list` has no `--sort` and the only
route to one readmits a rejected parameter. There the direction was forced. Here it is
chosen, and the useful direction turns out to be the other one.

### The Envelope grows two keys

```java
record CommentPage(List<Comment> items, int count, boolean truncated,
                   int totalCount, String nextCursor) {}
```

- **`totalCount`** — the true number of comments on the issue. It arrives in the same
  response at **zero extra calls**. ADR-0004 refused the equivalent field for `list_labels`,
  and half of that reasoning does not transfer: it argued that "`truncated: true` **plus the
  'use `search`' line** already carries the decision the Client has to make". There is no
  `search` line here. "You have 30 of 143" and "there is more" are not the same fact — only
  the first lets a Client judge whether walking the rest is worth the context.
- **`nextCursor`** — `null` when nothing older remains. A zero-comment issue produces this
  naturally rather than by special case: measured, `totalCount: 0`, `nodes: []`,
  `hasPreviousPage: false`, `startCursor: null`.
- **`truncated` stays**, although it is now derivable from either `nextCursor != null` or
  `count < totalCount`. ADR-0001 keeps the equally derivable `count` for a stated reason —
  "counting array elements is a step a model can get wrong, and one integer removes the
  opportunity" — and deriving a boolean is the same class of step. Adding a key is additive;
  removing a core one would break the Envelope far harder than this amendment does.

### The spare row cannot be used on this route

Not a preference — it is unavailable. `ListResult.of` learns `truncated` by asking for
`limit + 1` and seeing whether the extra row comes back. On this route it cannot:

```
$ gh api graphql -f query='{ … comments(first:101) { totalCount } }'
gh: Requesting 101 records on the `comments` connection exceeds the `first` limit of 100 records.
```

`Limits.clamp` caps at 100, so the spare would be exactly the 101 that hard-errors. Worse
than failing, it would fail *badly*: the stderr contains no string `classify()` recognises —
in particular not `rate limit`, which was checked against the branch order — so a Client
asking for the maximum would be told "the GitHub CLI failed in a way this Server does not
recognise".

`truncated` therefore comes from `pageInfo.hasPreviousPage`, which the response volunteers.
The consequence for `ListResult.of` is recorded in #23: its javadoc states that "`gh` cannot
say whether more rows exist past the limit it was given" as though it were a property of
`gh`, and it is a property of the two porcelain commands the other Tools use. That sentence
needs the same narrowing ADR-0003's "no tap" sentence got.

### The cursor is wrapped, not passed through

GitHub's cursor is not opaque in any real sense. `Y3Vyc29yOnYyOpHPAAAAAUn6C_U=` base64-decodes
to `cursor:v2:` followed by a msgpack `uint64` of `5536091125` — the `databaseId` of the
comment it points at. There is no TTL and no server-side state.

That is legal to hand straight to a Client: #20 established that opaqueness is a **MUST** for
a Client and only a **SHOULD** for a Server, and the specification's own example cursor is a
base64-encoded page number. It is nevertheless rejected here, on one measurement:

**A cursor from another issue is accepted silently and produces a wrong answer.** Feeding
`cli/cli#13840`'s cursor to `cli/cli#14361`, an issue that genuinely has one comment:

```
totalCount: 1   nodes: []   hasPreviousPage: false      ← exit 0, no error anywhere
```

A Client reads that as "this issue has one comment, I can see none of it, and there is
nothing more". Across repositories it is no better disguised: the same cursor against
`ollama/ollama#5000` returned all eight of its comments looking entirely normal, because the
id happened to sort after all of them.

Under ADR-0002 this is the worst square on the board. Every failure this Server reports
carries a Remedy so a Client knows what to do next; a silently wrong success tells it nothing
and is not even a failure. So the cursor a Client receives is the Server's own:

```
base64( "<owner>/<repo>#<number>" + "|" + <GitHub cursor> )
```

On the way back the Server compares the three parameters and rejects a mismatch with
`FIX_REQUEST`. That is roughly ten lines, and it is the only way to catch this at all — the
Server cannot ask which issue a bare comment id belongs to without spending another call.

### `classify()` gains a second substring

A cursor that is not a cursor fails hard, and `gh` exits non-zero:

```
gh: `not-a-cursor` does not appear to be a valid cursor.
```

`classify()` does not recognise it, so today it lands on `UNKNOWN` where the Client can
plainly fix its own request. The wrap above does not remove the need: it catches a cursor
belonging to a different issue, not a correctly-addressed wrapper whose inner half is
corrupt. This is the **second** mandatory `classify()` change on this route; ADR-0005
mandated the first.

### The names are borrowed from the protocol

`cursor` in, `nextCursor` out. #20 found that the specification uses exactly these two names
for the four list operations it paginates. The protocol-level slot itself is unreachable —
`mcp-core` 2.0.0 makes `CallToolResult` a fixed record, so a sixth top-level key cannot be
sent — but the *vocabulary* costs nothing to borrow, and a model that has met `nextCursor` in
a protocol listing reads this one without learning anything.

`before` was rejected because it reads to a model like a time filter (`since`'s opposite)
rather than a page marker. "Next" here means the next response, which is older; the Tool
description says the first response is the newest comments, which removes the ambiguity.

### Five fields per comment

`author`, `authorAssociation`, `createdAt`, `body`, `url`. Measured on 30 comments of
`cli/cli#13840`:

| selection | 30 comments | per comment |
| --- | --- | --- |
| `author` `createdAt` `body` `url` | 19,402 B | 647 B |
| the same with `bodyText` for `body` | 14,336 B | **478 B (−26%)** |
| `body` alone | 15,232 B | 508 B |
| `url` alone | 2,278 B | 76 B |
| `+ authorAssociation` | +810 B | +27 B |
| `+ updatedAt` | +1,050 B | +35 B |
| `+ isMinimized` `+ minimizedReason` | +1,290 B | +43 B |
| `+ databaseId` | +720 B | +24 B |
| every field above | 35,662 B | 1,189 B |

**`body`, not `bodyText`.** The 26% is the largest single lever available and it was tested
rather than assumed. `bodyText` keeps the *content* of a fenced code block but drops the
fences, so nothing marks where code ends and prose begins. It destroys link targets:
`[Issue Triage](https://github.com/cli/cli/actions/runs/29097580359)` becomes the words
`Issue Triage`, and a GitHub-internal URL is rewritten to `actions/gh-actions-cache#77
(comment)` with the address gone. A bare URL survives. Trading a Client's ability to follow a
link, and its ability to see where code starts, for 26% is not worth it. This is the
unbounded field #22 flagged as uncuttable, and it is: the text of a comment is the comment.

**`url`, not `databaseId`.** The URL costs three times as much for the same information
(76 B against 24 B) and is reconstructible from the id. It stays because `list_issues` and
`get_issue` both carry `url`, because 76 B is cheap, and because it is the one thing a
Client can hand to a person.

**`authorAssociation`** — `OWNER` / `MEMBER` / `CONTRIBUTOR` / `NONE`, 27 B. Under ADR-0003's
rule a field earns its place by what a Client can do with it, and whether a sentence was
written by a maintainer or by a passer-by genuinely changes how much weight a model should
give it. This is the least certain of the five.

**`author` can be null.** The schema types it `Actor`, not `Actor!` — a deleted account
reports nothing. `IssueMapper` already resolves `author` to a login with `asString("")`, and
that is copied here; a login is never the empty string, so the empty value is unambiguous.

**Rejected**: `updatedAt` (whether a comment was edited changes no action a Client would
take), `reactionGroups` (expensive, and the counts decide nothing), `databaseId` (subsumed by
`url`), `bodyHTML`, and the `viewer*` family (this Server has no viewer of its own — the
token is `gh`'s business).

### The `limit` rule is copied; its justification is not

`Limits.DEFAULT` 30, `Limits.MAX` 100, `Limits.clamp` unchanged — one rule across every
`list_*` Tool, which is the point of it being a shared class.

But ADR-0004's headline sentence for that rule — "the cap is the Server's own; `gh` itself
caps nothing", evidenced by `gh label list --limit 1000` returning all 976 rows — **is false
on this route**. GraphQL enforces 100 itself and hard-errors above it. Here the clamp also
prevents a real failure rather than only protecting the Client's context, and that difference
must not be smoothed over the next time the rule is restated.

### The Tool is named `list_issue_comments`

It breaks the pattern of `list_issues` / `list_labels`, which carry no qualifier. GitHub has
three things called a comment — issue comments, review comments on a diff, and the comment-like
entries in an issue's timeline — and this map ruled the latter two out of its scope without
ruling them out forever. A label has only one kind, which is why `list_labels` needs no
qualifier and this does.

### Paging is not a new cost surface

The map carried fog reading: if a route needs paging, one Tool call is no longer one `gh`
process — does that meet a rate limit, does it need a Remedy, is it this Server's business.
All three now have answers, and none of them opens a ticket. Measured, the query reports
`cost: 1` against a limit of 5,000 per hour, so walking all 143 comments of `cli/cli#13840`
costs five points. `classify()`'s existing `rate limit` branch already returns `RETRY`. And
the premise does not hold: **one Tool call is still exactly one `gh` process.** Paging is the
Client choosing to call again, which is no different from it calling `list_issues` twice.

## Known limitations

- **Minimized comments are returned as ordinary rows.** GitHub's UI collapses comments hidden
  as spam or off-topic; `isMinimized` exists in the schema and was rejected on cost, so this
  Server shows what a browser hides. No minimized comment appeared in roughly 250 sampled
  comments, so the frequency is unmeasured, not zero.
- **A cursor is only valid for the issue it came from.** Now reported rather than silently
  wrong, but a Client still must not build one.
- **Reading a long thread is one call per 100 comments, and the Client pays the context.**
  `totalCount` exists so that decision is made with the number in hand.
- **The query document lives in this Server** — carried unchanged from ADR-0005, and this
  ADR enlarges it.
- **The window only opens on the newest end.** Reading a long thread from its beginning means
  paging to it. A forward window could be added additively later; nothing here forecloses it.

## Out of scope

- **The implementation** — [#23](https://github.com/DemianLi/project_mcp/issues/23), which
  carries both mandatory `classify()` branches, the cursor wrapper, and the GraphQL-shaped
  fixtures.
- **Review comments and timeline events**, and adding `includeComments` to `get_issue` — all
  three ruled out on the map, unchanged here.
- **Re-routing `list_issues` or `list_labels` onto a cursor.** They have narrowing parameters
  and this one does not; that asymmetry is the whole argument above, and it does not travel
  backwards.

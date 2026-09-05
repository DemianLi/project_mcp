# 5. An issue's comments are read over GraphQL

Date: 2026-09-05

## Status

Accepted. Resolves [#21](https://github.com/DemianLi/project_mcp/issues/21); constrains the
shape decision in [#22](https://github.com/DemianLi/project_mcp/issues/22) and the
implementation in [#23](https://github.com/DemianLi/project_mcp/issues/23).

Written one ticket earlier than ADR-0004 was, which covered its map's prototype and grilling
tickets together. The route stands on its own here because [#22](https://github.com/DemianLi/project_mcp/issues/22)
cannot ask its questions until it is settled — which parameters are even *possible* is a
consequence of this decision.

## Context

ADR-0003 excluded `comments` from `get_issue` because they grow without bound, and named the
absence of a gate as the reason a limit would be pointless there:

> There is no tap at the `gh` layer. `gh issue view` has no flag to limit comments … Any limit
> would therefore be applied by this Server *after* receiving all of them: it would spend the
> fetch and save only the Client's context.

That sentence is true of `gh issue view`, and **false of `gh` as a whole** — which is what this
ADR discovers. `gh api` reaches both the REST and the GraphQL endpoints, and both take a
count. So the debt ADR-0003 deferred is payable after all, and the first question is which
counter to reach for. See the amendment appended to ADR-0003.

Three candidate routes, all measured against real `gh` by a probe that drives the real
`GhCli` — so every Remedy below is what a Client is actually told, not a reading of
`classify()`. The probe is on branch `prototype/comments-route` (`8dbdb16`).

| | A `gh issue view --json comments` | B `gh api repos/…/issues/{n}/comments` | C `gh api graphql` |
| --- | --- | --- | --- |
| Gate | **none** — 143 of 143 | `per_page`, caps at 100 | `first:`, plus a real cursor |
| Fields | fixed 11 per comment | fixed 14 per comment | **chosen by the query** |
| Spelling | `author` / `createdAt` | `user.login` / `created_at` | `author` / `createdAt` |

## Decision

### Route C — `gh api graphql`

Four measurements decide it, and one non-measurement.

**Bytes.** Normalised per comment, over the pipe: A **765 B** (11 keys), B **3,595 B**
(14 keys), C **644 B** (four selected fields). Unnormalised, the shape of the problem is
starker — on `cli/cli#13840` (143 comments), **B's thirty comments cost as much as A's
hundred and forty-three**: 107,854 B against 109,431 B, while C's thirty cost 19,311 B.

**Time**, five runs on that same issue: A 1.65 / 2.01 / 1.69 / 1.84 / 1.72 s; B 0.58–0.65 s;
C 0.56–0.73 s. A is ~2.8× slower, and the reason is exactly the missing gate.

**A's cost is structural, not merely slow.** With no gate, "at most `n` comments" happens
*above* `gh`: the Server spends the whole fetch and saves only the Client's context. That is
word for word the objection ADR-0003 raised against limiting comments inside `get_issue`. It
was decisive there; it is decisive here.

**C is the only route with a cursor.** It volunteers `totalCount`, `pageInfo.hasNextPage`
and `endCursor` in the same response. ADR-0004's Envelope pays for a spare row to learn
`truncated`; C is told. Whether any of that reaches the Client is [#22](https://github.com/DemianLi/project_mcp/issues/22)'s
to decide — this ADR only records that the route makes it possible, and the other two do not.

**The non-measurement**: [#21](https://github.com/DemianLi/project_mcp/issues/21) framed the
trade-off as A's field-spelling consistency with `get_issue` against B's second vocabulary,
and expected that to be the hard part. It is not a trade-off at all — **C is GraphQL too**,
so it shares A's spelling exactly. That argument only rules out B; it never separated A from C.

### B is rejected on a failure surface that cannot be repaired

Not a preference. Three distinct causes arrive as one indistinguishable line:

| Cause | A | B | C |
| --- | --- | --- | --- |
| No such issue number | `FIX_REQUEST` | **`UNKNOWN`** | `FIX_REQUEST` (after the change below) |
| No such repository | `FIX_REQUEST` | **`UNKNOWN`** | `FIX_REQUEST` |
| Empty `repo` | `FIX_REQUEST` | **`UNKNOWN`** | `FIX_REQUEST` |

B's three rows are all `gh: Not Found (HTTP 404)`. This is **not** a `classify()` that has
fallen behind: the information is gone at the `gh` layer, so no wording added here could
recover it. Under ADR-0002 that means every one of these reaches a Client as "the GitHub CLI
failed in a way this Server does not recognise" — a Remedy of `UNKNOWN` where a Client could
have fixed its own request.

### `classify()` gains one substring

C's stderr for a number that is not an issue reads:

```
gh: Could not resolve to an Issue with the number of 99999999.
```

Singular `Issue`, and no `or pull request` — so it misses the clause `classify()` already
has and lands on `UNKNOWN`. One more branch fixes it. The two strings cannot collide
(`an issue or pull request` does not contain `an issue with the number of`), so this does not
disturb the porcelain wording `get_issue` and `list_issues` depend on.

### A pull request number and a number that does not exist are told apart in the message, not by a second call

This is the price of route C, and it is paid once, in wording.

Route C rejects a pull request number **at the source** — `issue(number:)` simply does not
resolve one — which is a real advantage over B, where a pull request succeeds and a
zero-comment pull request returns `[]` with no tell whatsoever. But C reports it with the
same sentence it uses for a number that does not exist:

```
gh: Could not resolve to an Issue with the number of 14362.   ← a pull request
gh: Could not resolve to an Issue with the number of 99999999. ← no such number
```

The Server does **not** spend a second call to separate them. It says both at once:

> That repository has no issue with that number. It may not exist at all, or it may be a
> pull request — GitHub numbers both from one sequence, and this Server's issue Tools take
> issues only.

The reasoning is ADR-0002's own definition: a Remedy classifies **by the action available,
not by the cause**, and in both cases the Client does the identical thing — change `number`.
Buying a more precise sentence with an extra round trip on the failure path is the trade
that definition exists to refuse.

Note the consequence for ADR-0003's wording, which asserts the opposite on the porcelain
route ("this also means there is no pull request with it either"). That sentence stays
correct where it is — `get_issue` reads over porcelain, whose stderr genuinely does cover
both — and must not be copied onto this route, where it would be false.

## Known limitations

- **The query string lives in this Server.** Routes A and B name an operation and let `gh`
  build the request; C sends a GraphQL document. That is a larger surface to keep working
  against GitHub's schema, and it is the real cost of choosing C.
- **`classify()` now recognises two spellings of the same condition** because two routes
  word it differently. That is not tidy, and it grows with each new route.
- **A pull request number and a missing number are indistinguishable to the Client** on this
  route, by the decision above.

## Out of scope

- **What the Tool's parameters and return shape are** — [#22](https://github.com/DemianLi/project_mcp/issues/22).
  Whether `endCursor` surfaces at all, what a comment keeps, which way the window slides:
  none of it is settled here. This ADR fixes only what is *reachable*.
- **Re-routing `get_issue` or `list_issues` onto `gh api`.** They work, their failure
  messages are precise, and nothing measured here argues for touching them.

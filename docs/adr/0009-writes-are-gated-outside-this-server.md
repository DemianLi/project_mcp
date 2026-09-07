# 9. Writes are gated by the login `gh` resolves, not by this Server

Date: 2026-09-06

## Status

Accepted. Resolves [#35](https://github.com/DemianLi/project_mcp/issues/35), the route ticket
of the map [写不写得动，谁说了算：一个只读实例](https://github.com/DemianLi/project_mcp/issues/32),
and with it that whole map: the map's other branch was the only one that carried
implementation, and this ADR closes it.

Builds on the vocabulary [#34](https://github.com/DemianLi/project_mcp/issues/34) put in
`CONTEXT.md` — `Ungranted` and `Withheld` — and on the two measurements
[#33](https://github.com/DemianLi/project_mcp/issues/33) and
[#36](https://github.com/DemianLi/project_mcp/issues/36) left behind. It writes no code:
what it settles is that a gate this Server was asked to consider does not get built.

## Context

ADR-0007 added `add_issue_comment`, the first Tool here that changes anything, and pushed one
question out of scope while doing it: whether a deployer can hand out an instance of this
Server that runs the four read Tools and cannot write. Map #32 was charted on that question.

Two routes were named at charting, and both were legitimate ends of the map:

- **No switch.** The permissions of whatever login `gh` resolves are the gate. This Server's
  whole job is to report clearly when that gate refuses.
- **A Server-side gate.** A deployment-time decision that this instance is read-only, keyed
  on `readOnlyHint` so it partitions writes as a class rather than Tool by Tool.

`CONTEXT.md` names the two promises those routes hand out, and they are not grades of one
thing. **`Ungranted`**: the ability to write was never handed to this instance, so GitHub
refuses — the constraint sits outside the Server, covers every route to GitHub, and was never
this Server's to hand out. **`Withheld`**: the instance holds a login that could write and
does not — this Server's to hand out, but covering only what travels through it.

Three ground facts were on the table when the route was judged:

- **`Ungranted` already works.** #33 drove a real `gh` at a fine-grained PAT with
  `Issues: Read-only`: the four read Tools were unaffected, the id lookup that opens the write
  succeeded, and the failure landed on the mutation — `gh: Resource not accessible by personal
  access token`, exit 1. The instance was usable, and it failed at the right call.
- **It used to be described wrongly, and no longer is.** That refusal matched nothing and
  arrived as `UNKNOWN`; the nearest branch that should have caught it says `gh auth login`,
  which is the wrong instruction for a login whose authentication was never the problem. #36
  gave the family `resource not accessible by` its own `ASK_OPERATOR` branch and its own
  wording (`GhStderr`, row "not permitted").
- **A Server-side gate is self-disciplinary.** The process still holds a token that can
  write; the gate stops misuse, not bypass.

## Decision

### This Server does not gate writes

No configuration key, no startup flag, no conditional registration, no refusal path inside a
write Tool. The gate is the permissions of the login `gh` resolves, and this Server's part in
a read-only instance is to **report that gate's refusals well** — which, since #36, it does.

Of the two promises, this Server therefore hands out **neither**, and that is now a decision
rather than a description of today. `Ungranted` remains reachable through this Server without
being offered by it: a deployer picks a login without the permission, entirely outside this
Server, and the Server meets the refusal and explains it. `Withheld` is not offered at all.

Three reasons, in the order they carry weight.

**A gate the process can bypass is a promise this Server cannot keep.** A `Withheld` instance
holds a writable token by definition — that is what distinguishes it from `Ungranted`. The
gate is then one branch in a program, and everything that goes around that branch still
writes: a bug, a path nobody anticipated, a future Tool added without the check. A deployer
who wants "no write reaches GitHub" is asking for a property of the deployment, and the honest
answer is the one that is a property of the deployment. That the weaker promise is the one
this Server can enforce is precisely why it is not worth enforcing here — the deployer who
wants it can have the stronger one for the same effort, by choosing the login.

**It extends the boundary this repo has held from the start.** Authentication is `gh`'s
problem; this Server holds no token and reads no `GH_TOKEN`. Permission is the same kind of
fact as authentication and arrives down the same pipe. A gate keyed on "may this instance
write" is this Server forming an opinion about a credential it deliberately never sees.

**The precedent is already in the codebase, priced.** `add_issue_comment` refuses a pull
request number, and it does so with no check: `repository.issue(number:)` cannot resolve a
pull request's id, so the operation is not expressible rather than forbidden (ADR-0007). That
is an `Ungranted`-shaped gate, and this repo chose it once already. Its price is recorded too
— a `classify()` branch now load-bearing for an irreversible side effect, carrying a
capitalised DO NOT (`GhStderr`, row "no such issue (graphql)"). Moving a constraint outside the Server does not make it
free; it moves where the cost lives.

### Why a repository's own settings are not this gate

Worth writing down because the instinct is right and the mechanism is not: the gate does
belong outside the Server, and a repository's settings look like a place it could live. They
are not that place, for two measured reasons.

**Wrong granularity.** `owner` and `repo` are per-call parameters on every Tool here. A
repository setting is the repository owner's gate over one repository; an instance-level
promise has to hold across every repository the caller may name, including ones created after
the instance started. The two are not the same kind of statement, so one cannot stand in for
the other.

**No repository setting produces read-works-write-doesn't.** Turning issues off takes the four
read Tools down with the write — that condition already has its own branch and its own
sentence, "that repository has issues turned off, so it has none to list"
(`GhStderr`, row "issues disabled"). Locking a conversation is the nearest thing, and it stops
human collaborators as well as this Server — but only those without write access, which is why
it does not gate an `Ungranted` login either; see Known limitations.

### The Server's obligation instead

If the gate lives outside, the Server owes the deployer exactly one thing: when the gate
refuses, the caller must be told something true and actionable. That is `Remedy` doing its
ordinary job, and #36 closed the last gap in it — a write refused for want of permission now
carries `ASK_OPERATOR` and says the login is authenticated and not permitted, rather than
`UNKNOWN` beside advice to log in again.

Nothing further is owed and nothing further is built.

## Known limitations

- **A deployer who cannot choose the login gets nothing from this Server.** `Ungranted` costs
  a login picked outside it — a second PAT, a second `gh` host configuration. Where that is not
  available, there is no read-only instance to be had, and this decision is what makes that
  final rather than pending.
- **The promise cannot be checked from inside.** Nothing here can tell a deployer, before a
  call is made, whether the login `gh` will resolve can write. Verifying an `Ungranted`
  instance is done outside this Server, and it is fiddly enough to be worth writing down —
  #33 needed a two-sided cross-check because the obvious tests are all false negatives against
  a public repository, and even that cannot separate a genuinely read-only PAT from one built
  with `Issues: Read and write`. The map's Notes carry the procedure.
- **A locked conversation is reported as `UNKNOWN`, and the band in which it can arise is
  narrower than this ADR first claimed.** As written, this bullet called a lock "the one case
  that genuinely produces read-works-write-doesn't". A lock is only operative on an identity
  that would otherwise be permitted to comment, and both edges of that are measured. An
  identity with the permission is unaffected: `addComment` against a **locked** sandbox issue,
  as that repository's owner, exited 0 and posted the comment. An identity without it is
  refused before the lock can matter:
  [#33](https://github.com/DemianLi/project_mcp/issues/33)'s fine-grained PAT with
  Issues: Read-only was refused with `gh: Resource not accessible by personal access token`,
  exit 1, on an issue that was not locked at all. The band is therefore an identity that could
  comment on an unlocked issue and not on a locked one — empty on a private repository, and on
  a public one requiring a non-collaborator, which is a second GitHub account rather than a
  second token. `Ungranted` as this ADR defines it — a login picked without the permission —
  never reaches the lock.

  That the band is empty on a private repository is an **argument** from those two
  measurements plus GitHub's documented rule that a lock admits users with write access. It is
  not a third measurement and is not written here as one.

  What does not change: `GhStderr` has no branch for a locked conversation, so an identity
  that does reach that band is told `UNKNOWN`. ADR-0007 flagged that an issue can be "closed,
  locked or deleted" between the lookup and the mutation and handed the wording to
  [#28](https://github.com/DemianLi/project_mcp/issues/28), which was resolved without adding
  it. GitHub's stderr for it is still unmeasured, so no string is guessed here.
- **A Client still cannot verify any of this.** `readOnlyHint` is the Server's own assertion
  about one Tool, and the protocol's single MUST is that a Client not trust it
  ([#26](https://github.com/DemianLi/project_mcp/issues/26)). Nothing decided here changes
  that, and nothing here should be read as making the annotation more trustworthy.

## Out of scope

- **The gate's mechanism, its configuration surface, and its default.** All three were fog on
  map #32 conditional on the other route. The route closed the road, so none of them
  graduates: there is no first operator-facing configuration key to design, and
  `CommentTools`' class-level `@ConditionalOnProperty` problem — it would take
  `list_issue_comments` down with the write — never has to be solved.
- **What a successful write leaves behind in this Server.** ADR-0002 logs argv on failure
  only. Adjacent to this decision and a different audience: a gate is for the deployer, a trace
  is for whoever asks afterwards. Ruled out of scope on the map, and still is.
- **Per-call confirmation before a write** (`elicitation`). It defends a Client against being
  tricked, not a deployer wanting a read-only instance, and #26 measured the SDK support as
  doubtful.
- **A session-level gate.** Under Stdio the Client that launches this process already controls
  its argv; negotiating the same thing again is the same party setting it twice.

## Amendments

**2026-09-07.** The Known limitation on locked conversations is rewritten. It stated as a
finding something that was a deduction: that a lock is the one condition producing
read-works-write-doesn't. Measured on the sandbox, a locked issue does not refuse the
repository's owner — `addComment` exits 0 and the comment is posted. Set beside
[#33](https://github.com/DemianLi/project_mcp/issues/33), which had already measured the other
edge, the condition the bullet named is unreachable from any `Ungranted` login, and the bullet
now says so and marks which half of it is argued rather than measured.

The decision does not move, and the first bullet under it gets slightly firmer: `Ungranted`
was said to work because #33 found the read Tools unaffected and the failure landing on the
mutation, and it now also survives the one repository condition that was thought to undercut
it. The gate still lives outside this Server, and what this Server
owes a deployer is still one true and actionable Remedy when the gate refuses. What moves is a
sentence of evidence, in the ADR family whose governing principle is "measured, not assumed".

Worth recording as method, because it cost a real write. The measurement verified that the
issue was locked. It did not verify that the lock bound the login doing the writing, and those
are different checks; the probe comment landed on
[sandbox #4](https://github.com/DemianLi/project-mcp-sandbox/issues/4#issuecomment-5570332356)
and is still there. A precondition is not established by confirming the condition exists —
only by confirming it binds the party it is meant to bind.

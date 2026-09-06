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
  wording (`GhCli.java:281`).
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
capitalised DO NOT (`GhCli.java:315`). Moving a constraint outside the Server does not make it
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
(`GhCli.java:354`). Locking a conversation is the nearest thing, and it stops human
collaborators as well as this Server; see Known limitations, where it is also the one case
this Server currently reports badly.

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
- **A locked conversation is the one case that genuinely produces read-works-write-doesn't,
  and this Server reports it as `UNKNOWN` today.** `classify()` has no branch for it. ADR-0007
  flagged that an issue can be "closed, locked or deleted" between the lookup and the mutation
  and handed the wording to #28, which was resolved without adding it. This matters more after
  this ADR than before it: the decision above rests on refusals being reported well, and this
  is a refusal that is not. GitHub's actual stderr for it has **not** been measured, so no
  string is guessed here.
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

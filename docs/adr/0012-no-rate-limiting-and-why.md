# 12. This Server does not rate limit, and that is a knowing departure

Date: 2026-09-09

## Status

Accepted. **A knowing departure from a MUST**, recorded so that it is a decision rather
than an oversight.

## Context

The 2025-11-25 specification's `server/tools.mdx` closes with four parallel obligations
under Security Considerations. All four are `MUST`, and the section is four sentences long:

> Servers MUST: Validate all tool inputs · Implement proper access controls · Rate limit
> tool invocations · Sanitize tool outputs

Three of them this Server has an answer for. `Limits` clamps `limit`, `Cursors` refuses a
cursor it did not issue, and `get_issue` turns away a pull request number. Access control
is delegated to whatever login `gh` resolves, which ADR-0009 settled deliberately. Output
sanitising is a decision rather than an omission: issue bodies come back verbatim because
the model needs the text, and the rendering risk lands on the Client.

The third has no answer at all. This Server does not throttle anything.

**One thing that looks like throttling and is not.** When `gh` hits GitHub's own rate
limit, `GhStderr` classifies it as `RETRY` and carries a wait forward when GitHub named
one. That is a downstream limit reflected back to the caller. It is not this Server
limiting anything, and reading it as compliance would be reading the wrong direction.
(That branch's samples are still `UNMEASURED` — ADR-0002 could not provoke the failure,
and this repo has never seen the real wording.)

**What throttling would protect here.** One process, one `gh` login, launched as a
subprocess by one Client. There is no contention between callers to arbitrate. The
exposure is a model in a loop burning that one token's GraphQL budget — which GitHub
itself already stops, and which this Server already reports as a classified failure.

## Decision

**Not implemented.** Three reasons, in the order they mattered.

**The blast radius is one login's own budget, and it is already enforced.** In the stdio
single-tenant shape this Server was built for, the thing a limiter would protect is
protected — by GitHub, at the far end, with a failure this Server knows how to classify.
A second limiter in front of it would mostly duplicate a control that already exists.

**Any threshold chosen here would be invented.** Per what — per tool, per minute, per
session? This repo has no traffic to derive an answer from, and its whole discipline is
that a number in the code is a number someone measured. A limiter pinned in place by a
test asserting a made-up rate is the same mistake as the four network markers that
ADR-0002's descendants removed in `a748fa4`.

**A gesture would teach the wrong thing.** This Server exists to be read. A token limiter
satisfying a `MUST` on paper teaches that specification obligations are met by gesture,
which is the opposite of what the rest of this repo argues.

**Whoever deploys this past one desk has to add it.** Named plainly because a departure
without an owner is an oversight with better prose. The place for it is in front of the
Tool layer, not inside `GhCli` — `GhCli` is the single exit to `gh`, and a limiter there
would count subprocess launches rather than Tool invocations, which is what the
specification actually names.

## Known limitations

**This is non-conformance, not an interpretation.** Asked whether this Server meets the
2025-11-25 specification, the honest answer names this row and says no. The other three
MUSTs in that section are met or deliberately delegated; this one is absent.

**The single-tenant premise is doing the work.** Every reason above rests on one process
serving one login. It stops holding the moment the deployment shape changes — which is the
same premise ADR-0009 rests on, and the same one that makes multi-tenant SaaS a hard wall
in `docs/reviews/commercial-readiness.md` §7.1.

## Out of scope

**A limiter for a future HTTP transport.** If this Server ever grows Streamable HTTP, the
limiter belongs with that transport's other concerns and the reasoning above will need
redoing from scratch — the reasons hold for stdio, not for the design.

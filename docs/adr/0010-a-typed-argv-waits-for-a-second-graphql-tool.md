# 10. A typed argv waits for a second `gh api graphql` Tool

Date: 2026-09-07

## Status

Accepted. It writes no code: what it settles is that an abstraction this Server was asked to
consider does not get built yet, and what would make it right to build.

Recorded so the same suggestion is not made again from scratch. The argument below is about
this codebase's shape at this date, not about the abstraction being a bad one — it is a
decision with an expiry condition, named in **Decision**.

## Context

An architecture review raised the argv `GhCli` receives as a candidate for deepening. The
observation was accurate on both halves:

- The argv is a bare `List<String>`. Nothing in its type distinguishes a flag from a value,
  or a value that may be typed from one that must not be.
- `gh api`'s two field flags carry a rule with real consequences, and that rule lived only in
  comments. `-f` sends a value as a literal string. `-F` has three magic readings of it, all
  three measured on this route: `123` and `true` become JSON scalars; `{owner}`, `{repo}` and
  `{branch}` are replaced with whatever repository the working directory resolves to; and
  `@path` or `@-` reads the value out of a local file or stdin and sends *that*. So `-F body=`
  on `add_issue_comment` would let a Client name a file on this machine and have its contents
  posted to GitHub, over a Tool whose annotations say it writes a comment.

The proposed fix was one module: a typed field, so that a `String` value could not be
expressed with `-F` at all — the mistake made unrepresentable rather than merely watched.

Three facts decided against building it today.

**One file, three call sites.** `gh api graphql` appears only in `CommentTools`:
`list_issue_comments`' query, and `add_issue_comment`'s id lookup and mutation. `IssueTools`
and `LabelTools` reach GitHub through porcelain — `gh issue list --repo … --json …`,
`gh label list --search …` — where `-f` and `-F` do not appear and the rule does not apply. A
typed field would serve one file and be inapplicable to the other two.

**The deletion test answers the wrong way.** Delete the module and the complexity reappears
across three call sites in one class, not across N callers. That is the definition of a
hypothetical seam: one adapter, not two, and nothing yet varying across it.

**The mistake it would prevent is now caught.** `ArgvFlagAcceptanceTest` reads the rule off
the artifact rather than off memory. Every GraphQL document declares its own variable types,
and the document travels in the argv as `-f query=`, so one captured call carries both what
was sent and what it should have been sent as. The check is two-sided — `Int`, `Float` and
`Boolean` must be `-F`, everything else `-f` — and it keeps no table of variable names, so a
variable added to a document with the wrong flag goes red with nobody touching the test.

That last fact is what makes waiting cheap rather than merely tolerable. A type would make
the mistake unrepresentable; the test makes it un-shippable. The difference between those two
is worth one module when there are callers to pay it back across, and is not worth one when
there are three call sites in one file.

## Decision

The argv stays a `List<String>`. No typed field, no argv builder.

**The expiry condition is a second Tool that reaches GitHub through `gh api graphql`.** At
that point there are two adapters rather than one, the rule spans two files, and the seam
stops being hypothetical. This is the same rule ADR-0008 applied to `CHECK_INSTEAD_OF_RETRYING`
naming `list_issue_comments` by hand: the second write Tool is the point at which to
parameterise, not before.

A second *porcelain* Tool is not the trigger. It would not use the flags at all.

## Known limitations

The rule is enforced, not embodied. A maintainer can still write `-F body=` and will find out
from a test rather than from the compiler — and only after running it. What the test buys over
the comments it replaced is that the finding-out is certain and does not depend on anyone
remembering that `CommentTools` has a rule.

The enforcement reaches exactly as far as a Tool that reaches `gh`. `ToolCalls` is what
guarantees that, and it is a fixture rather than a proof; a Tool whose entry stopped short of
the subprocess would contribute no argv. The test guards this by requiring at least one
recorded call per declared Tool rather than merely one overall, which is as far as the
Acceptance layer can see.

## Out of scope

Whether `GhCli` should know anything about `gh api` at all. It deliberately does not: it
spawns, drains, times out and classifies stderr, and the argv it receives is opaque to it.
Moving flag knowledge into it would give it a second job and would not survive the porcelain
Tools, which build argv it must also run.

The porcelain argv's own shape — `--limit`, `--json`, `--search`/`--sort` exclusivity. Those
carry rules too, and they are guarded where they are decided: `LabelTools` keeps `sort` out of
its method signature so the combination `gh` refuses is unreachable rather than rejected.

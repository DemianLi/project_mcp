# 17. `owner` and `repo` may not contain a slash

Date: 2026-09-09

## Status

Accepted. Fixes [#37](https://github.com/DemianLi/project_mcp/issues/37), found while
writing `docs/measurements/gh-compatibility.sh` and present in the released `v0.1.0`.
Amends [ADR-0002](0002-failure-contract-for-gh-calls.md)'s claim about where a wrong Remedy
can come from.

## Context

`gh`'s `--repo` takes **`[HOST/]OWNER/REPO`**. This Server composed it as
`owner + "/" + repo` from two Client-supplied parameters and checked neither, so a slash
inside `owner` promoted its first segment to a hostname.

Measured through the Tool interface against the packaged 0.1.0, not by reading:

| `owner` / `repo` | where the Server went | what the Client was told |
| --- | --- | --- |
| `a/b` / `c` | `https://a/api/graphql` | `UNKNOWN` |
| `127.0.0.1:8099/a` / `b` | `https://127.0.0.1:8099/api/graphql` | **`RETRY`** |

**Two problems, and the second is the one that matters more.**

The caller chose where this Server made its next request. `commercial-readiness.md` §4.3
had judged the parameter surface safe on the strength of there being no shell and `-f`
sending values literally — both true, and neither covering this. In the deployment shape
this Server is built for, the caller is a model that has just read someone else's issue
text.

And the refusal came back as `RETRY`, "the network looks unavailable", because `gh`'s
`dial tcp … connection refused` matches `GhStderr`'s network row. The network was fine; the
address was not. A Client that follows that advice sends the same request to the same host
again. ADR-0002 puts a **confident wrong Remedy** in its worst category — worse than
`UNKNOWN`, which at least hands the stderr over and admits it does not know.

**Whether a credential went with it: answered from the source, not measured.** `gh` 2.91.0
pins `github.com/cli/go-gh/v2 v2.13.0`, whose `pkg/auth/auth.go` decides this in one
branch of `tokenForHost`. `api.AddAuthTokenHeader` (`api/http_client.go`) sets
`Authorization` whenever that lookup returns a token for the request's host, and the lookup
splits the world in two:

- **The host is `github.com`, a `*.ghe.com` tenancy, or `github.localhost`** — `GH_TOKEN`
  or `GITHUB_TOKEN` applies.
- **Anything else** — those two are *not* consulted; `GH_ENTERPRISE_TOKEN` or
  `GITHUB_ENTERPRISE_TOKEN` is, **for any such host**, with no allow-list. Failing that, the
  config file's `hosts.<host>.oauth_token` and then the keyring, both keyed by host and
  therefore empty for a host nobody logged into.

So the answer depends on the deployment, and the dependency is uncomfortable:

| Credential the deployment sets | What a caller-chosen host received |
| --- | --- |
| `GH_TOKEN` / `GITHUB_TOKEN`, or `gh auth login` for github.com | no `Authorization` header |
| **`GH_ENTERPRISE_TOKEN` / `GITHUB_ENTERPRISE_TOKEN`** | **that token, valid, in the header** |

The second row is the route [docs/deploying.md](../deploying.md) names for GitHub Enterprise
Server. For those deployments 0.1.0's defect was a credential disclosure and not merely a
redirect — which is why the severity could not be left at "undetermined" once it was
cheap to settle.

Read rather than run: no request was captured with a header in it, because doing that needs
a TLS listener with a certificate this machine trusts. The branch is a plain string
comparison with no configuration in it, so the reading is not delicate — but it is a reading,
and it is pinned to those two versions.

## Decision

**Neither `owner` nor `repo` may contain a slash.** `Repos` refuses one with `FIX_REQUEST`,
naming the parameter, before any subprocess starts — the fifth failure this Server invents
rather than inherits.

Three narrower decisions inside that, each of which could reasonably have gone the other
way.

**Only the slash.** An empty half or a trailing one composes `/c` or `a/`, and `gh` answers
both with `expected the "[HOST/]OWNER/REPO" format`, which `GhStderr`'s malformed-name row
already turns into `FIX_REQUEST` — measured, and re-checked by
`docs/measurements/gh-compatibility.sh`. Refusing those here would be this Server
re-answering, less well, a question GitHub's own CLI answers correctly. `ReposTest` pins
that hole open on purpose, because the argument for it lives nowhere a compiler can see.

**Checked on every Tool, including the three where it is inert.** Only the porcelain routes
compose `--repo`; `list_issue_comments` and `add_issue_comment` pass the halves as separate
GraphQL variables, where a slash produces an ordinary "Could not resolve to a Repository"
and the right Remedy already. The check runs there anyway, so that **one bad parameter gets
one answer whichever Tool received it**. Which route a Tool takes to GitHub is this Server's
business (ADR-0005, ADR-0010) and should not be visible in what a Client is told about its
own typo.

**The guard composes the slug.** `Repos.slug(owner, repo)` returns the string rather than
merely validating it, so a Tool cannot compose one without passing through the check. The
call sites moved inside `ToolResults.attempt`'s lambda for the reason ADR-0011 gives: a
`ToolFailure` thrown outside it is Spring AI's to answer, and arrives with no Remedy at all.

## Consequences

**A Client can no longer reach an arbitrary host through these parameters.** Pinned by
`RepositoryNameAcceptanceTest`, which drives every Tool from `listTools()` rather than a
list, asserts `FIX_REQUEST` rather than merely a failure, and asserts that the stand-in `gh`
never ran — a guard that refused after spawning the subprocess would satisfy every assertion
about the response while the request still left the machine. Mutation-checked: with the
refusal removed, the test is red on `add_issue_comment`.

**ADR-0002's bound is narrower than it reads.** That ADR says unmatched output falls to
`UNKNOWN` "rather than to a confident wrong answer". True of *unmatched* output. This
failure was **matched** — by a row whose markers are Go's networking wording, on a stderr
that really was a connection failure, to a host that should never have been dialled. String
matching bounds the damage from stderr it does not recognise; it bounds nothing about
stderr it recognises correctly for a request that should not have been made.

**The network row still has no measured sample.** The stderr this produced —
`Post "https://127.0.0.1:8099/api/graphql": dial tcp 127.0.0.1:8099: connect: connection
refused` — is real `gh` output and could be promoted from `UNMEASURED`. It is not, because
it is evidence of *an address that was wrong*, not of *GitHub being unreachable*, and this
ADR exists because those two were conflated once already.

## Out of scope

**Other parameters.** `body`, `cursor` and `limit` each have their own guard for their own
reason (ADR-0006, ADR-0007, ADR-0004); this is not the beginning of a validation layer.

**Whether the request carried a credential.** Named above as unmeasured. If it turns out one
was sent, that is a disclosure rather than a redirect and deserves its own record.

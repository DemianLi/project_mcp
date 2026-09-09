# Deploying this Server

Operational, not architectural. What a deployment has to provide, what it has to decide,
and the two or three things about this Server that are only visible from the source.

For what the Server *is*, read [README.md](../README.md). For why it is shaped this way,
read [docs/adr/](adr/). For a commercial readiness assessment,
[docs/reviews/commercial-readiness.md](reviews/commercial-readiness.md).

## Read this first: one process is one identity

Every call this Server makes to GitHub uses the same resolved login. There is no per-caller
identity, no per-request token, and no way to give two users of one instance different
permissions. That is a property of the design, recorded in
[ADR-0009](adr/0009-writes-are-gated-outside-this-server.md), and it decides the shape of
the deployment before anything else does:

- **One desk, one instance.** Fine. This is what the Server is for.
- **Several people, one instance.** They share one login, one set of permissions, and one
  view of which repositories exist. Whatever one of them can write, all of them can.
- **Multi-tenant.** One process per tenant, or not at all. There is no switch.

`commercial-readiness.md` §4.1 and §4.2 cost this out; `CONTEXT.md` under *Deployment*
defines the two different promises the phrase "read-only instance" can mean, which is worth
reading before promising either to anyone.

## Authentication

### What the specification asks of a stdio server

Nothing that this Server is missing. Under *Protocol Requirements*, both revisions say the
same thing in the same words: an implementation on a stdio transport **SHOULD NOT** follow
the authorization specification, and should retrieve its credentials from the environment
instead. Read it at `docs/specification/2025-11-25/basic/authorization.mdx` — and at
`2026-07-28/basic/authorization/index.mdx`, which is identical on this point.

OAuth 2.1, resource-server token validation, protected-resource metadata — all of it is
scoped to HTTP-based transports, as that document's own Purpose and Scope says. A stdio server that took a token over the wire would be
departing from the specification, not conforming to it more closely. This Server holds no
token, validates no token, and reads none: authentication is entirely `gh`'s, resolved
either from the environment the Server was started in or from `gh`'s own stored
credentials.

That changes if a second transport is ever added. Streamable HTTP would bring the whole
authorization specification with it, and none of it is implemented.

### How the credential reaches `gh`

The Server spawns `gh` with `new ProcessBuilder(command).start()` and never calls
`.environment()`, so **the child inherits the Server process's environment verbatim**. Where
`gh` looks, in its own order of precedence:

| Source | Set by |
| --- | --- |
| `GH_TOKEN` | the environment the Server was started in |
| `GITHUB_TOKEN` | the same, lower precedence |
| `gh`'s stored credentials | `gh auth login`, written under `$GH_CONFIG_DIR`, else `$XDG_CONFIG_HOME/gh`, else `$HOME/.config/gh` |

`gh`'s own documentation says `GH_TOKEN` "takes precedence over previously stored
credentials", so an environment variable silently wins over whatever `gh auth login` wrote.
A deployment that sets both has one of them doing nothing.

**The stored-credential route needs somewhere to look.** A container started without `HOME`,
`XDG_CONFIG_HOME` or `GH_CONFIG_DIR` leaves `gh` with no config path to resolve, and
credentials written into the image become unreachable however correctly they were written.
Either set one of those three, or use the token variable and accept the restart cost below.

The variable contract above is `gh help environment`'s, read from `gh` 2.91.0. It is
version-specific.

For GitHub Enterprise Server, the token variable is `GH_ENTERPRISE_TOKEN` and the host comes
from `GH_HOST`. Untested here — every measurement in this repository was taken against
`github.com`.

**If that is your variable, take 0.1.1 rather than 0.1.0.** `gh` attaches
`GH_ENTERPRISE_TOKEN` to a request to *any* host that is not `github.com`, a `*.ghe.com`
tenancy or `github.localhost` — there is no allow-list — and until 0.1.1 a Client could
choose that host by putting a slash in `owner`
([ADR-0017](adr/0017-owner-and-repo-may-not-contain-a-slash.md), read out of `go-gh`
v2.13.0's `tokenForHost`). On a `GH_TOKEN` deployment the same defect sent no credential;
on this one it sent a valid enterprise token.

### Rotating the credential, and why it depends on where you put it

This is the one thing in this document that cannot be worked out without reading the source.

**Token in the environment.** The Server's environment is fixed when the process starts, and
every `gh` invocation inherits that same fixed copy. Replacing the token in the orchestrator,
the secret store, or the shell that will start the next process changes nothing for a Server
already running. **Rotation requires restarting the Server process**, and under stdio that
means the Client restarting it.

**Token in `gh`'s stored credentials.** Each Tool call spawns a new `gh`, which re-reads its
config file. A token rewritten there — by `gh auth login`, or by writing the file — takes
effect on the next Tool call, with no restart.

Neither is better. The first is what containers and orchestrators are built around; the
second is what survives a rotation without an outage. Choose knowing which one you chose.

Anything beyond this — expiry policy, PAT versus GitHub App, how often to rotate — is
`gh`'s and the deployer's. This Server observes none of it and has no opinion worth
recording.

## What a deployment has to provide

The repository's [`Dockerfile`](../Dockerfile) is one deployment that satisfies all of this,
and it was built and driven before it was committed: image built, container started, a
`tools/list` and a `get_issue` answered over its stdio, stdout checked line by line for
anything that was not JSON-RPC, and the out-of-memory path provoked inside the container to
confirm the protocol stream survives it. About 510 MB on arm64, most of which is the JRE and
`gh` — this Server cannot do anything at all without the GitHub CLI in the image.

The conditions below are what that file is satisfying, and they hold just as well for a
systemd unit or a bare `java -jar`.

1. **A JDK 25 runtime.** The build targets Java 25; earlier runtimes will not load the
   classes.
2. **`gh` on the Server process's `PATH`.** `GhCli` is a `@Component` constructed through
   its no-argument constructor, which hardcodes the name `gh`. Its second constructor takes
   an explicit path and its javadoc calls that "a real configuration point" — nothing wires
   it to a property, so today it is reachable only from code. If `gh` is not on `PATH`, every
   Tool call fails with `ASK_OPERATOR` and a sentence saying the CLI could not be started.
3. **A credential**, by one of the routes above.
4. **A writable working directory**, or a `logging.file.name` pointing somewhere writable.
   The default is `logs/project-mcp.log`, relative to wherever the process was started, and
   the directory does not have to exist first — measured: Logback creates the whole nested
   path. The Server writes one JSON line per Tool call there
   ([ADR-0013](adr/0013-what-a-call-leaves-behind.md)); nothing rotates it away beyond the
   100 MB cap and nothing ships it anywhere.
5. **A clean stdout.** This is the hard one. stdout *is* the protocol: anything else written
   to it corrupts JSON-RPC. An entrypoint script that echoes a banner, a wrapper that prints
   a version, a shell profile that greets — each of them breaks the Server before it starts.
   The Server's own hygiene is already handled (`banner-mode: off`,
   `web-application-type: none`, console appender `OFF`); what a deployment adds around it is
   not.
6. **stdin and stdout attached to the Client, and shared with nothing else.** The Server
   reads line-delimited JSON-RPC from one and writes it to the other; a second writer on
   either interleaves into the middle of a message. There is no port to expose and no health
   endpoint to probe — under stdio there is nowhere to put one.

`mvn spring-boot:run` is not a way to start this. Maven writes build output to stdout before
the application starts, and a Client parses that as JSON-RPC. Always the packaged jar.

## JVM flags, and why they come in a pair

```
-XX:+ExitOnOutOfMemoryError -XX:+DisplayVMOutputToStderr
```

The Server refuses any single `gh` response over 8 MB and halts itself on a fatal `Error`
([ADR-0015](adr/0015-a-ceiling-on-one-response.md)), so most of this is already handled in
code. Keep the flags anyway: the same provocation at the same heap size ended two different
ways. On the host the Server's own branch won and wrote the line naming the call; in the
container the flag won and the log file held nothing but its startup lines. Which gets there
first is not this Server's to decide, so both are worth having.

Without them, a Server that runs out of memory **does not die**. Measured: no response to the
in-flight call, nothing in the log but a Reactor stack trace, and the process still holding
the pipe ten minutes after its stdin closed. A Client can restart a Server that died; against
one that is up and mute it can only wait out its own timeout, while the abandoned process
stays.

The second flag is not decoration. `-XX:+ExitOnOutOfMemoryError` on its own prints
`Terminating due to java.lang.OutOfMemoryError` **to stdout** — into the JSON-RPC stream,
breaking the one `MUST NOT` the stdio transport has.
`-XX:OnOutOfMemoryError="kill -9 %p"` prints four lines there. `DisplayVMOutputToStderr`
moves the JVM's own output to stderr, which the specification explicitly allows a stdio
server to write to. All three measured; the pair is the only combination that exits promptly
and leaves the protocol stream untouched.

Heap sizing follows from the same ADR: the amplification chain holds several copies of a
payload, so the practical break is near a fifth of the heap. The 8 MB ceiling is set far
enough below any plausible heap's fifth that it is reached first — a refusal carries a
Remedy, an `OutOfMemoryError` carries nothing.

## Two clocks, and only one of them is this Server's

The word *timeout* means two unrelated things here, and a deployment that conflates them
sets the wrong number.

**The Client's clock** is the one the specification talks about. `2025-11-25`'s
`basic/lifecycle.mdx`, under *Timeouts*, binds the **sender** of a request: a sender SHOULD
establish a timeout for requests it sends, and SHOULD issue a cancellation notification when
one expires. On `tools/call` this Server is the receiver, and it uses none of
the server-to-client requests the protocol offers — no sampling, no elicitation, no
`roots/list` — so in this Server that clause has nothing of its own to bind and addresses
the Client. (The SDK does hold a `requestTimeout` for the requests a server *can* send;
this one never sends them.) The sentence beside it, saying an SDK SHOULD let those timeouts
be set per request, is addressed to the sender's SDK for the same reason. Neither is a requirement on the
budget below, and neither is a gap this Server is conceding.

**This Server's clock** is `GhCli.TIMEOUT_SECONDS = 30`: how long one `gh` invocation may
take before it is killed, along with everything it spawned. The specification says nothing
about it. It exists because a subprocess that never returns is the failure `GhCli` was
written to prevent.

Neither clock knows about the other, which is what the rest of this section is about.

### What a call actually costs

Measured 2026-09-09 against `github.com`, five samples per shape, driving the packaged jar
over its own stdio. The number is the Server's own `durationMs` — the whole Tool body, `gh`
round trip plus parse plus serialise, which is what the Client waits for.

| Tool | Repository | median | slowest | largest response |
| --- | --- | ---: | ---: | ---: |
| `list_labels` | `DemianLi/project_mcp` | 542 ms | 555 ms | 1.4 KB |
| `list_issue_comments` | `DemianLi/project_mcp` | 595 ms | 665 ms | 3.6 KB |
| `get_issue` | `DemianLi/project_mcp` | 659 ms | 705 ms | 7.7 KB |
| `list_issue_comments` | `modelcontextprotocol/modelcontextprotocol` | 702 ms | 844 ms | 13.4 KB |
| `list_issues` (state `ALL`) | `DemianLi/project_mcp` | 824 ms | 882 ms | 7.1 KB |
| `list_issues` (100 open) | `modelcontextprotocol/modelcontextprotocol` | 966 ms | 1,474 ms | 18.2 KB |

Thirty seconds is **31 times the slowest median above and 20 times the slowest single call
observed**. That is the argument for the number: it is not "about long enough", it is two
orders of magnitude away from ordinary traffic, far enough that reaching it means `gh` or
the network is genuinely stuck rather than merely busy. The same run puts the largest
response at 18 KB, which is a 460th of the 8 MB ceiling
([ADR-0015](adr/0015-a-ceiling-on-one-response.md)) — both bounds sit a long way outside the
traffic they bound.

One desk's afternoon, on one machine, against one network. Read it as an order of magnitude,
not an SLO — the trap [ADR-0014](adr/0014-no-metrics-and-who-would-have-to.md) names. Take
your own with [`docs/measurements/tool-latency.sh`](measurements/tool-latency.sh), which is
the script this table came out of.

### The number a Client timeout is set from

The budget is per `gh` invocation, not per Tool call, and one Tool makes two.

| Tool | `gh` invocations | worst case |
| --- | ---: | ---: |
| `list_issues`, `get_issue`, `list_labels`, `list_issue_comments` | 1 | ~30 s |
| `add_issue_comment` | 2 | ~60 s (structural bound, not measured) |

The two are structural, not an oversight: GraphQL cannot feed a query's result into a
mutation in the same document, and `addComment` needs a `subjectId` that a prior query
resolved. That first call is deliberately a read — a timeout there means nothing was
written ([ADR-0007](adr/0007-add-issue-comment-parameters-return-and-annotations.md)).

A Client deadline shorter than 60 seconds can therefore expire over a write that is still in
flight. What happens then is below, and it is not a hang.

### One slow call does not block the others

Measured with a stand-in `gh` that sleeps 20 seconds. Request A (`list_issues`, into the
slow binary) went out; one second later request B (`list_labels`, into a fast one):

```
B answered  27 ms after it was sent, with A's `gh` still 19 s from returning
A answered  20.36 s after it was sent, tagged with its own id, after B
```

So a stuck `gh` costs its own caller and nobody else, and responses leave in completion
order with their `id` — which JSON-RPC allows and a Client matches on. Nothing was
interleaved into the middle of another message.

This is the SDK's scheduling rather than this Server's: an observation about MCP Java SDK
2.0.0 and Spring AI 2.0.1, not a promise this Server keeps. It is not pinned by a test,
because a test over it would assert someone else's internals — re-run it against your own
versions with
[`docs/measurements/concurrency-and-cancellation.sh`](measurements/concurrency-and-cancellation.sh),
which needs no network and no credential.

### When the Client gives up

Nothing reaches this Server. `notifications/cancelled` is not implemented anywhere in the
stack it is built on — `mcp-core` 2.0.0, `mcp-json-jackson3` 2.0.0, `spring-ai-mcp` 2.0.1
and `spring-ai-autoconfigure-mcp-server-common` 2.0.1 contain no occurrence of the string,
and `McpSchema`'s method constants do not include it. A cancellation therefore lands in the
SDK's unknown-notification branch: one `WARN` in the log file, no reply, nothing on stdout.
The specification permits exactly this — a receiver MAY ignore a cancellation — and
[ADR-0016](adr/0016-a-cancelled-call-is-not-cancelled-here.md) records the decision not to
close the gap.

What that costs, concretely:

- **The `gh` keeps running**, to its own completion or to the 30-second budget, whichever
  arrives first. The work is not stopped and the subprocess is not killed early.
- **The late response is still written**, correctly tagged. A Client that gave up must
  ignore it, which is what the specification tells the sender to do.
- **The Client's own words end up in the log file.** That `WARN` prints the whole
  notification, `reason` included:

  ```
  No handler registered for notification method: JSONRPCNotification[jsonrpc=2.0,
    method=notifications/cancelled, params={requestId=101, reason=probe gave up}]
  ```

  [ADR-0013](adr/0013-what-a-call-leaves-behind.md)'s boundary is about GitHub's content —
  no issue body, no comment text. This is the Client's own text, and it is outside that
  boundary. A deployment where the Client puts anything sensitive in a cancellation reason
  should know the file holds it.

### Changing the budget

Thirty seconds is a constant, not a property. That is deliberate and it is the same rule
`MAX_RESPONSE_BYTES` and `Limits.MAX` follow: every bound in this Server is a constant with
an ADR behind it, because a bound exposed as configuration is a bound set by someone who
never read the reasoning. Nothing in the specification's timeout clause argues otherwise —
as above, that clause is addressed to the sender.

Two ways to change it, both requiring a build: edit `GhCli.TIMEOUT_SECONDS`, or construct
`GhCli` through its two-argument constructor, which is how the timeout tests run their
budget down to one second.

What each direction buys and costs:

- **Shorter.** A stuck call is abandoned sooner. It also expires over more writes that were
  about to succeed, and every expired write comes back `CHECK_BEFORE_RETRY` — the Client is
  told to go and look rather than retry, because GitHub has no idempotency key
  ([ADR-0008](adr/0008-failure-contract-for-writes.md)). Shortening buys responsiveness with
  unconfirmed writes.
- **Longer.** More slow-but-real calls complete. Past the Client's own deadline it buys
  nothing at all: the Client stops waiting, this Server never learns, and the work runs on
  to produce a response nobody reads.

## Environment variables worth setting

Not for the Server's sake — for `gh`'s.

```sh
GH_NO_UPDATE_NOTIFIER=1
GH_NO_EXTENSION_UPDATE_NOTIFIER=1
GH_TELEMETRY=false
GH_PROMPT_DISABLED=1
NO_COLOR=1
```

The reason is a path worth understanding rather than a list worth copying. When `gh` exits
non-zero, this Server passes **the whole of its stderr** to `GhStderr.classify()`, which
matches by `contains` over the lowered text, and then reports that same stderr verbatim to
the Client — where a model reads it. Anything `gh` writes to stderr for its own reasons rides
along.

`gh` documents that it checks for a new release once every 24 hours and that "an upgrade
notice is displayed on standard error". Telemetry can be made to print there too. On a
successful call none of it matters, because stderr is only read on failure — but a failure
inside that window carries the notice into the model's view, and a marker collision is
possible in principle: one of the classifier's markers is the literal string `gh auth login`,
so any `gh` output containing that phrase classifies as *not authenticated*.

Stated precisely, because the parts have different evidence behind them: the mechanism is
measured (the classifier and the payload both take the entire stderr, and the test suite
feeds it that way); the notifier writing to stderr is `gh`'s own documentation; the two
colliding has not been provoked here, because there is no way to make `gh` believe a newer
release exists.

**After upgrading `gh`, check that it still fails in the words this Server reads.** The
same `contains` matching is how every failure gets its Remedy, so a rephrased message costs
the caller its recovery advice without anything going red.
[`docs/measurements/gh-compatibility.sh`](measurements/gh-compatibility.sh) provokes seven
of the ten classified failures against the installed `gh` and exits non-zero if one no
longer matches. It writes nothing to GitHub.

`GH_PROMPT_DISABLED` and `NO_COLOR` are cheaper insurance in the same direction — nothing
here is a terminal, and neither a prompt nor an escape sequence has anywhere useful to go.

## What this Server does not do

Not gaps to be apologised for; boundaries with reasons recorded elsewhere.

- **It does not authenticate its Client.** Whoever can write to its stdin can call every
  Tool. Under stdio the Client is the process's parent, so the boundary is the operating
  system's, not this Server's.
- **It does not rate limit.** A knowing departure from a `MUST` in the 2025-11-25 Security
  Considerations, with the reasons and the owner in
  [ADR-0012](adr/0012-no-rate-limiting-and-why.md). GitHub's own rate limiting is reflected
  back as a `RETRY` Remedy, and that is not the same thing.
- **It does not measure itself.** No metrics, no health endpoint, and
  [ADR-0014](adr/0014-no-metrics-and-who-would-have-to.md) says who would have to add them
  and where.
- **It does not respect an issue lock.** Measured: an owner's login posted a comment to a
  locked issue, because GitHub's lock refuses people without write access and that login had
  it. Locking is not an access control this Server can be leaned on to enforce.
- **It does not carry a response over 8 MB.** Over that, one `gh` call is refused with
  `FIX_REQUEST` rather than delivered or crashed on. Ordinary traffic never meets it: the
  largest response GitHub's own shapes produce here is a full comment page at 6.57 MB,
  measured. A response so large that reading it exhausts the heap before the ceiling can be
  applied — 200 MB against a 256 MB heap — comes back as `UNKNOWN` instead, from the path
  that already handled a `gh` this Server could not read.
  [ADR-0015](adr/0015-a-ceiling-on-one-response.md).
- **It does not sanitise GitHub's content.** Issue bodies and comments cross the wire as
  GitHub returned them. A Client that renders them is responsible for doing so safely;
  `commercial-readiness.md` §4.3 covers the injection surface on both sides.

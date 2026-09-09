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

No Dockerfile is shipped, deliberately: one has not been built and run here, and an
unverified example is worth less than the conditions it would have to satisfy. These
conditions hold for a container image, a systemd unit, or a bare `java -jar`.

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
- **It does not sanitise GitHub's content.** Issue bodies and comments cross the wire as
  GitHub returned them. A Client that renders them is responsible for doing so safely;
  `commercial-readiness.md` §4.3 covers the injection surface on both sides.

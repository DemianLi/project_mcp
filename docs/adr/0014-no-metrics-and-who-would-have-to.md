# 14. No metrics, and who would have to add them

Date: 2026-09-09

## Status

Accepted. The companion to [ADR-0013](0013-what-a-call-leaves-behind.md), which records
what this Server *does* observe. Shaped like
[ADR-0012](0012-no-rate-limiting-and-why.md): a thing not built, named, with an owner.

## Context

`docs/reviews/commercial-readiness.md` recommended Micrometer, noting that
`micrometer-core` arrives on the classpath through Spring AI and suggesting `@Timed` or an
injected `MeterRegistry`.

Neither works as written. `micrometer-core`, `micrometer-observation` and
`micrometer-commons` are on the compile classpath; no `micrometer-registry-*` is, and
neither is `spring-boot-starter-actuator`. `MeterRegistry` is autoconfigured by actuator, so
there is no bean to inject and no registry for `@Timed` to write to. The recommendation was
read off a dependency tree rather than tried.

That mattered less than the shape it was proposed for. This Server speaks stdio: the Client
starts it as a subprocess and it dies when the pipe closes. A counter lives as long as that
process — minutes on a desk — and there is no port to scrape it through and no address to
push it to before it goes.

## Decision

This Server does not measure itself. No actuator, no registry, no meters, no `@Timed`.

What would otherwise be measured is in the log instead, per call rather than aggregated:
`durationMs` is latency, `outcome` and `remedy` are the failure rate and its breakdown,
and a line per call is the call count. Aggregation is a `jq` pipeline over the file, run by
whoever wants the number.

## Consequences

**There is no live number.** Nothing answers "how is it doing right now" without reading
the file. On one desk that is the same question as "what just happened", which the file
answers better.

**The gap is honest in the docs.** `commercial-readiness.md` §3 states this rather than
recommending an implementation that the classpath cannot support.

**A trap worth naming, the same one ADR-0012 names.** Per-call durations in a log are not
a latency SLO. They are unaggregated, they cover only calls that reached the Tool method
(ADR-0013: a schema-rejected call leaves no line), and they end when the process does.
Reporting a p99 computed from one desk's file as this Server's latency would be a
measurement of one person's afternoon.

## Who has to

Whoever deploys this Server past a single desk, at the point where the transport stops being
stdio.

Concretely: an HTTP transport gives a long-lived process and a place to put an endpoint.
Adding `spring-boot-starter-actuator` and a registry (`micrometer-registry-prometheus`) then
gives `MeterRegistry` a home, and `/actuator/prometheus` can be exposed on a port separate
from the MCP endpoint — nothing about metrics belongs on `/mcp`. The Tool layer is where the
meters go, beside the trace ADR-0013 already emits there, not inside `GhCli`, which sees
subprocess calls rather than Tool invocations and would count the two calls of a write as
two.

Until that deployment exists, the meters would have no reader.

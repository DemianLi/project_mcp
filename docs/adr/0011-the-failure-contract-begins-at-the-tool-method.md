# 11. The failure contract begins at the Tool method body

Date: 2026-09-09

## Status

Accepted. **Narrows ADR-0002's scope, not its principle.** ADR-0002 says every failure
this Server reports carries a Remedy. That is true of every failure this Server reports.
It is not true of every failure a Client sees, and this ADR draws the line between them.

## Context

ADR-0002 and ADR-0008 were verified by reading the code and by an offline suite that
drives the wire. On 2026-09-09 the packaged jar was driven for the first time by the
official MCP Inspector and by hand-written JSON-RPC. Both found the same reachable path
the suite had no test standing on.

**A call the schema rejects never reaches this repo's code.** `get_issue` with `repo` and
`number` missing comes back as:

```json
{"content":[{"type":"text","text":"Tool (get_issue) input validation failed: Validation failed: JSON schema validation errors: [: 未找到所需屬性“repo”, : 未找到所需屬性“number”]"}],
 "isError":true}
```

No `structuredContent`. No Remedy. A type mismatch behaves identically — sending `labels`
as a string rather than an array answers `[/labels: 已找到 string，必須是 array]` in the
same shape. Those two are the most common mistakes a Client makes, and neither carries
advice.

**Two further facts, both measured.**

The message follows the JVM's default locale. The string above came back in Chinese
because the machine's locale is `zh_TW`; the same failure on a `ja_JP` host is Japanese.
Nothing in this repo chose that.

The work is done by `io.modelcontextprotocol.util.ToolInputValidator`, a `final` class
with a `static` method, called from `McpAsyncServer` **before** the Tool is dispatched. It
builds its own result:

```java
return CallToolResult.builder()
    .content(List.of(TextContent.builder(message).build()))
    .isError(true)
    .build();
```

There is no `structuredContent` argument to reach.

**Two escapes were tried and measured. Neither works.**

Spring AI exposes `org.springframework.ai.mcp.customizer.McpSyncServerCustomizer`, which
hands out `McpServer.SyncSpecification` — so `validateToolInputs(boolean)`,
`jsonSchemaValidator(...)` and `capabilities(...)` are all reachable from a bean in this
repo. That much was checked before either was judged.

`validateToolInputs(false)` is worse. With SDK validation off, the arguments go to Spring
AI's binder, and the same missing-parameter call answers:

```
java.lang.NullPointerException: Cannot invoke "java.lang.Number.intValue()" because the
return value of "sun.invoke.util.ValueConversions.primitiveConversion(...)" is null
```

Still no `structuredContent`, and now JVM internals are on the wire. A bad `int` becomes
Jackson's `Conversion from JSON to int failed`. This was built and driven, not reasoned
about.

A custom `JsonSchemaValidator` reaches `validation.errorMessage()` and therefore the text,
which would fix the locale. It does not reach the result's shape, because
`ToolInputValidator` builds that.

## Decision

**The failure contract's scope is the Tool method body.** Everything inside
`ToolResults.attempt(...)` carries a Remedy; the argument-binding gate in front of it is
the SDK's, answers in the SDK's shape, and this Server cannot change that shape with the
seams the SDK offers.

**SDK input validation stays on.** It is the better of the two available behaviours by
measurement: a readable schema complaint beats a leaked `NullPointerException`.

**No custom `JsonSchemaValidator`, for now.** It would fix the message's language and
nothing else, at the cost of a class implementing an SDK interface this repo otherwise
does not touch. Revisit it when a Client is actually running on a differently-localised
host — that is when the cost stops being theoretical.

**The current shape is pinned by a test.**
`SdkBoundaryAcceptanceTest.aCallTheSchemaRejectsCarriesNoRemedy` asserts
`structuredContent` is *null*. It is a tripwire, not an endorsement: the day the SDK grows
a seam here, that assertion fails and someone gets to close the hole.

## Known limitations

**The model gets no advice on its most likely mistake.** A model omitting a required
argument is the commonest failure in service, and it is the one failure with no Remedy
attached. It does get a sentence naming the missing field, which is why this is a
limitation rather than a blocker.

**The language of that sentence varies by host.** Two deployments of the same jar can
answer the same mistake in different languages.

**Nothing was measured about how a model recovers from it.** Whether the sentence alone is
enough for self-correction is unknown; ADR-0002's claim that Remedies exist because models
need them predicts it is not, and that prediction is untested.

## Out of scope

**MCP Java SDK 3.x.** Its roadmap rebuilds the tool dispatch path for the stateless
lifecycle. Whether the validation gate survives in this form is unknown, and the pinning
test is how this repo will find out.

**Validating in the method body instead.** It cannot help: the schema gate runs first, and
turning it off to get past it is the measured-worse option above.

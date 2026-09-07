package io.github.demianli.projectmcp.tool;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import io.github.demianli.projectmcp.gh.ToolFailure;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one way a Tool's work becomes what goes on the wire.
 *
 * <p>Written once and shared, because the failure contract is Server-wide: a second Tool
 * inherits it by handing its work to {@link #attempt}, not by copying a shape — and, since
 * the two halves below are private, not by any other route either. That is the whole point
 * of the single entry. It used to be two: every Tool wrapped its own body in
 * {@code try { of(...) } catch (ToolFailure e) { failure(e) }}, five copies of one contract,
 * with nothing but habit keeping the sixth honest.
 *
 * <p>Every failure travels as {@code isError: true}, never as a JSON-RPC protocol error. A
 * protocol error means the call did not happen and never reaches the model as tool output —
 * it would deliver the Remedy where its intended reader cannot see it. A missing {@code gh}
 * is still a Tool that ran and could not do its job.
 *
 * <p><strong>What a Tool can still get wrong.</strong> Not forgetting to catch — that no
 * longer compiles. What is left is doing the work <em>outside</em> the lambda: a
 * {@code ToolFailure} thrown out of a Tool method is caught by Spring AI's own callback,
 * which answers with {@code isError: true} and a text message and <em>no</em>
 * {@code structuredContent} — so the Remedy and the stderr vanish from the half ADR-0002
 * calls authoritative, while {@code isError} still looks right. Measured, not assumed. That
 * is the mistake {@code FailureContractAcceptanceTest} exists to catch.
 */
final class ToolResults {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ToolResults() {
    }

    /**
     * Runs a Tool's work and reports whichever way it goes.
     *
     * <p>Everything a Tool does that can fail belongs inside {@code body} — the {@code gh}
     * call, the mapping, and any judgement the Tool makes on what came back. Failures leave
     * it by being thrown, whether they came from {@code GhCli}, from {@code Cursors}, or from
     * the Tool itself; there is no second way to report one.
     *
     * @param body produces the value to return, or throws {@link ToolFailure}
     */
    static CallToolResult attempt(Supplier<?> body) {
        try {
            return of(body.get());
        } catch (ToolFailure e) {
            return failure(e);
        }
    }

    /** The success shape, unchanged from ADR-0001: the Envelope as JSON in one text block. */
    private static CallToolResult of(Object value) {
        return CallToolResult.builder().addTextContent(JSON.writeValueAsString(value)).build();
    }

    /**
     * The failure shape.
     *
     * <p>Two halves saying the same thing to two readers. {@code structuredContent} is
     * authoritative and machine-readable; it survives untouched because
     * {@code McpAsyncServer} skips output-schema validation once {@code isError} is true.
     * {@code content} is mandatory on a {@code CallToolResult}, which is what makes
     * structuring the error safe here — a human reading a Client that renders only text
     * still sees a sentence.
     */
    private static CallToolResult failure(ToolFailure failure) {
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("remedy", failure.remedy().name());
        if (failure.retryAfterSeconds() != null) {
            structured.put("retryAfterSeconds", failure.retryAfterSeconds());
        }
        structured.put("message", failure.getMessage());
        structured.put("stderr", failure.stderr());

        String text = failure.stderr().isEmpty()
                ? failure.getMessage()
                : failure.getMessage() + "\n\n" + failure.stderr();

        return CallToolResult.builder()
                .addTextContent(text)
                .structuredContent(structured)
                .isError(true)
                .build();
    }
}

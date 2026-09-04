package io.github.demianli.projectmcp.tool;

import java.util.LinkedHashMap;
import java.util.Map;

import io.github.demianli.projectmcp.gh.GhFailure;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns results and failures into what goes on the wire.
 *
 * <p>Written once and shared, because the failure contract is Server-wide: a second Tool
 * inherits it by calling {@link #failure}, not by copying a shape.
 *
 * <p>Every failure travels as {@code isError: true}, never as a JSON-RPC protocol error. A
 * protocol error means the call did not happen and never reaches the model as tool output —
 * it would deliver the Remedy where its intended reader cannot see it. A missing {@code gh}
 * is still a Tool that ran and could not do its job.
 */
final class ToolResults {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ToolResults() {
    }

    /** The success shape, unchanged from ADR-0001: the Envelope as JSON in one text block. */
    static CallToolResult of(Object value) {
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
    static CallToolResult failure(GhFailure failure) {
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

package io.github.demianli.projectmcp.tool;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import io.github.demianli.projectmcp.gh.ToolFailure;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import tools.jackson.databind.json.JsonMapper;

/**
 * Entry point for all Tool work. Handles success, controlled failure ({@link ToolFailure}),
 * and fatal errors. Constructs the response shape and logs call metadata (never content).
 *
 * <p>Failures are reported as {@code isError: true} with structured content (Remedy, stderr,
 * retry delay), not as JSON-RPC errors. Structured content lets the Client and model see the
 * Remedy; protocol errors are invisible to the model.
 *
 * <p><strong>Work must go inside the lambda.</strong> A {@code ToolFailure} thrown outside
 * is caught by Spring AI's own handler, which strips {@code structuredContent}. The
 * {@code FailureContractAcceptanceTest} catches this mistake.
 *
 * <p>Logs the call shape (tool name, repo, duration, bytes, outcome) but never its content
 * (body, comment text, label names). See docs/design.md#logging.
 */
final class ToolResults {

    private static final Logger log = LoggerFactory.getLogger(ToolResults.class);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * MDC keys this class sets, removed in the finally block.
     * Tool calls run on a pooled thread, so keys must be cleaned up to avoid bleeding
     * into subsequent calls.
     */
    private static final String[] KEYS =
            {"tool", "callId", "repo", "outcome", "remedy", "durationMs", "resultBytes"};

    private ToolResults() {
    }

    /**
     * Runs a Tool's work and handles the outcome: success, {@link ToolFailure}, or fatal error.
     *
     * <p>All work must happen inside {@code body}. Failures thrown outside are not caught
     * by this method; failures thrown inside are caught and structured properly.
     *
     * <p>Sets MDC fields before running {@code body} so all Tool output is tagged with
     * {@code callId}, {@code tool}, and {@code repo}, even if multiple Tools run concurrently
     * on pooled threads.
     *
     * @param tool  the Tool's wire name, e.g. {@code list_issues}
     * @param owner repository owner, recorded as shape
     * @param repo  repository name, recorded as shape
     * @param body  produces the value to return, or throws {@link ToolFailure}
     */
    static CallToolResult attempt(String tool, String owner, String repo, Supplier<?> body) {
        long start = System.nanoTime();
        MDC.put("tool", tool);
        MDC.put("callId", callId());
        MDC.put("repo", owner + "/" + repo);
        try {
            // Serialize the result so the trace can report its actual size.
            String json = JSON.writeValueAsString(body.get());
            MDC.put("resultBytes", String.valueOf(json.getBytes(StandardCharsets.UTF_8).length));
            end(start, "ok");
            log.info("{} ok", tool);
            return CallToolResult.builder().addTextContent(json).build();
        } catch (ToolFailure e) {
            // Log the Remedy (the classification), not the full message.
            MDC.put("remedy", e.remedy().name());
            end(start, "error");
            log.info("{} failed", tool);
            return failure(e);
        } catch (Error e) {
            // A fatal error means the process cannot be trusted. Halt immediately
            // rather than trying to respond, since the Client can restart a dead Server
            // but can only timeout on a mute one.
            end(start, "fatal");
            log.error("{} died", tool, e);
            Runtime.getRuntime().halt(70);
            throw e;
        } finally {
            for (String key : KEYS) {
                MDC.remove(key);
            }
        }
    }

    /** Eight hex characters: enough to join three lines in one file, short enough to read. */
    private static String callId() {
        return String.format("%08x", ThreadLocalRandom.current().nextInt());
    }

    private static void end(long start, String outcome) {
        MDC.put("outcome", outcome);
        MDC.put("durationMs",
                String.valueOf(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)));
    }

    /**
     * Builds the failure response: both text (for humans) and structured content (for models).
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

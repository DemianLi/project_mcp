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
 * The one way a Tool's work becomes what goes on the wire, and the one place a call leaves a
 * trace.
 *
 * <p>Written once and shared, because the failure contract is Server-wide: a second Tool
 * inherits it by handing its work to {@link #attempt}, not by copying a shape — and, since
 * both result shapes are built in here and nowhere else, not by any other route either.
 * That is the whole point of the single entry. It used to be two: every Tool wrapped its
 * own body in
 * {@code try { of(...) } catch (ToolFailure e) { failure(e) }}, five copies of one contract,
 * with nothing but habit keeping the sixth honest.
 *
 * <p>Every failure travels as {@code isError: true}, never as a JSON-RPC protocol error. A
 * protocol error means the call did not happen and never reaches the model as tool output —
 * it would deliver the Remedy where its intended reader cannot see it. A missing {@code gh}
 * is still a Tool that ran and could not do its job.
 *
 * <p><strong>The trace is the same argument applied a second time.</strong> One entry means
 * one line per call, in one shape, without five Tools each deciding what a call worth
 * recording looks like. ADR-0013 fixes what that line carries; the short version is that it
 * records the <em>shape</em> of a call — which Tool, which repository, how long, how big,
 * how it ended — and never its <em>content</em>. No issue title, no body, no comment text,
 * no label name reaches the log file, because a log that mirrors GitHub's content is a
 * disclosure surface that exists outside the protocol entirely.
 *
 * <p><strong>What a Tool can still get wrong.</strong> Not forgetting to catch — that no
 * longer compiles. What is left is doing the work <em>outside</em> the lambda: a
 * {@code ToolFailure} thrown out of a Tool method is caught by Spring AI's own callback,
 * which answers with {@code isError: true} and a text message and <em>no</em>
 * {@code structuredContent} — so the Remedy and the stderr vanish from the half ADR-0002
 * calls authoritative, while {@code isError} still looks right. Measured, not assumed. That
 * is the mistake {@code FailureContractAcceptanceTest} exists to catch. Work done outside
 * the lambda is also work the trace cannot see, which is a second reason for the same rule.
 */
final class ToolResults {

    private static final Logger log = LoggerFactory.getLogger(ToolResults.class);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * The MDC keys this class owns, removed in the {@code finally} below.
     *
     * <p>Removed rather than {@link MDC#clear()}: Spring AI dispatches Tool calls on a pooled
     * thread ({@code pool-2-thread-N} in the log), so a key left behind decorates whatever
     * call lands on that thread next — a trace field belonging to a call that already ended.
     * Clearing would fix that too, and would also discard anything a future caller had put
     * there, which is not this class's to discard.
     */
    private static final String[] KEYS =
            {"tool", "callId", "repo", "outcome", "remedy", "durationMs", "resultBytes"};

    private ToolResults() {
    }

    /**
     * Runs a Tool's work, reports whichever way it goes, and records that it happened.
     *
     * <p>Everything a Tool does that can fail belongs inside {@code body} — the {@code gh}
     * call, the mapping, and any judgement the Tool makes on what came back. Failures leave
     * it by being thrown, whether they came from {@code GhCli}, from {@code Cursors}, or from
     * the Tool itself; there is no second way to report one.
     *
     * <p>{@code body} must produce a value. A {@code null} would serialise as the literal
     * {@code null} inside an otherwise successful result — every mapper here constructs a
     * record and none can return one, so this is a contract stated rather than enforced.
     *
     * <p>{@code tool}, {@code owner} and {@code repo} are in the MDC <em>before</em>
     * {@code body} runs, so every line a Tool's work emits from inside carries them without
     * being handed anything: {@code GhCli}'s argv warning is tagged with the call it belongs
     * to, and so is the write line {@code CommentTools} emits itself. That is what makes
     * {@code callId} worth generating — the three lines a failing write can produce are not
     * guaranteed to be adjacent under a pooled dispatcher, and the id is what joins them.
     *
     * <p><strong>A call rejected by the input schema never reaches here</strong> and so
     * leaves no trace at all: the SDK validates before dispatch (ADR-0011), builds its own
     * result and returns it. {@code durationMs} therefore measures the Tool method body, not
     * the request.
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
            // Serialised here rather than in `of` so the trace can report what the Client
            // actually receives. Measuring the record instead would report a number no one
            // can check against anything.
            String json = JSON.writeValueAsString(body.get());
            MDC.put("resultBytes", String.valueOf(json.getBytes(StandardCharsets.UTF_8).length));
            end(start, "ok");
            log.info("{} ok", tool);
            return CallToolResult.builder().addTextContent(json).build();
        } catch (ToolFailure e) {
            // The Remedy, not the message and not the stderr. The classification is the part
            // that is safe to aggregate; GhCli's own line already carries the detail, under
            // the same callId, for the failures that came from there.
            MDC.put("remedy", e.remedy().name());
            end(start, "error");
            log.info("{} failed", tool);
            return failure(e);
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

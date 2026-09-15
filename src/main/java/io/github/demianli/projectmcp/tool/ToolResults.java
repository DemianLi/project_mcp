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
 * 所有 Tool 工作的入口，處理成功、可控失敗（{@link ToolFailure}）與致命錯誤。組出回應
 * 形狀，並記錄呼叫的中繼資料（從不記錄內容）。
 *
 * <p>失敗以 {@code isError: true} 加上 structured content（Remedy、stderr、重試等待
 * 時間）回報，而不是 JSON-RPC 錯誤。structured content 讓 Client 與模型都看得到 Remedy；
 * 協定錯誤則模型看不到。
 *
 * <p><strong>工作必須放在 lambda 內。</strong>在外面拋出的 {@code ToolFailure} 會被
 * Spring AI 自己的 handler 接住，並丟掉 {@code structuredContent}。
 * {@code FailureContractAcceptanceTest} 會抓出這種錯誤。
 *
 * <p>記錄呼叫的結構（Tool 名稱、repo、耗時、位元組數、結果），從不記錄內容（body、留言
 * 文字、label 名稱）。見 docs/design.md#logging。
 */
final class ToolResults {

    private static final Logger log = LoggerFactory.getLogger(ToolResults.class);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * 本 class 設定的 MDC key，在 finally 區塊中移除。
     * Tool 呼叫在 thread pool 的執行緒上執行，key 必須清掉，才不會滲入後續的呼叫。
     */
    private static final String[] KEYS =
            {"tool", "callId", "repo", "outcome", "remedy", "durationMs", "resultBytes"};

    private ToolResults() {
    }

    /**
     * 執行 Tool 的工作並處理結果：成功、{@link ToolFailure} 或致命錯誤。
     *
     * <p>所有工作都必須在 {@code body} 內進行。在外面拋出的失敗本方法接不到；在內部拋出的
     * 失敗會被接住並轉成結構化結果。
     *
     * <p>執行 {@code body} 前先設定 MDC 欄位，讓 Tool 的所有輸出都帶有 {@code callId}、
     * {@code tool} 與 {@code repo}，即使多個 Tool 同時在 pool 執行緒上執行也能區分。
     *
     * @param tool  Tool 在 wire 上的名稱，例如 {@code list_issues}
     * @param owner repository owner，作為結構資訊記錄
     * @param repo  repository 名稱，作為結構資訊記錄
     * @param body  產生要回傳的值，或拋出 {@link ToolFailure}
     */
    static CallToolResult attempt(String tool, String owner, String repo, Supplier<?> body) {
        long start = System.nanoTime();
        MDC.put("tool", tool);
        MDC.put("callId", callId());
        MDC.put("repo", owner + "/" + repo);
        try {
            // 序列化結果，trace 才能回報實際大小。
            String json = JSON.writeValueAsString(body.get());
            MDC.put("resultBytes", String.valueOf(json.getBytes(StandardCharsets.UTF_8).length));
            end(start, "ok");
            log.info("{} ok", tool);
            return CallToolResult.builder().addTextContent(json).build();
        } catch (ToolFailure e) {
            // 記錄 Remedy（分類結果），而非完整訊息。
            MDC.put("remedy", e.remedy().name());
            end(start, "error");
            log.info("{} failed", tool);
            return failure(e);
        } catch (Error e) {
            // 致命錯誤代表行程已不可信任。立即停止而不嘗試回應：Client 能重啟已終止的
            // Server，面對不回應的 Server 卻只能等到逾時。
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

    /** 八個十六進位字元：足以串起同一檔案中的三行，又短到方便閱讀。 */
    private static String callId() {
        return String.format("%08x", ThreadLocalRandom.current().nextInt());
    }

    private static void end(long start, String outcome) {
        MDC.put("outcome", outcome);
        MDC.put("durationMs",
                String.valueOf(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)));
    }

    /**
     * 組出失敗回應：給人看的文字，與給模型用的 structured content。
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

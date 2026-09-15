package io.github.demianli.projectmcp.wire;

import java.util.Map;

/**
 * 讓每個 Tool 一路執行到呼叫 {@code gh} 的參數。
 *
 * <p>Acceptance 測試用這些參數驅動每個 Tool。每組參數都必須通過呼叫前的驗證，例如
 * add_issue_comment 的 body 若為空白，會在 gh 執行前以 FIX_REQUEST 失敗。寫入 Tool 的參數
 * 必須走到寫入路徑；停在呼叫前的檢查，會讓 WritePartitionAcceptanceTest 在預期
 * CHECK_BEFORE_RETRY 時看到 FIX_REQUEST。
 *
 * <p>只符合 schema 型別不夠，還必須通過語意檢查。缺少參數視為失敗，所以沒有參數的 Tool
 * 會讓測試失敗而不是略過。
 */
final class ToolCalls {

    private ToolCalls() {
    }

    /** {@code name} 的參數；此檔案不認得該 Tool 時回傳 {@code null}。 */
    static Map<String, Object> forTool(String name) {
        return switch (name) {
            case "list_issues", "list_labels" ->
                    Map.of("owner", "DemianLi", "repo", "project_mcp");

            case "get_issue" ->
                    Map.of("owner", "cli", "repo", "cli", "number", 14356);

            case "list_issue_comments" ->
                    Map.of("owner", "cli", "repo", "cli", "number", 14361);

            // `body` 刻意非空白：空白的 body 會在 `gh` 執行前被拒絕。
            case "add_issue_comment" ->
                    Map.of("owner", "DemianLi", "repo", "project-mcp-sandbox",
                            "number", 1, "body", "hello");

            default -> null;
        };
    }
}

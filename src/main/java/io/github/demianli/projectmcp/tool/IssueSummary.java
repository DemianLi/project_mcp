package io.github.demianli.projectmcp.tool;

import java.util.List;

/**
 * {@code list_issues} 回傳的 issue 摘要。
 *
 * <p>七個欄位，足以讓 Client 決定要進一步讀哪個 issue。不含 body（那由 {@code get_issue}
 * 提供）。
 *
 * @param labels 與 assignees：攤平成字串，可直接當篩選參數使用
 * @param updatedAt GitHub 回報的 ISO-8601 時間戳（保留為 String）
 */
public record IssueSummary(
        int number,
        String title,
        String state,
        List<String> labels,
        List<String> assignees,
        String url,
        String updatedAt) {
}

package io.github.demianli.projectmcp.tool;

import java.util.List;

/**
 * {@code get_issue} 回傳的完整 issue 內容。
 *
 * <p>十二個欄位：{@link IssueSummary} 的七個（完全相同，Client 只需學一次形狀），另加
 * 五個。沒有 {@code comments} 欄位，留言由專屬的 Tool 提供。
 *
 * @param labels 與 assignees：攤平成字串，可直接當篩選參數使用
 * @param author 只有登入名稱
 * @param closedAt open 時為 null；此時 {@code stateReason} 為空字串（與 gh 回報相同）
 * @param stateReason closed 時為 COMPLETED 或 NOT_PLANNED
 */
public record IssueDetail(
        int number,
        String title,
        String state,
        List<String> labels,
        List<String> assignees,
        String url,
        String updatedAt,
        String body,
        String author,
        String createdAt,
        String closedAt,
        String stateReason) {
}

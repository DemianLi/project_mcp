package io.github.demianli.projectmcp.tool;

/**
 * {@code list_labels} 回傳的 label。
 *
 * <p>兩個欄位：name（與篩選參數所用的字串相同）與 description（repository 未設定時為
 * 空字串）。
 */
public record LabelSummary(String name, String description) {
}

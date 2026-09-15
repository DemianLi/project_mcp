package io.github.demianli.projectmcp.tool;

import java.util.List;

/**
 * 所有 {@code list_*} Tools 的標準回應 Envelope。
 *
 * <p>結構與欄位名稱一致，Client 只需學一次形狀。
 *
 * @param count items 陣列的長度；明確給出，避免模型數錯。
 * @param truncated 此頁之後是否還有更多項目
 */
public record ListResult<T>(List<T> items, int count, boolean truncated) {

    /**
     * 從多要一筆的結果建立 Envelope。
     *
     * <p>porcelain 指令（{@code gh issue list}、{@code gh label list}）無法直接回報截斷，
     * 所以多要一列：多出的那一列存在就代表還有更多，回傳前將它丟掉。
     */
    public static <T> ListResult<T> of(List<T> fetchedWithSpare, int limit) {
        boolean truncated = fetchedWithSpare.size() > limit;
        List<T> items = List.copyOf(
                truncated ? fetchedWithSpare.subList(0, limit) : fetchedWithSpare);
        return new ListResult<>(items, items.size(), truncated);
    }
}

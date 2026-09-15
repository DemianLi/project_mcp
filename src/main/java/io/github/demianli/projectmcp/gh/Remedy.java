package io.github.demianli.projectmcp.gh;

/**
 * 呼叫者對一次失敗<em>接下來</em>該怎麼做。失敗依可採取的行動分類，而不是依原因。
 * 見 docs/design.md#failure-contract。
 */
public enum Remedy {

    /**
     * 重試同一個呼叫。可能附帶等待時間，此時在時間到之前不要重試。等待時間只來自 rate limit，
     * 可能是 {@code gh} 指明的，或本 Server 自己對寫入的限制，從不來自逾時。
     */
    RETRY,

    /**
     * 本 Server 無法讀到結果的寫入。先用 {@code list_issue_comments} 確認是否已寫入再決定：
     * 留言在就算成功；不在就 {@link #RETRY}。見 docs/design.md#writes。
     */
    CHECK_BEFORE_RETRY,

    /** 照原樣呼叫不可能成功，須修改參數。 */
    FIX_REQUEST,

    /** 呼叫者無法改變任何事，須由人修復環境。 */
    ASK_OPERATOR,

    /**
     * {@code gh} 以本 Server 無法辨識的方式失敗。能依據的只有隨附的資訊：通常是原樣的
     * {@code gh} stderr；唯一一條 {@code gh} 沒有輸出的路徑（讀取的輸出無法讀回），
     * 則以 Java 例外字串代替。
     */
    UNKNOWN
}

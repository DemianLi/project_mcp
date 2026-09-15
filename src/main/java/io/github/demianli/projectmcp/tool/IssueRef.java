package io.github.demianli.projectmcp.tool;

/**
 * repository 與 issue 編號的組合。
 *
 * <p>作為一組交給 {@link Cursors}，用來標註 cursor 與做跨 issue 驗證。Spring AI 從參數
 * 推導每個 Tool 的 input schema，所以 owner、repo 與 number 從 Client 分開傳入，在這裡
 * 組合。
 */
public record IssueRef(String owner, String repo, int number) {

    /** cursor 名稱與失敗訊息使用的格式：{@code owner/repo#number}。 */
    String reference() {
        return owner + "/" + repo + "#" + number;
    }

    /**
     * {@code other} 是否指向這個 issue（不分大小寫）。
     *
     * <p>GitHub 解析 owner 與 repository 名稱時不分大小寫。使用 {@code equalsIgnoreCase}，
     * 避免依 locale 轉換大小寫（例如土耳其語把 {@code I} 轉成 {@code i}）。
     */
    boolean isNamedBy(String other) {
        return reference().equalsIgnoreCase(other);
    }
}

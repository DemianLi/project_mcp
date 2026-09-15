package io.github.demianli.projectmcp.tool;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import io.github.demianli.projectmcp.gh.Remedy;
import io.github.demianli.projectmcp.gh.ToolFailure;

/**
 * 在 GitHub cursor 外包上所屬的 issue，避免 cursor 被拿到別的 issue 上而悄悄出錯。
 *
 * <p>GitHub 的 cursor 對 Client 是不透明的，但用在另一個 issue 上時會悄悄給出錯誤結果。
 * 包上 issue 參照後，送回時就能驗證。
 */
final class Cursors {

    /** 分隔 cursor 所屬的 issue 與 GitHub 自己的 cursor。 */
    private static final char SEPARATOR = '|';

    private Cursors() {
    }

    /** 包裝 {@code ghCursor}；沒有下一頁時回傳 {@code null}。 */
    static String wrap(IssueRef issue, String ghCursor) {
        if (ghCursor == null) {
            return null;
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (issue.reference() + SEPARATOR + ghCursor)
                        .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 從本 Server 為同一個 issue 發出的 cursor 還原 GitHub 的 cursor。
     *
     * @return 要送給 {@code gh} 的 cursor；Client 沒送時為 {@code null}
     * @throws ToolFailure cursor 無法解讀或屬於另一個 issue 時
     */
    static String unwrap(IssueRef issue, String clientCursor) throws ToolFailure {
        if (clientCursor == null || clientCursor.isBlank()) {
            return null;
        }

        String decoded;
        try {
            decoded = new String(Base64.getUrlDecoder().decode(clientCursor),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw unreadable();
        }

        int boundary = decoded.indexOf(SEPARATOR);
        if (boundary < 0) {
            throw unreadable();
        }

        String from = decoded.substring(0, boundary);
        if (!issue.isNamedBy(from)) {
            throw new ToolFailure(Remedy.FIX_REQUEST,
                    "That `cursor` came from " + from + ", but this call asks about "
                            + issue.reference() + ". A cursor is only valid for the issue it "
                            + "was issued for. Omit it to start from the newest comments of "
                            + issue.reference() + ".",
                    "", null);
        }
        return decoded.substring(boundary + 1);
    }

    private static ToolFailure unreadable() {
        return new ToolFailure(Remedy.FIX_REQUEST,
                "That `cursor` is not one this Server issued. Pass back the `nextCursor` "
                        + "from a previous response unchanged, or omit it to start from the "
                        + "newest comments.",
                "", null);
    }

}

package io.github.demianli.projectmcp.gh;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * 寫出一個替身 {@code gh} 執行檔。
 *
 * <p>executable bit 不可或缺。少了它，{@link ProcessBuilder#start()} 會拋出與「執行檔
 * 不存在」無法區分的 {@link IOException}：這樣寫出的 script 會讓「執行檔不存在」的測試
 * 因錯誤原因通過，其他測試則以看起來正是契約在運作的方式失敗。同理，「執行檔不存在」的
 * 情境一律指向不存在的路徑，而不是單純不可執行的檔案。
 */
public final class FakeGh {

    private FakeGh() {
    }

    /** 執行 {@code body} 的 {@code gh}，回傳要交給 {@link GhCli} 的路徑。 */
    public static String writing(Path dir, String body) throws IOException {
        Path script = dir.resolve("gh");
        Files.writeString(script, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        return script.toString();
    }

    /** 輸出 {@code stderr} 並以非零結束的 {@code gh}。 */
    public static String failing(Path dir, String stderr) throws IOException {
        return writing(dir, "cat >&2 <<'STDERR'\n" + stderr + "\nSTDERR\nexit 1");
    }
}

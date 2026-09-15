package io.github.demianli.projectmcp.tool;

import java.util.Locale;

/** {@code list_issues} 的 issue 狀態篩選。用 enum 讓 schema 能驗證。 */
public enum IssueState {

    OPEN,
    CLOSED,
    ALL;

    /** {@code gh --state} 文件所用的拼法。 */
    public String forGh() {
        return name().toLowerCase(Locale.ROOT);
    }
}

package io.github.demianli.projectmcp.tool;

import java.util.Locale;

/** Issue state filter for {@code list_issues}. Enum to enable schema validation. */
public enum IssueState {

    OPEN,
    CLOSED,
    ALL;

    /** The spelling {@code gh --state} documents. */
    public String forGh() {
        return name().toLowerCase(Locale.ROOT);
    }
}

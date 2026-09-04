package io.github.demianli.projectmcp.tool;

import java.util.Locale;

/**
 * Which issues {@code list_issues} asks for.
 *
 * <p>An enum rather than a {@code String} so the schema Spring AI derives from the method
 * signature carries an {@code enum} constraint: a bad value is refused at the schema layer
 * instead of becoming a {@code gh} runtime error. That is the "typed subset of gh"
 * positioning cashed out in one type.
 */
public enum IssueState {

    OPEN,
    CLOSED,
    ALL;

    /** The spelling {@code gh --state} documents. */
    public String forGh() {
        return name().toLowerCase(Locale.ROOT);
    }
}

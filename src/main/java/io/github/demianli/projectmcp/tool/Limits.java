package io.github.demianli.projectmcp.tool;

/**
 * Limit validation rule shared by all {@code list_*} Tools: default 30, clamped to 1–100.
 *
 * <p>The ceiling protects Client context. The floor accommodates {@code gh} which rejects 0
 * and negative values.
 */
final class Limits {

    static final int DEFAULT = 30;
    static final int MAX = 100;

    private Limits() {
    }

    static int clamp(Integer limit) {
        if (limit == null) {
            return DEFAULT;
        }
        return Math.clamp(limit.intValue(), 1, MAX);
    }
}

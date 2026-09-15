package io.github.demianli.projectmcp.tool;

/**
 * 所有 {@code list_*} Tools 共用的 limit 規則：預設 30，限制在 1–100。
 *
 * <p>上限保護 Client 的 context；下限配合 {@code gh}，因為它拒絕 0 與負值。
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

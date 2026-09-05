package io.github.demianli.projectmcp.tool;

/**
 * The one {@code limit} rule, shared by every {@code list_*} Tool.
 *
 * <p>Shared rather than repeated because ADR-0004 states it as a single rule holding across
 * Tools, and a copied constant would make that sentence a coincidence instead of a fact.
 *
 * <p>Neither bound comes from {@code gh}. The ceiling protects the Client's context window
 * and, measured on {@code rust-lang/rust}, its wall clock: 976 label rows take 4,812 ms
 * against 587 ms for 100. {@code gh} itself caps nothing — {@code gh label list --limit
 * 1000} returns all 976, and {@code gh issue list --limit 300} returns 300. The floor exists
 * because {@code gh} rejects {@code --limit 0} and negatives outright.
 *
 * <p>Clamping at both ends rather than clamping above and throwing below keeps one
 * predictable rule on one parameter.
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

package io.github.demianli.projectmcp.tool;

import java.util.List;

/**
 * Standard response envelope for all {@code list_*} Tools.
 *
 * <p>Shared structure with consistent key names enables Clients to learn the shape once.
 *
 * @param count size of items array; explicit to avoid model errors in counting.
 * @param truncated whether more entries exist beyond this page
 */
public record ListResult<T>(List<T> items, int count, boolean truncated) {

    /**
     * Builds envelope from results fetched with one spare entry.
     *
     * <p>Porcelain commands ({@code gh issue list}, {@code gh label list}) cannot report
     * truncation directly, so one extra row is requested. Presence of the spare signals
     * more exist; it is then dropped before returning.
     */
    public static <T> ListResult<T> of(List<T> fetchedWithSpare, int limit) {
        boolean truncated = fetchedWithSpare.size() > limit;
        List<T> items = List.copyOf(
                truncated ? fetchedWithSpare.subList(0, limit) : fetchedWithSpare);
        return new ListResult<>(items, items.size(), truncated);
    }
}

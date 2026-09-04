package io.github.demianli.projectmcp.tool;

import java.util.List;

/**
 * The Envelope: the fixed outer structure every {@code list_*} Tool returns.
 *
 * <p>Shared deliberately, so a Client learns the shape once and it holds everywhere. The
 * list key is the generic {@code items} rather than a per-Tool name for the same reason —
 * the Tool's name already says what is inside. See
 * {@code docs/adr/0001-list-issues-parameters-and-return-shape.md}.
 *
 * @param count number of entries in {@code items}. Redundant with {@code items.size()} by
 *     construction, and kept anyway: counting array elements is a step a model can get
 *     wrong, and one integer removes the opportunity.
 * @param truncated whether more entries exist beyond this response — not merely whether a
 *     requested limit was clamped. See {@link #of}.
 */
public record ListResult<T>(List<T> items, int count, boolean truncated) {

    /**
     * Builds an Envelope from a batch fetched with one spare entry.
     *
     * <p>{@code gh} cannot say whether more rows exist past the limit it was given, so the
     * caller asks it for {@code limit + 1}. Getting that many back is the proof that there
     * is more; the spare is then dropped.
     */
    public static <T> ListResult<T> of(List<T> fetchedWithSpare, int limit) {
        boolean truncated = fetchedWithSpare.size() > limit;
        List<T> items = List.copyOf(
                truncated ? fetchedWithSpare.subList(0, limit) : fetchedWithSpare);
        return new ListResult<>(items, items.size(), truncated);
    }
}

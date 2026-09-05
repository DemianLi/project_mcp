package io.github.demianli.projectmcp.tool;

/**
 * One label, as {@code list_labels} reports it.
 *
 * <p>Two fields. {@code gh label list --json} offers eight; the other six —
 * {@code color}, {@code id}, {@code isDefault}, {@code createdAt}, {@code updatedAt},
 * {@code url} — are excluded by ADR-0003's rule, that a field earns its place only if the
 * Client can <em>do</em> something with it. Keeping all eight would be 284,739 bytes on
 * {@code rust-lang/rust} against 86,274. Fixed by
 * {@code docs/adr/0004-list-labels-tool-shape-parameters-and-return.md}.
 *
 * <p>Note the asymmetry with {@link IssueSummary#labels()}, which reports labels as bare
 * strings. It is deliberate, and {@code CONTEXT.md}'s Label entry names it: as an issue's
 * <em>attribute</em> a label is just its name, which is also the string a Client passes
 * back to filter by; as an <em>object of choice</em> the name alone often cannot tell two
 * labels apart, and the description is what does.
 *
 * @param name the exact string {@code list_issues} accepts in its {@code labels} parameter.
 * @param description empty for labels that have none, passed through as {@code ""} rather
 *     than dropping the key. Not a rare shape: 20 of {@code cli/cli}'s 83 labels have no
 *     description. Same treatment {@link IssueDetail} gives {@code gh}'s own spellings of
 *     absence.
 */
public record LabelSummary(String name, String description) {
}

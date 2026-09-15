package io.github.demianli.projectmcp.tool;

/**
 * A label returned by {@code list_labels}.
 *
 * <p>Two fields: name (same string used as filter parameter) and description (empty when not
 * set by the repository).
 */
public record LabelSummary(String name, String description) {
}

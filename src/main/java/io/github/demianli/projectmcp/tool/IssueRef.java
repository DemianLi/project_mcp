package io.github.demianli.projectmcp.tool;

/**
 * Repository and issue number together.
 *
 * <p>Carried as a bundle to {@link Cursors} for cursor naming and cross-issue validation.
 * Spring AI derives each Tool's input schema from its parameters, so owner, repo, and number
 * arrive separately from a Client and are grouped here.
 */
public record IssueRef(String owner, String repo, int number) {

    /** Format for cursor names and failure messages: {@code owner/repo#number}. */
    String reference() {
        return owner + "/" + repo + "#" + number;
    }

    /**
     * Whether {@code other} names this issue, case-insensitively.
     *
     * <p>GitHub resolves owner and repository names that way. Uses {@code equalsIgnoreCase}
     * to avoid locale-dependent folding (Turkish {@code I} to {@code i}).
     */
    boolean isNamedBy(String other) {
        return reference().equalsIgnoreCase(other);
    }
}

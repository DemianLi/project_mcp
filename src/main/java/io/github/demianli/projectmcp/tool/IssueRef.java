package io.github.demianli.projectmcp.tool;

/**
 * Which issue, in which repository — the three values a Client sends together and this
 * Server passes around together.
 *
 * <p><strong>It exists to end a pass-through.</strong> {@code CommentMapper.toPage} took
 * {@code owner}, {@code repo} and {@code number} and read none of them — its javadoc said
 * outright that they were there only so {@code Cursors} could name the issue — so a pure
 * function of a JSON string carried three arguments that were not about the JSON, and
 * {@code Cursors} took the same three twice more. What the record holds is what was
 * actually being done with them: the name a cursor carries, and the check that a name
 * coming back is the same issue.
 *
 * <p>{@link Cursors} and the one Tool that feeds it are today's only consumers. That is a
 * fact about now rather than the reason for the record — a single consumer would be a
 * hypothetical seam, and the pass-through is what makes this one earn its keep either way.
 *
 * <p><strong>Not a domain term.</strong> Deliberately absent from `CONTEXT.md`: the glossary
 * names things that carry a decision, and this carries one rule and no choice. If a decision
 * ever attaches to it — a canonical spelling, say — that is the moment to give it a word.
 *
 * <p>Not the repository on its own either. {@code owner + "/" + repo} is composed three more
 * times, in the porcelain argv of {@code list_issues}, {@code get_issue} and
 * {@code list_labels}. That is a different thing being named — a repository, not an issue in
 * one — and three concatenations of it do not want a type yet.
 *
 * <p>Tool method signatures stay flat. Spring AI derives each Tool's input schema from its
 * parameters, so the three arrive separately from a Client and are put together here, inside
 * the method body.
 */
public record IssueRef(String owner, String repo, int number) {

    /**
     * The form a cursor carries and a failure message quotes: {@code owner/repo#number}.
     *
     * <p>Never parsed back apart. {@link Cursors} compares it whole, and the separator it
     * puts around it works because neither an owner, a repository name, nor a decimal number
     * may contain one.
     */
    String reference() {
        return owner + "/" + repo + "#" + number;
    }

    /**
     * Whether {@code other} names this same issue, however it was spelled.
     *
     * <p>Case-insensitively, because GitHub resolves an owner and a repository name that
     * way. Measured: {@code repository(owner:"cli", name:"cli")}, {@code owner:"CLI"
     * name:"CLI"} and {@code owner:"cLi" name:"Cli"} all answer
     * {@code nameWithOwner: "cli/cli"}. Comparing exactly refused a cursor that was never
     * wrong, and said so in a sentence naming the same issue on both sides of the word
     * "but".
     *
     * <p>{@code equalsIgnoreCase} rather than lowercasing both: {@code String.toLowerCase()}
     * without a {@code Locale} folds by the default one, and in a Turkish locale {@code I}
     * does not become {@code i}.
     *
     * <p>The rule is here rather than at the comparison, and canonicalising when a cursor is
     * <em>issued</em> was rejected: which spelling is canonical is GitHub's to say, and it
     * only says so in a response.
     */
    boolean isNamedBy(String other) {
        return reference().equalsIgnoreCase(other);
    }
}

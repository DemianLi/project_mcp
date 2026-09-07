package io.github.demianli.projectmcp.tool;

/**
 * Which issue, in which repository — the three values a Client sends together and this
 * Server passes around together.
 *
 * <p>It exists for {@link Cursors} and the one Tool that feeds it. A cursor this Server
 * issues names the issue it came from, and that name is checked on the way back; the name
 * and the check are what this record holds. Before it, {@code CommentMapper.toPage} took
 * {@code owner}, {@code repo} and {@code number} and read none of them — its javadoc said
 * outright that they were there only so {@code Cursors} could name the issue — and a pure
 * function of a JSON string carried three arguments that were not about the JSON.
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

package io.github.demianli.projectmcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.demianli.projectmcp.gh.Remedy;
import io.github.demianli.projectmcp.gh.ToolFailure;
import org.junit.jupiter.api.Test;

/**
 * Coverage layer: the rule itself, including the half of it that deliberately does nothing.
 *
 * <p>The Acceptance layer proves no Tool lets a slash reach {@code gh}. This one pins why the
 * rule stops where it does — a guard that grows to "validate the parameters" is a guard that
 * starts answering questions {@code gh} already answers correctly, and the argument for not
 * doing that lives nowhere a compiler can see it.
 */
class ReposTest {

    @Test
    void composesTheSlugGhExpects() {
        assertThat(Repos.slug("DemianLi", "project_mcp")).isEqualTo("DemianLi/project_mcp");
    }

    @Test
    void refusesASlashInEitherHalf() {
        // The measured shape from issue #37: this owner sent the Server to
        // https://127.0.0.1:8099/api/graphql, and the refusal came back as RETRY.
        assertThatThrownBy(() -> Repos.slug("127.0.0.1:8099/a", "b"))
                .isInstanceOfSatisfying(ToolFailure.class,
                        failure -> assertThat(failure.remedy()).isEqualTo(Remedy.FIX_REQUEST))
                .hasMessageContaining("`owner`");

        assertThatThrownBy(() -> Repos.slug("DemianLi", "project_mcp/extra"))
                .isInstanceOfSatisfying(ToolFailure.class,
                        failure -> assertThat(failure.remedy()).isEqualTo(Remedy.FIX_REQUEST))
                .hasMessageContaining("`repo`");
    }

    @Test
    void saysWhichParameterWasWrong() {
        // Not decoration. A Client that gets "one of your parameters is bad" has to guess,
        // and FIX_REQUEST means the caller is expected to fix it without guessing.
        assertThatThrownBy(() -> Repos.slug("a/b", "c"))
                .hasMessageContaining("`owner`")
                .hasMessageNotContainingAny("`repo` must");
    }

    /**
     * The deliberate hole.
     *
     * <p>An empty half composes {@code /c} or {@code a/}, and {@code gh} answers both with
     * {@code expected the "[HOST/]OWNER/REPO" format} — which {@code GhStderr}'s
     * malformed-name row already turns into {@code FIX_REQUEST}, measured, and re-checked by
     * {@code docs/measurements/gh-compatibility.sh}. Refusing here as well would be this
     * Server inventing a sixth failure to say what GitHub's own CLI says correctly, and
     * would need a reason this repository does not have.
     */
    @Test
    void leavesAnEmptyHalfToGh() {
        assertThat(Repos.slug("", "c")).isEqualTo("/c");
        assertThat(Repos.slug("a", "")).isEqualTo("a/");
    }
}

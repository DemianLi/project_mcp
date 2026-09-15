package io.github.demianli.projectmcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.demianli.projectmcp.gh.Remedy;
import io.github.demianli.projectmcp.gh.ToolFailure;
import org.junit.jupiter.api.Test;

/**
 * Tests the slash-validation rule for owner and repo parameters.
 *
 * <p>Includes the deliberate gap: empty halves and trailing slashes are left to gh.
 */
class ReposTest {

    @Test
    void composesTheSlugGhExpects() {
        assertThat(Repos.slug("DemianLi", "project_mcp")).isEqualTo("DemianLi/project_mcp");
    }

    @Test
    void refusesASlashInEitherHalf() {
        // A slash in owner would be read as a host, routing to a different endpoint.
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

    /** Empty halves and trailing slashes are validated by gh, not by this Server. */
    @Test
    void leavesAnEmptyHalfToGh() {
        assertThat(Repos.slug("", "c")).isEqualTo("/c");
        assertThat(Repos.slug("a", "")).isEqualTo("a/");
    }
}

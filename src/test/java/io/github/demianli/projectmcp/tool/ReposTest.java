package io.github.demianli.projectmcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.demianli.projectmcp.gh.Remedy;
import io.github.demianli.projectmcp.gh.ToolFailure;
import org.junit.jupiter.api.Test;

/**
 * 驗證 owner 與 repo 參數的斜線規則。
 *
 * <p>也涵蓋刻意留下的空隙：空的一半與結尾斜線交給 gh 處理。
 */
class ReposTest {

    @Test
    void composesTheSlugGhExpects() {
        assertThat(Repos.slug("DemianLi", "project_mcp")).isEqualTo("DemianLi/project_mcp");
    }

    @Test
    void refusesASlashInEitherHalf() {
        // owner 中的斜線會被當成主機，把請求送到別的端點。
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
        // 不是裝飾。收到「某個參數有誤」的 Client 只能用猜的；FIX_REQUEST 意味著呼叫者應該
        // 不必猜就能修正。
        assertThatThrownBy(() -> Repos.slug("a/b", "c"))
                .hasMessageContaining("`owner`")
                .hasMessageNotContainingAny("`repo` must");
    }

    /** 空的一半與結尾斜線由 gh 驗證，不由本 Server 驗證。 */
    @Test
    void leavesAnEmptyHalfToGh() {
        assertThat(Repos.slug("", "c")).isEqualTo("/c");
        assertThat(Repos.slug("a", "")).isEqualTo("a/");
    }
}

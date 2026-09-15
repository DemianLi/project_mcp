package io.github.demianli.projectmcp.gh;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.demianli.projectmcp.gh.GhStderr.Branch;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * 驗證 {@code gh} 的 stderr 被分類成什麼，以及分類表整體的不變式。
 *
 * <p>不啟動子行程：每個測試都把字串交給 {@link GhStderr#classify(String)}，這正是
 * {@link GhStderr} 與 {@link GhCli} 分開的原因。會走到真正機制的情況由
 * {@code GhCliFailureTest} 涵蓋。
 *
 * <p><strong>兩類測試。</strong>第一類逐列驗證措辭，證明每句訊息都正確。第二類走訪
 * {@link GhStderr#branches()} 檢查不變式：沒有樣本同時落在兩列、沒有失效的 marker、
 * 沒有 marker 包含另一列的 marker，以及分類表只產生允許的 Remedy。第二類不需要寫死
 * 列清單，新增的列會自動被涵蓋。
 *
 * <p>樣本字串放在它所佐證的那一列上。措辭測試送出的正是建表時依據的字串，樣本一改，
 * 使用它的測試也跟著反映。
 */
class GhStderrTest {

    /**
     * 整張表中唯一包含 {@code needle} 的樣本。
     *
     * <p>唯一性是斷言而不是假設：否則 needle 一旦同時比對到兩個樣本，測試就會悄悄指向
     * 錯的列。
     */
    private static String sample(String needle) {
        List<String> found = GhStderr.branches().stream()
                .flatMap(branch -> branch.samples().stream())
                .map(GhStderr.Sample::stderr)
                .filter(stderr -> stderr.contains(needle))
                .toList();
        assertThat(found)
                .as("`%s` should pick out exactly one sample in the table", needle)
                .hasSize(1);
        return found.get(0);
    }

    /** markers 比對得到 {@code stderr} 的每一列，不論誰先被嘗試。 */
    private static List<Branch> allMatching(String stderr) {
        String lowered = stderr.toLowerCase(Locale.ROOT);
        return GhStderr.branches().stream().filter(branch -> branch.matches(lowered)).toList();
    }

    // ---------------------------------------------------------------- 逐列驗證

    @Test
    void networkUnreachableIsRetry() {
        ToolFailure f = GhStderr.classify(sample("connection refused"));
        assertThat(f.remedy()).isEqualTo(Remedy.RETRY);
        assertThat(f.retryAfterSeconds()).isNull();
        assertThat(f.stderr())
                .as("the verbatim stderr travels alongside, which is what bounds a "
                        + "misclassification")
                .contains("connection refused");
    }

    @Test
    void rateLimitIsRetryAndKeepsTheWaitWhenGhNamesOne() {
        ToolFailure withWait = GhStderr.classify(sample("retry after"));
        assertThat(withWait.remedy()).isEqualTo(Remedy.RETRY);
        assertThat(withWait.retryAfterSeconds()).isEqualTo(60);
        assertThat(withWait.getMessage())
                .as("the wait is in the sentence as well as the field -- the field is the "
                        + "only one a Client is obliged to read")
                .contains("Wait 60 seconds");
    }

    @Test
    void rateLimitWithoutAStatedWaitLeavesItUnset() {
        // rate limit 措辭沒有指明等待時間。契約寧可分類正確，也不猜一個未知的等待時間。
        ToolFailure f = GhStderr.classify(sample("secondary rate limit"));
        assertThat(f.remedy()).isEqualTo(Remedy.RETRY);
        assertThat(f.retryAfterSeconds()).isNull();
        assertThat(f.getMessage()).doesNotContain("Wait");
    }

    @Test
    void noSuchIssueNumberIsFixRequest() {
        ToolFailure f = GhStderr.classify(sample("issue or pull request"));
        assertThat(f.remedy()).isEqualTo(Remedy.FIX_REQUEST);
        assertThat(f.getMessage())
                .as("the caller is told which parameter to change")
                .contains("number");
    }

    @Test
    void theGraphqlWordingForTheSameThingIsAlsoFixRequest() {
        // 同一情況經由 GraphQL 路徑：`gh api graphql` 的措辭是 "an Issue with the number of"，
        // 單數，且沒有 "or pull request" 子句。
        ToolFailure f = GhStderr.classify(sample("an Issue with the number of"));
        assertThat(f.remedy()).isEqualTo(Remedy.FIX_REQUEST);
        assertThat(f.getMessage())
                .as("one sentence covers both causes, because the action is the same")
                .contains("pull request");
    }

    @Test
    void theTwoIssueWordingsSayDifferentThingsAboutPullRequests() {
        // 兩列互不吞併由分類表負責，noSampleLandsOnTwoRows 對每一對列斷言。這裡驗證走訪
        // 看不到的另一半：porcelain 的措辭能承諾 GraphQL 措辭無法承諾的事。
        assertThat(GhStderr.classify(sample("issue or pull request")).getMessage())
                .as("porcelain gets the sentence that can promise there is no pull request "
                        + "with that number either -- true there, false on GraphQL")
                .contains("no pull request with it either");
        assertThat(GhStderr.classify(sample("an Issue with the number of")).getMessage())
                .doesNotContain("no pull request with it either");
    }

    @Test
    void anUnusableCursorIsFixRequest() {
        // Cursors 會在呼叫 `gh` 前拒絕其他 issue 的 cursor，但無法拒絕 issue 正確、內層卻
        // 損壞的 cursor：它會送到 GitHub，而這就是 GitHub 的回應。
        ToolFailure f = GhStderr.classify(sample("not-a-cursor"));
        assertThat(f.remedy()).isEqualTo(Remedy.FIX_REQUEST);
        assertThat(f.getMessage()).contains("cursor");
    }

    @Test
    void repoNotFoundIsFixRequest() {
        assertThat(GhStderr.classify(sample("Repository with the name")).remedy())
                .isEqualTo(Remedy.FIX_REQUEST);
    }

    @Test
    void malformedRepoSlugIsFixRequest() {
        assertThat(GhStderr.classify(sample("\"[HOST/]OWNER/REPO\"")).remedy())
                .isEqualTo(Remedy.FIX_REQUEST);
        assertThat(GhStderr.classify(sample("'[HOST/]OWNER/REPO'")).remedy())
                .as("the single-quoted rendering is the row's second marker, and reaches "
                        + "the same sentence")
                .isEqualTo(Remedy.FIX_REQUEST);
    }

    @Test
    void issuesDisabledIsFixRequest() {
        assertThat(GhStderr.classify(sample("disabled issues")).remedy())
                .isEqualTo(Remedy.FIX_REQUEST);
    }

    @Test
    void badCredentialsIsAskOperator() {
        assertThat(GhStderr.classify(sample("Bad credentials")).remedy())
                .isEqualTo(Remedy.ASK_OPERATOR);
    }

    @Test
    void anAuthenticatedLoginWithoutThePermissionIsAskOperator() {
        // 權限不足的 fine-grained PAT：同一 token 先前成功過，這次被拒。少了這一列會落到
        // UNKNOWN，並錯誤地建議重新登入。
        ToolFailure f = GhStderr.classify(sample("personal access token"));

        assertThat(f.remedy()).isEqualTo(Remedy.ASK_OPERATOR);
        assertThat(f.getMessage()).doesNotContain("gh auth login");
        assertThat(f.getMessage()).contains("lacks permission");
    }

    @Test
    void theSameRefusalWordedForAnAppTokenIsAskOperatorToo() {
        // installation token 被拒，這是 gh 在 GitHub Actions 中得到的措辭。訊息不得提及 PAT
        // 專屬的細節。
        ToolFailure f = GhStderr.classify(sample("by integration"));

        assertThat(f.remedy()).isEqualTo(Remedy.ASK_OPERATOR);
        assertThat(f.getMessage()).doesNotContain("personal access token");
    }

    @Test
    void unrecognisedStderrIsUnknownAndSurvivesVerbatim() {
        // 無法辨識的 stderr，也就是底線：沒有任何一列比對到。透過 Tool 有型別的介面走不到
        // 這裡，所以出現就代表 Server 有 bug。見 docs/design.md#failure-contract。
        String blob = "unknown flag: --banana\n\nUsage:  gh issue list [flags]\n\nFlags:\n"
                + "      --app string         Filter by GitHub App author";
        ToolFailure f = GhStderr.classify(blob);
        assertThat(f.remedy()).isEqualTo(Remedy.UNKNOWN);
        assertThat(f.stderr()).isEqualTo(blob);
        assertThat(allMatching(blob)).as("nothing matched it, which is what UNKNOWN means")
                .isEmpty();
    }

    // ------------------------------------------------------------- 整張表

    @Test
    void noSampleLandsOnTwoRows() {
        // 先比對到者勝出。樣本若同時落在兩列，就會篤定地回傳錯的 Remedy，落敗的那一列也
        // 永遠走不到。本測試確保每個樣本恰好屬於它宣告的那一列。
        assertThat(GhStderr.branches()).as("an empty table would pass this vacuously")
                .isNotEmpty();

        for (Branch branch : GhStderr.branches()) {
            assertThat(branch.samples())
                    .as("row `%s` declares at least one sample", branch.name())
                    .isNotEmpty();

            for (GhStderr.Sample sample : branch.samples()) {
                assertThat(allMatching(sample.stderr()))
                        .as("`%s` belongs to row `%s` alone", sample.stderr(), branch.name())
                        .containsExactly(branch);
            }
        }
    }

    @Test
    void everyMarkerIsExercisedByASampleOfItsOwnRow() {
        // 沒有任何樣本碰得到的 marker，從未被證明有效。最容易寫出這種 marker 的方式是用大寫：
        // classify 會先把 stderr 轉小寫，再與這些 marker 原樣比對，所以這裡的 `HTTP 401`
        // 永遠比對不到，看起來卻和正常的分支一模一樣。
        assertThat(GhStderr.branches()).isNotEmpty();

        for (Branch branch : GhStderr.branches()) {
            for (String marker : branch.markers()) {
                assertThat(branch.samples())
                        .as("row `%s` matches on `%s`, but states no sample containing it",
                                branch.name(), marker)
                        .anyMatch(sample -> sample.stderr().toLowerCase(Locale.ROOT)
                                .contains(marker));
            }
        }
    }

    @Test
    void noMarkerSwallowsAnotherRowsMarker() {
        // noSampleLandsOnTwoRows 看不到的失敗：若某列的 marker 包含前面某列的 marker，它對
        // 任何 stderr 都走不到，不只是對這裡寫下的樣本；要靠一個沒人想到要加的樣本才會發現。
        //
        // 刻意比這更嚴格。包含關係只會讓「後面」的列走不到，所以被拒絕的組合中有一半在現行
        // 順序下無害，而且只有在沒人調整順序時才無害。要避免的正是依賴順序，因此檢查不考慮
        // 順序。同一列內的 markers 不受此限：它們導向同一句訊息，重疊沒有代價。
        List<Branch> branches = GhStderr.branches();
        assertThat(branches).isNotEmpty();

        for (Branch outer : branches) {
            for (Branch inner : branches) {
                if (outer == inner) {
                    continue;
                }
                for (String a : outer.markers()) {
                    for (String b : inner.markers()) {
                        assertThat(a)
                                .as("row `%s` matches on `%s`, which contains row `%s`'s "
                                        + "`%s` -- whichever of the two is tried second can "
                                        + "never be reached", outer.name(), a, inner.name(), b)
                                .doesNotContain(b);
                    }
                }
            }
        }
    }

    @Test
    void theTableProducesThreeRemediesAndNeverTheOtherTwo() {
        // UNKNOWN 是底線而不是一列；CHECK_BEFORE_RETRY 屬於 GhCli 的寫入路徑，用於三種
        // 不讀取結果就放棄呼叫的結束方式。兩者都無法從 stderr 得出；帶有其中之一的列，等於
        // 暗中宣稱本 class 知道它不可能知道的事：呼叫實際做了什麼。
        assertThat(GhStderr.branches()).isNotEmpty();
        assertThat(GhStderr.branches()).extracting(Branch::remedy)
                .containsOnly(Remedy.RETRY, Remedy.FIX_REQUEST, Remedy.ASK_OPERATOR);
    }
}

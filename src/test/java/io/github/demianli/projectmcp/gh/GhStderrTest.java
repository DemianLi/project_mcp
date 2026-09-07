package io.github.demianli.projectmcp.gh;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.demianli.projectmcp.gh.GhStderr.Branch;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Coverage layer: what {@code gh}'s stderr is turned into, and what holds across the table.
 *
 * <p>No process anywhere. Everything here is a string going into a static method, which is
 * the whole reason {@link GhStderr} was split out of {@link GhCli} — the sibling
 * {@code GhCliFailureTest} keeps the tests that need a real subprocess, and needs one for
 * every case it has.
 *
 * <p><strong>Two kinds of test, and they do not overlap.</strong> The first kind asks whether
 * one row says the right thing: these carry the assertions about wording that no walk of the
 * table could make, and they are the reason a row's sentence can be trusted. The second kind
 * asks whether the rows disagree with each other — that no sample lands on two rows, that no
 * marker is dead, that no marker swallows another's, that the table produces the three
 * Remedies it is allowed to. Those were prose before this file existed: three comments in the
 * {@code if} chain saying "the two strings cannot both match", and one test covering one pair
 * of them.
 *
 * <p>The second kind keeps no list of its own. It walks {@link GhStderr#branches()}, so a row
 * added tomorrow is covered by all four without anyone touching this file — the same reason
 * {@code ArgvFlagAcceptanceTest} reads variable types off the GraphQL document rather than
 * off a table of its own.
 *
 * <p>Sample strings are not repeated here. They live on the row they justify, so the string a
 * wording test sends is the same object the table was built around; a row whose sample is
 * edited cannot leave a test asserting against the old one.
 */
class GhStderrTest {

    /**
     * The one sample in the whole table containing {@code needle}.
     *
     * <p>Uniqueness is asserted rather than assumed: a needle that started matching two
     * samples would otherwise silently point a test at the wrong row.
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

    /** Every row whose markers match {@code stderr}, ignoring which comes first. */
    private static List<Branch> allMatching(String stderr) {
        String lowered = stderr.toLowerCase(Locale.ROOT);
        return GhStderr.branches().stream().filter(branch -> branch.matches(lowered)).toList();
    }

    // ---------------------------------------------------------------- one row at a time

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
        // gh's rate-limit wording is unverified: it could not be provoked against the real
        // API, which is why both of this row's samples are UNMEASURED. The contract is built
        // so that not knowing it costs the wait, not the classification.
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
        // The same condition down the other route: `gh api graphql` says "an Issue with the
        // number of", singular and without the "or pull request" clause. Before ADR-0005
        // this fell through to UNKNOWN, and it is the whole reason list_issue_comments could
        // not simply inherit the failure contract unchanged.
        ToolFailure f = GhStderr.classify(sample("an Issue with the number of"));
        assertThat(f.remedy()).isEqualTo(Remedy.FIX_REQUEST);
        assertThat(f.getMessage())
                .as("one sentence covers both causes, because the action is the same")
                .contains("pull request");
    }

    @Test
    void theTwoIssueWordingsSayDifferentThingsAboutPullRequests() {
        // That neither row swallows the other is now the table's business, asserted for
        // every pair by noSampleLandsOnTwoRows. What is left here is the half a walk cannot
        // see: the porcelain wording can promise something the GraphQL one cannot.
        assertThat(GhStderr.classify(sample("issue or pull request")).getMessage())
                .as("porcelain gets the sentence that can promise there is no pull request "
                        + "with that number either -- true there, false on GraphQL")
                .contains("no pull request with it either");
        assertThat(GhStderr.classify(sample("an Issue with the number of")).getMessage())
                .doesNotContain("no pull request with it either");
    }

    @Test
    void anUnusableCursorIsFixRequest() {
        // Cursors refuses a cursor from the wrong issue before `gh` is called. It cannot
        // refuse a correctly-addressed wrapper whose inner half is corrupt: that reaches
        // GitHub, and this is what comes back.
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
        // Measured in #33: a fine-grained PAT with Issues: Read-only, refused by addComment
        // after the id lookup on the same token had already succeeded.
        ToolFailure f = GhStderr.classify(sample("personal access token"));

        assertThat(f.remedy()).isEqualTo(Remedy.ASK_OPERATOR);
        // The whole point of the row, and the regression this test exists to catch: before
        // it, this landed on UNKNOWN, and the nearest matching Remedy would have told an
        // operator to log in again -- which does not change what a login may do.
        assertThat(f.getMessage()).doesNotContain("gh auth login");
        assertThat(f.getMessage()).contains("lacks permission");
    }

    @Test
    void theSameRefusalWordedForAnAppTokenIsAskOperatorToo() {
        // UNMEASURED on purpose -- matched on the family for the reason the row gives. This
        // is the wording an installation token gets, which is what `gh` resolves inside
        // GitHub Actions, so it is the member of the family a real deployment is most likely
        // to meet. The sentence must stay free of anything true only of a PAT.
        ToolFailure f = GhStderr.classify(sample("by integration"));

        assertThat(f.remedy()).isEqualTo(Remedy.ASK_OPERATOR);
        assertThat(f.getMessage()).doesNotContain("personal access token");
    }

    @Test
    void unrecognisedStderrIsUnknownAndSurvivesVerbatim() {
        // The usage blob gh prints for a bad flag, and the one input here that is not a
        // sample: the floor is what no row matched, so it cannot have a row of its own.
        // Unreachable through a Tool's typed surface, so it can only mean a bug in this
        // Server -- ADR-0002 gives it no Remedy of its own, and this is where it lands.
        String blob = "unknown flag: --banana\n\nUsage:  gh issue list [flags]\n\nFlags:\n"
                + "      --app string         Filter by GitHub App author";
        ToolFailure f = GhStderr.classify(blob);
        assertThat(f.remedy()).isEqualTo(Remedy.UNKNOWN);
        assertThat(f.stderr()).isEqualTo(blob);
        assertThat(allMatching(blob)).as("nothing matched it, which is what UNKNOWN means")
                .isEmpty();
    }

    // ------------------------------------------------------------- across the whole table

    @Test
    void noSampleLandsOnTwoRows() {
        // The invariant three comments in the old if chain asserted in prose and one test
        // checked for one pair. First match wins, so a sample landing on two rows is not an
        // UNKNOWN and not a crash -- it is a confident wrong Remedy, which ADR-0002 puts in
        // its worst category, and the losing row quietly stops being reachable.
        assertThat(GhStderr.branches()).as("an empty table would pass this vacuously")
                .isNotEmpty();

        for (Branch branch : GhStderr.branches()) {
            assertThat(branch.samples())
                    .as("row `%s` states no sample, so nothing below examines it",
                            branch.name())
                    .isNotEmpty();

            for (GhStderr.Sample sample : branch.samples()) {
                assertThat(allMatching(sample.stderr()))
                        .as("`%s` is row `%s`'s own sample, so exactly that row should "
                                + "claim it", sample.stderr(), branch.name())
                        .containsExactly(branch);
            }
        }
    }

    @Test
    void everyMarkerIsExercisedByASampleOfItsOwnRow() {
        // A marker no sample reaches is a marker nothing has ever shown to work. The cheapest
        // way to write one is a capital letter: classify lowercases the stderr and compares
        // against these verbatim, so `HTTP 401` here would never match anything and would
        // look exactly like a working branch.
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
        // The failure noSampleLandsOnTwoRows cannot see: a row whose marker contains an
        // earlier row's is unreachable for every stderr, not just for the samples written
        // down here, and it would take a sample nobody thought to add to notice.
        //
        // Deliberately stricter than that. Containment only makes the *later* row
        // unreachable, so half the pairs this rejects are harmless as the table stands
        // today -- and stay harmless only as long as nobody reorders it. Depending on the
        // order is the thing being prevented, so the check ignores it. Markers within one
        // row are exempt: they lead to the same sentence, so overlap there costs nothing.
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
        // UNKNOWN is the floor rather than a row, and CHECK_BEFORE_RETRY belongs to GhCli's
        // write route, applied to the three exits that abandon a call without reading it.
        // Neither can be reached from stderr, and a row carrying one would be a quiet claim
        // that this class knows something it cannot know: what the call did.
        assertThat(GhStderr.branches()).isNotEmpty();
        assertThat(GhStderr.branches()).extracting(Branch::remedy)
                .containsOnly(Remedy.RETRY, Remedy.FIX_REQUEST, Remedy.ASK_OPERATOR);
    }
}

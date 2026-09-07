package io.github.demianli.projectmcp.gh;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads {@code gh}'s stderr and decides what the caller should do about it.
 *
 * <p>Best-effort by construction: these are substrings of messages {@code gh} chooses, not an
 * API. Two things bound the damage when a match is wrong or missing — the verbatim stderr
 * always travels alongside, so nothing is lost, and anything unmatched becomes
 * {@link Remedy#UNKNOWN} rather than a confident wrong answer.
 *
 * <p>This was {@code GhCli.classify()} until it became a table; ADRs 0002 through 0009 refer
 * to it under that name, and the branches below are the same branches those ADRs added.
 *
 * <p><strong>Why the branches are a table and not an {@code if} chain.</strong> They were a
 * chain, and it worked. What the chain could not express is the invariant three of its own
 * comments asserted in prose — "the two strings cannot both match, so the order is free" —
 * verified by a human reading it, three times, and by one test covering one pair. First match
 * wins, so a marker that turns out to contain an earlier one is not an {@code UNKNOWN}: it is
 * a <em>confident wrong Remedy</em>, which ADR-0002 puts in its worst category. As data the
 * rows can be walked, and {@code GhStderrTest} walks them.
 *
 * <p>Each row therefore carries the strings it was built from. Those strings already lived in
 * this package — quoted in comments beside the branch they justify, and again as literals in
 * the tests — and the only change is that a row cannot now be added without stating what it
 * was measured against. {@link Provenance} says whether that string came out of a real
 * {@code gh} or was written here; it is a record of evidence, not a proof, and nothing can
 * check it from inside this process.
 *
 * <p><strong>Not the whole failure contract.</strong> This class sees only a non-zero exit
 * with stderr to read. An absent binary, a timeout and an unreadable pipe never reach it —
 * they have no stderr — and the write route's {@link Remedy#CHECK_BEFORE_RETRY} is
 * {@link GhCli}'s, applied to those three exits and to none of the rows here. So the table
 * produces exactly three Remedies, and that is asserted rather than assumed.
 */
final class GhStderr {

    private GhStderr() {
    }

    /** Where a sample's wording came from. Evidence, not proof: nothing here can verify it. */
    enum Provenance {

        /** This exact string came out of a real {@code gh}, and the row says where. */
        MEASURED,

        /**
         * Written here, never seen from {@code gh}. It exercises a marker that was chosen
         * for a reason the row gives — a family whose other member is measured, or a wording
         * that could not be provoked.
         */
        UNMEASURED
    }

    /** One stderr this row is meant to catch. */
    record Sample(String stderr, Provenance provenance) {
    }

    /**
     * One classification: the markers that select it, and what the caller is then told.
     *
     * @param statedWait extracts a wait the stderr names, or {@code null} where no wording
     *     names one — which is every row but the rate limit. Named around
     *     {@code Object.wait}, which a record component may not shadow.
     */
    record Branch(String name, List<String> markers, Remedy remedy, String sentence,
                  Pattern statedWait, List<Sample> samples) {

        Branch(String name, List<String> markers, Remedy remedy, String sentence,
               List<Sample> samples) {
            this(name, markers, remedy, sentence, null, samples);
        }

        boolean matches(String loweredStderr) {
            return markers.stream().anyMatch(loweredStderr::contains);
        }

        /** The failure this row reports for {@code stderr}, which it has already matched. */
        ToolFailure toFailure(String stderr) {
            if (statedWait == null) {
                return new ToolFailure(remedy, sentence, stderr, null);
            }
            // Against the original rather than the lowered copy: the pattern is
            // case-insensitive itself, and the digits are what is being read out.
            Matcher m = statedWait.matcher(stderr);
            Integer seconds = m.find() ? Integer.valueOf(m.group(1)) : null;
            return new ToolFailure(remedy,
                    sentence + (seconds == null
                            ? "" : " Wait " + seconds + " seconds before retrying."),
                    stderr, seconds);
        }
    }

    /** {@code gh} sometimes names a wait; the wording is unverified, so this is best-effort. */
    private static final Pattern RETRY_AFTER =
            Pattern.compile("retry after (\\d+)", Pattern.CASE_INSENSITIVE);

    /**
     * The rows, in the order they are tried.
     *
     * <p>First match wins, and no row here depends on that: no marker contains another, which
     * {@code GhStderrTest} asserts rather than leaving to a reading. The order is still not
     * arbitrary — it is the order a maintainer should meet them in, which is why "not
     * authenticated" reads before "authenticated and still refused", and why the general
     * repository failure reads after the specific issue ones.
     */
    private static final List<Branch> BRANCHES = List.of(

            // Unmeasured, and deliberately so: ADR-0002 could not provoke it — the graphql
            // budget was untouched at 5000/5000 — and shaped the contract so that not
            // knowing the wording costs the wait, never the classification.
            new Branch("rate limit",
                    List.of("rate limit"),
                    Remedy.RETRY,
                    "GitHub is rate limiting this token.",
                    RETRY_AFTER,
                    List.of(new Sample("API rate limit exceeded. Please retry after 60 seconds.",
                                    Provenance.UNMEASURED),
                            new Sample("You have exceeded a secondary rate limit",
                                    Provenance.UNMEASURED))),

            // Two markers, both off the one line #6 measured. It elided the middle of that
            // line and the sample below fills it back in, which is why the sample is
            // UNMEASURED although the failure it describes was seen.
            //
            // It used to carry four more -- `no such host`, `network is unreachable`,
            // `i/o timeout`, `tls handshake timeout` -- Go net package wording, plausible and
            // never seen from `gh`. They were dropped rather than kept, because the coverage
            // property below wants a sample per marker and the only samples available were
            // ones written here: a guess would have been pinned in place by a test, which is
            // the reverse of what ADR-0002 means by "measured, not assumed".
            //
            // The cost is real, and how large it is cannot be stated here without measuring
            // the thing that was never measured: a network failure whose stderr happens to
            // carry `dial tcp` still lands on RETRY, and one that does not now reaches the
            // Client as UNKNOWN with `gh`'s stderr verbatim. Which failures fall on which
            // side is a fact about Go's wording that this repo does not have. To widen this
            // row again, provoke the failure and paste what came back; the sample is the
            // evidence, not a formality.
            new Branch("network",
                    List.of("connection refused", "dial tcp"),
                    Remedy.RETRY,
                    "GitHub could not be reached. The network looks unavailable.",
                    List.of(new Sample("Post \"https://api.github.com/graphql\": dial tcp: "
                            + "connect: connection refused", Provenance.UNMEASURED))),

            // Measured in #6. One stderr carries all three markers, which is why they are one
            // row: `gh` prints the status, the reason and the instruction together.
            new Branch("not authenticated",
                    List.of("http 401", "bad credentials", "gh auth login"),
                    Remedy.ASK_OPERATOR,
                    "The GitHub CLI is not authenticated, or its token is no longer valid. "
                            + "Someone with access to this Server has to run `gh auth login`.",
                    List.of(new Sample("HTTP 401: Bad credentials "
                            + "(https://api.github.com/graphql)\n"
                            + "Try authenticating with:  gh auth login", Provenance.MEASURED))),

            // Authenticated, and not allowed. Deliberately not folded into the row above:
            // `gh auth login` is the wrong instruction for a login that is already valid, and
            // giving it here is how an operator is sent to re-authenticate a token whose
            // authentication was never the problem. Measured in #33 on a fine-grained PAT with
            // Issues: Read-only -- exit 1, from `addComment` after the id lookup had already
            // succeeded, on gh 2.91.0.
            //
            // Matched on the family, not on that one sentence. GitHub words this refusal by
            // naming whatever it refused, and `by integration` is the same wall hit by a
            // GitHub App installation token -- which is what `gh` resolves inside GitHub
            // Actions, a deployment this Server can actually meet. Only the PAT wording is
            // measured; the rest of the family is matched because ADR-0002 classifies by the
            // action available and every member leaves exactly one. The sentence says nothing
            // PAT-specific for that same reason.
            new Branch("not permitted",
                    List.of("resource not accessible by"),
                    Remedy.ASK_OPERATOR,
                    "The login the GitHub CLI resolves is authenticated but lacks "
                            + "permission for this operation. Someone with access to this "
                            + "Server has to give that login the permission, or point "
                            + "`gh` at one that has it — logging in again does not "
                            + "change what a login is allowed to do.",
                    List.of(new Sample("gh: Resource not accessible by personal access token",
                                    Provenance.MEASURED),
                            new Sample("gh: Resource not accessible by integration",
                                    Provenance.UNMEASURED))),

            // Captured verbatim from `gh issue view 9999`. Reachable only once a Tool takes a
            // number, which get_issue was the first to do; before it this fell to UNKNOWN.
            new Branch("no such issue (porcelain)",
                    List.of("could not resolve to an issue or pull request"),
                    Remedy.FIX_REQUEST,
                    "That repository has no issue with that number. Check `number` — note "
                            + "that `gh` says \"issue or pull request\" because GitHub "
                            + "numbers both from one sequence, so this also means there is "
                            + "no pull request with it either.",
                    List.of(new Sample("GraphQL: Could not resolve to an issue or pull request "
                            + "with the number of 9999. (repository.issue)",
                            Provenance.MEASURED))),

            // The same condition, worded differently because it arrives from a different
            // route. `gh api graphql` says "an Issue with the number of", singular and without
            // the "or pull request" clause the porcelain commands use, so it misses the row
            // above and would otherwise land on UNKNOWN. Captured verbatim; see ADR-0005.
            //
            // One sentence covers a number that does not exist and a number that is a pull
            // request, because GraphQL reports both with these same words and separating them
            // would cost a second call on the failure path. ADR-0002 classifies by the action
            // available rather than by the cause, and the action here is identical: change
            // `number`.
            //
            // DO NOT tidy the pull-request half of that sentence away as read-specific
            // wording. Since ADR-0007 this row is also the whole pull-request guard on the
            // write route: `add_issue_comment` looks an issue up with
            // `repository.issue(number:)`, which cannot resolve a pull request's id, and this
            // is where that refusal is turned into something a Client can act on. Both
            // `gh issue comment` and the REST endpoint were measured writing a comment into a
            // pull request; this row is what stands between a Client's typo and that side
            // effect.
            new Branch("no such issue (graphql)",
                    List.of("could not resolve to an issue with the number of"),
                    Remedy.FIX_REQUEST,
                    "That repository has no issue with that number. It may not exist at "
                            + "all, or it may be a pull request — GitHub numbers both "
                            + "from one sequence, and this Server's issue Tools take issues "
                            + "only.",
                    List.of(new Sample("gh: Could not resolve to an Issue with the number of "
                            + "14362.", Provenance.MEASURED))),

            // A cursor that is not a cursor. The wrapper Cursors puts around one catches a
            // cursor belonging to a different issue before the call is made; it cannot catch a
            // correctly-addressed wrapper whose inner half is corrupt, which reaches GitHub
            // and fails here. Captured verbatim. See ADR-0006.
            new Branch("bad cursor",
                    List.of("does not appear to be a valid cursor"),
                    Remedy.FIX_REQUEST,
                    "That `cursor` is not one GitHub recognises. Pass back the "
                            + "`nextCursor` from a previous response unchanged, or omit it "
                            + "to start from the newest comments.",
                    List.of(new Sample("gh: `not-a-cursor` does not appear to be a valid "
                            + "cursor.", Provenance.MEASURED))),

            // Measured in #6. Reads after the two issue rows on purpose: it is the more
            // general failure of the three, and a later reader scanning this table should meet
            // them in that order.
            new Branch("no such repository",
                    List.of("could not resolve to a repository"),
                    Remedy.FIX_REQUEST,
                    "No such repository. Check `owner` and `repo` — note that a private "
                            + "repository this token cannot see looks the same as one that "
                            + "does not exist.",
                    List.of(new Sample("GraphQL: Could not resolve to a Repository with the "
                            + "name 'a/b'. (repository)", Provenance.MEASURED))),

            // Measured in #6 with double quotes. `gh` renders the same complaint with single
            // quotes elsewhere, so both are markers and the second sample exercises the one
            // that has not been seen.
            new Branch("malformed repository name",
                    List.of("owner/repo\" format", "owner/repo' format"),
                    Remedy.FIX_REQUEST,
                    "`owner` and `repo` did not compose a usable repository name. Neither "
                            + "may be empty or contain a slash.",
                    List.of(new Sample("expected the \"[HOST/]OWNER/REPO\" format, got "
                                    + "\"notavalidthing\"", Provenance.MEASURED),
                            new Sample("expected the '[HOST/]OWNER/REPO' format, got "
                                    + "'notavalidthing'", Provenance.UNMEASURED))),

            // Measured in #6. The nearest thing GitHub has to a repository-level gate, and
            // ADR-0009 records why it is not one: it takes the four read Tools down with the
            // write.
            new Branch("issues disabled",
                    List.of("disabled issues"),
                    Remedy.FIX_REQUEST,
                    "That repository has issues turned off, so it has none to list.",
                    List.of(new Sample("the 'torvalds/linux' repository has disabled issues",
                            Provenance.MEASURED))));

    /** The rows, for {@link GhCli} to classify with and for its tests to walk. */
    static List<Branch> branches() {
        return BRANCHES;
    }

    /** What the caller should do about {@code stderr}. */
    static ToolFailure classify(String stderr) {
        String lowered = stderr.toLowerCase(Locale.ROOT);
        for (Branch branch : BRANCHES) {
            if (branch.matches(lowered)) {
                return branch.toFailure(stderr);
            }
        }
        return new ToolFailure(Remedy.UNKNOWN,
                "The GitHub CLI failed in a way this Server does not recognise.", stderr, null);
    }
}

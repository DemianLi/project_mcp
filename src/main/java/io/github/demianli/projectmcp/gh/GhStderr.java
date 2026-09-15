package io.github.demianli.projectmcp.gh;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads {@code gh}'s stderr and decides what the caller should do about it.
 *
 * <p>Best-effort by construction: these are substrings {@code gh} chooses, not an API.
 * Anything unmatched becomes {@link Remedy#UNKNOWN}. Each row carries the provenance of
 * its sample strings — whether they came from real {@code gh} or were written here.
 *
 * <p>This class sees only a non-zero exit with stderr. An absent binary, a timeout, and an
 * unreadable pipe never reach it. The write route's {@link Remedy#CHECK_BEFORE_RETRY} is
 * {@link GhCli}'s concern, not this class's.
 */
final class GhStderr {

    private GhStderr() {
    }

    /** Where a sample's wording came from. Evidence, not proof: nothing here can verify it. */
    enum Provenance {

        /** This exact string is real output from {@code gh}. */
        MEASURED,

        /**
         * Written here rather than taken from {@code gh}. The marker it exercises is a best
         * guess at {@code gh}'s wording, so a real failure may not match it.
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
     * The rows, in the order they are tried. First match wins. No marker contains another,
     * which {@code GhStderrTest} asserts. The order goes from specific to general.
     */
    private static final List<Branch> BRANCHES = List.of(

            new Branch("rate limit",
                    List.of("rate limit"),
                    Remedy.RETRY,
                    "GitHub is rate limiting this token.",
                    RETRY_AFTER,
                    List.of(new Sample("API rate limit exceeded. Please retry after 60 seconds.",
                                    Provenance.UNMEASURED),
                            new Sample("You have exceeded a secondary rate limit",
                                    Provenance.UNMEASURED))),

            new Branch("network",
                    List.of("connection refused", "dial tcp"),
                    Remedy.RETRY,
                    "GitHub could not be reached. The network looks unavailable.",
                    List.of(new Sample("Post \"https://api.github.com/graphql\": dial tcp: "
                            + "connect: connection refused", Provenance.UNMEASURED))),

            new Branch("not authenticated",
                    List.of("http 401", "bad credentials", "gh auth login"),
                    Remedy.ASK_OPERATOR,
                    "The GitHub CLI is not authenticated, or its token is no longer valid. "
                            + "Someone with access to this Server has to run `gh auth login`.",
                    List.of(new Sample("HTTP 401: Bad credentials "
                            + "(https://api.github.com/graphql)\n"
                            + "Try authenticating with:  gh auth login", Provenance.MEASURED))),

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

            new Branch("no such issue (graphql)",
                    List.of("could not resolve to an issue with the number of"),
                    Remedy.FIX_REQUEST,
                    "That repository has no issue with that number. It may not exist at "
                            + "all, or it may be a pull request — GitHub numbers both "
                            + "from one sequence, and this Server's issue Tools take issues "
                            + "only.",
                    List.of(new Sample("gh: Could not resolve to an Issue with the number of "
                            + "14362.", Provenance.MEASURED))),

            new Branch("bad cursor",
                    List.of("does not appear to be a valid cursor"),
                    Remedy.FIX_REQUEST,
                    "That `cursor` is not one GitHub recognises. Pass back the "
                            + "`nextCursor` from a previous response unchanged, or omit it "
                            + "to start from the newest comments.",
                    List.of(new Sample("gh: `not-a-cursor` does not appear to be a valid "
                            + "cursor.", Provenance.MEASURED))),

            new Branch("no such repository",
                    List.of("could not resolve to a repository"),
                    Remedy.FIX_REQUEST,
                    "No such repository. Check `owner` and `repo` — note that a private "
                            + "repository this token cannot see looks the same as one that "
                            + "does not exist.",
                    List.of(new Sample("GraphQL: Could not resolve to a Repository with the "
                            + "name 'a/b'. (repository)", Provenance.MEASURED))),

            new Branch("malformed repository name",
                    List.of("owner/repo\" format", "owner/repo' format"),
                    Remedy.FIX_REQUEST,
                    "`owner` and `repo` did not compose a usable repository name. Neither "
                            + "may be empty or contain a slash.",
                    List.of(new Sample("expected the \"[HOST/]OWNER/REPO\" format, got "
                                    + "\"notavalidthing\"", Provenance.MEASURED),
                            new Sample("expected the '[HOST/]OWNER/REPO' format, got "
                                    + "'notavalidthing'", Provenance.UNMEASURED))),

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

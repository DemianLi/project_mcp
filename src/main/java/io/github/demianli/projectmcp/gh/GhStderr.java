package io.github.demianli.projectmcp.gh;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 讀取 {@code gh} 的 stderr，決定呼叫者接下來該怎麼做。
 *
 * <p>本質上是盡力而為：比對的是 {@code gh} 自行選用的子字串，不是 API。沒有比對到的一律
 * 成為 {@link Remedy#UNKNOWN}。每一列都標註其樣本字串的來源：取自真正的 {@code gh}，
 * 或是在此撰寫。
 *
 * <p>本 class 只處理「非零結束且有 stderr」的情況；執行檔不存在、逾時與 pipe 無法讀取
 * 都不會到這裡。寫入路徑的 {@link Remedy#CHECK_BEFORE_RETRY} 由 {@link GhCli} 負責，
 * 不在此處。
 */
final class GhStderr {

    private GhStderr() {
    }

    /** 樣本措辭的來源。只是佐證而非證明：這裡無法驗證它。 */
    enum Provenance {

        /** 這個字串是 {@code gh} 的真實輸出。 */
        MEASURED,

        /**
         * 在此撰寫，並非取自 {@code gh}。它所驗證的 marker 是對 {@code gh} 措辭的最佳推測，
         * 真正的失敗不一定比對得到。
         */
        UNMEASURED
    }

    /** 這一列要捕捉的一段 stderr。 */
    record Sample(String stderr, Provenance provenance) {
    }

    /**
     * 一種分類：選中它的 markers，以及隨後告訴呼叫者的內容。
     *
     * @param statedWait 擷取 stderr 指明的等待時間；措辭沒有指明時為 {@code null}，除了
     *     rate limit 那一列都是如此。命名避開 {@code Object.wait}，因為 record component
     *     不能遮蔽它。
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

        /** 這一列對已比對成功的 {@code stderr} 回報的失敗。 */
        ToolFailure toFailure(String stderr) {
            if (statedWait == null) {
                return new ToolFailure(remedy, sentence, stderr, null);
            }
            // 對原始字串而非小寫副本比對：pattern 本身不分大小寫，而要讀出的是其中的數字。
            Matcher m = statedWait.matcher(stderr);
            Integer seconds = m.find() ? Integer.valueOf(m.group(1)) : null;
            return new ToolFailure(remedy,
                    sentence + (seconds == null
                            ? "" : " Wait " + seconds + " seconds before retrying."),
                    stderr, seconds);
        }
    }

    /** {@code gh} 有時會指明等待時間；措辭未經驗證，因此只是盡力而為。 */
    private static final Pattern RETRY_AFTER =
            Pattern.compile("retry after (\\d+)", Pattern.CASE_INSENSITIVE);

    /**
     * 所有列，依嘗試順序排列，先比對到者勝出。沒有任何 marker 包含另一個，由
     * {@code GhStderrTest} 斷言。順序由具體到一般。
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

    /** 所有列，供 {@link GhCli} 分類使用，也供其測試走訪。 */
    static List<Branch> branches() {
        return BRANCHES;
    }

    /** 呼叫者對這段 {@code stderr} 該怎麼做。 */
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

package io.github.demianli.projectmcp.tool;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 以擷取的 {@code gh} 輸出，把 {@code IssueMapper} 當純函式測試。
 *
 * <p>fixture 保有真實的欄位形狀，包括空的 assignee 陣列，以及沒有 label 或 assignee
 * 的 issue。
 */
class IssueMapperTest {

    private static final String THREE_ISSUES = fixture("issue-list-with-empty-arrays.json");

    private final IssueMapper mapper = new IssueMapper();

    /** fixture 集中放在 {@code src/test/resources/gh}，全是真實擷取。 */
    private static String fixture(String name) {
        try {
            return Files.readString(Path.of("src/test/resources/gh", name));
        } catch (java.io.IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void keepsTheSevenFieldsAndFlattensTheObjectArrays() {
        IssueSummary first = mapper.toEnvelope(THREE_ISSUES, 10).items().getFirst();

        assertThat(first.number()).isEqualTo(6);
        assertThat(first.title()).isEqualTo("Implement list_issues and verify it in the Inspector");
        assertThat(first.state()).isEqualTo("OPEN");
        assertThat(first.url()).isEqualTo("https://github.com/DemianLi/project_mcp/issues/6");
        assertThat(first.updatedAt()).isEqualTo("2026-09-04T15:12:34Z");

        // gh 隨每個 label 送來的 node id、description 與 colour 都被捨棄；
        // 留下的正是 labels 參數所用的字串。
        assertThat(first.labels()).containsExactly("wayfinder:task");
        assertThat(first.assignees()).containsExactly("DemianLi");
    }

    @Test
    void emptyObjectArraysBecomeEmptyStringArrays() {
        IssueSummary unassigned = mapper.toEnvelope(THREE_ISSUES, 10).items().get(2);

        assertThat(unassigned.labels()).isEmpty();
        assertThat(unassigned.assignees()).isEmpty();
    }

    @Test
    void theSpareEntryProvesMoreExistAndIsDropped() {
        // limit 為 2 時取回三筆：gh 被要求取 limit + 1 筆，而且取滿了。
        ListResult<IssueSummary> envelope = mapper.toEnvelope(THREE_ISSUES, 2);

        assertThat(envelope.truncated()).isTrue();
        assertThat(envelope.count()).isEqualTo(2);
        assertThat(envelope.items()).extracting(IssueSummary::number).containsExactly(6, 5);
    }

    @Test
    void fallingShortOfTheSpareMeansThisIsAllOfThem() {
        ListResult<IssueSummary> envelope = mapper.toEnvelope(THREE_ISSUES, 3);

        assertThat(envelope.truncated()).isFalse();
        assertThat(envelope.count()).isEqualTo(3);
    }

    @Test
    void aRepositoryWithNoMatchingIssuesIsAnEmptyEnvelopeNotAFailure() {
        ListResult<IssueSummary> envelope = mapper.toEnvelope("[]", 30);

        assertThat(envelope.items()).isEmpty();
        assertThat(envelope.count()).isZero();
        assertThat(envelope.truncated()).isFalse();
    }

    @Test
    void theEnvelopeIsImmutable() {
        List<IssueSummary> items = mapper.toEnvelope(THREE_ISSUES, 10).items();

        assertThat(items).isUnmodifiable();
    }
}

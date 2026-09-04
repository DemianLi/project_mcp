package io.github.demianli.projectmcp.tool;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the half of {@code list_issues} that is a pure function of a string.
 *
 * <p>No Spring context, no subprocess, no GitHub account: the payload below was captured
 * verbatim from {@code gh issue list --repo DemianLi/project_mcp --state all --limit 3
 * --json number,title,state,labels,assignees,url,updatedAt}, so it carries the real shape
 * of the object arrays that get flattened — including the empty {@code assignees[].name}.
 *
 * <p>This is not a testing strategy for the Server as a whole; how to test a shell-out
 * Server without hitting the real GitHub API is still an open item on the map (issue #1).
 */
class IssueMapperTest {

    private static final String THREE_ISSUES = """
            [{"assignees":[{"id":"U_kgDOCLEPUw","login":"DemianLi","name":"","databaseId":145821523}],\
            "labels":[{"id":"LA_kwDOUOMf088AAAACzwxQIA","name":"wayfinder:task",\
            "description":"Wayfinder ticket: manual work unblocking a decision","color":"006b75"}],\
            "number":6,"state":"OPEN","title":"Implement list_issues and verify it in the Inspector",\
            "updatedAt":"2026-09-04T15:12:34Z","url":"https://github.com/DemianLi/project_mcp/issues/6"},\
            {"assignees":[{"id":"U_kgDOCLEPUw","login":"DemianLi","name":"","databaseId":145821523}],\
            "labels":[{"id":"LA_kwDOUOMf088AAAACzwxPqg","name":"wayfinder:grilling",\
            "description":"Wayfinder ticket: HITL conversation","color":"d93f0b"}],\
            "number":5,"state":"CLOSED","title":"Decide the parameters and return shape of list_issues",\
            "updatedAt":"2026-09-04T14:58:56Z","url":"https://github.com/DemianLi/project_mcp/issues/5"},\
            {"assignees":[],\
            "labels":[],\
            "number":4,"state":"CLOSED","title":"Get a zero-Tool Stdio server to handshake in the Inspector",\
            "updatedAt":"2026-09-04T14:24:23Z","url":"https://github.com/DemianLi/project_mcp/issues/4"}]""";

    private final IssueMapper mapper = new IssueMapper();

    @Test
    void keepsTheSevenFieldsAndFlattensTheObjectArrays() {
        IssueSummary first = mapper.toEnvelope(THREE_ISSUES, 10).items().getFirst();

        assertThat(first.number()).isEqualTo(6);
        assertThat(first.title()).isEqualTo("Implement list_issues and verify it in the Inspector");
        assertThat(first.state()).isEqualTo("OPEN");
        assertThat(first.url()).isEqualTo("https://github.com/DemianLi/project_mcp/issues/6");
        assertThat(first.updatedAt()).isEqualTo("2026-09-04T15:12:34Z");

        // The node id, description and colour that gh sends alongside each label are gone;
        // what survives is the same string the labels parameter takes.
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
        // Three fetched against a limit of two: gh was asked for limit + 1 and filled it.
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

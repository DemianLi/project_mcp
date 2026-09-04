package io.github.demianli.projectmcp.gh.proto;

// PROTOTYPE. Seam B exercised. Each test says what it PROVED and what it FAKED.

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class SeamBProtoTest {

    private final ProcessRunner runner = mock(ProcessRunner.class);
    private final GhCliB gh = new GhCliB(runner);

    @Test
    void success() throws Exception {
        when(runner.run(anyList())).thenReturn(new ProcessRunner.Raw(0, "[{\"number\":1}]", ""));
        assertThat(gh.run(List.of("issue", "list"))).contains("\"number\":1");
    }

    @Test
    void nonZeroExitCarriesStderr() throws Exception {
        when(runner.run(anyList())).thenReturn(new ProcessRunner.Raw(
                1, "", "GraphQL: Could not resolve to a Repository"));
        assertThatThrownBy(() -> gh.run(List.of("issue", "list")))
                .hasMessageContaining("exit 1")
                .hasMessageContaining("Could not resolve to a Repository");
    }

    @Test
    void missingBinaryIsAnIOException() throws Exception {
        // Note what this asserts: that GhCliB reacts correctly to an IOException we
        // INVENTED. That ProcessBuilder.start() actually throws one for an absent binary
        // is assumed here, not shown -- the real spawn is the thing we replaced.
        when(runner.run(anyList())).thenThrow(new IOException("No such file or directory"));
        assertThatThrownBy(() -> gh.run(List.of("issue", "list")))
                .hasMessageContaining("could not start");
    }

    // No timeout test, and no pipe-buffer test. Not an omission -- there is nothing here
    // to test. The timeout, the concurrent draining and the exit-code read all moved into
    // the ProcessRunner implementation, which this seam replaces wholesale. Testing them
    // would mean testing the real ProcessRunner, i.e. seam A under another name.
}

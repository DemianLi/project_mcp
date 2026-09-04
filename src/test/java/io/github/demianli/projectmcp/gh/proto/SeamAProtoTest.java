package io.github.demianli.projectmcp.gh.proto;

// PROTOTYPE. Seam A exercised. Each test says what it PROVED and what it FAKED.

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SeamAProtoTest {

    @TempDir Path tmp;

    private String fakeGh(String script) throws Exception {
        Path p = tmp.resolve("gh");
        Files.writeString(p, "#!/bin/sh\n" + script + "\n");
        Files.setPosixFilePermissions(p, Set.copyOf(
                java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x")));
        return p.toString();
    }

    @Test
    void success() throws Exception {
        var gh = new GhCliA(fakeGh("echo '[{\"number\":1}]'"), 5);
        assertThat(gh.run(List.of("issue", "list"))).contains("\"number\":1");
    }

    @Test
    void nonZeroExitCarriesStderr() throws Exception {
        var gh = new GhCliA(fakeGh(
                "echo 'GraphQL: Could not resolve to a Repository' >&2; exit 1"), 5);
        assertThatThrownBy(() -> gh.run(List.of("issue", "list")))
                .hasMessageContaining("exit 1")
                .hasMessageContaining("Could not resolve to a Repository");
    }

    @Test
    void missingBinaryIsAnIOException() {
        // The asymmetry the ticket names. Nothing is stubbed: the file is genuinely absent,
        // so this is the real IOException from the real ProcessBuilder.start().
        var gh = new GhCliA(tmp.resolve("no-such-gh").toString(), 5);
        assertThatThrownBy(() -> gh.run(List.of("issue", "list")))
                .hasMessageContaining("could not start")
                .hasRootCauseInstanceOf(java.io.IOException.class);
    }

    @Test
    void timeoutIsReal() throws Exception {
        var gh = new GhCliA(fakeGh("sleep 5"), 1);
        long start = System.nanoTime();
        assertThatThrownBy(() -> gh.run(List.of("issue", "list")))
                .hasMessageContaining("timed out after 1s");
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertThat(ms).isBetween(900L, 3000L); // it really waited
    }

    @Test
    void stderrLargerThanThePipeBufferDoesNotDeadlock() throws Exception {
        // The bug GhCli's concurrent draining exists to prevent. Only a real process can
        // fill a real pipe buffer, so only this seam can prove the fix works.
        var gh = new GhCliA(fakeGh(
                "i=0; while [ $i -lt 4000 ]; do echo 'noise noise noise noise noise' >&2; "
                + "i=$((i+1)); done; echo '[]'"), 10);
        assertThat(gh.run(List.of("issue", "list"))).isEqualTo("[]\n");
    }
}

package io.github.demianli.projectmcp.gh;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Writes a stand-in for the {@code gh} binary.
 *
 * <p>The executable bit is load-bearing. Without it {@link ProcessBuilder#start()} throws an
 * {@link IOException} indistinguishable from an absent binary — a script written without it
 * would make the absent-binary test pass for the wrong reason and every other test fail in a
 * way that looks exactly like the contract working. For the same reason the absent-binary
 * case points at a path that does not exist, never at a file that merely is not executable.
 */
public final class FakeGh {

    private FakeGh() {
    }

    /** A {@code gh} that runs {@code body}. Returns the path to hand to {@link GhCli}. */
    public static String writing(Path dir, String body) throws IOException {
        Path script = dir.resolve("gh");
        Files.writeString(script, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        return script.toString();
    }

    /** A {@code gh} that writes {@code stderr} and exits non-zero. */
    public static String failing(Path dir, String stderr) throws IOException {
        return writing(dir, "cat >&2 <<'STDERR'\n" + stderr + "\nSTDERR\nexit 1");
    }
}

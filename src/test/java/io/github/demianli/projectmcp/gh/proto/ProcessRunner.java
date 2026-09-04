package io.github.demianli.projectmcp.gh.proto;

// PROTOTYPE. Seam B: the spawn itself is behind an interface.

import java.io.IOException;
import java.util.List;

public interface ProcessRunner {

    record Raw(int exitCode, String stdout, String stderr) {}

    /** Everything GhCli does with pipes, timeouts and exit codes lives behind this. */
    Raw run(List<String> command) throws IOException;
}

package io.github.demianli.projectmcp.gh.proto;

// PROTOTYPE. Seam B: GhCli keeps only the classification; the spawn is delegated.

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class GhCliB {

    private final ProcessRunner runner;

    public GhCliB(ProcessRunner runner) {
        this.runner = runner;
    }

    public String run(List<String> args) {
        List<String> command = new ArrayList<>(args.size() + 1);
        command.add("gh");
        command.addAll(args);

        ProcessRunner.Raw raw;
        try {
            raw = runner.run(command);
        } catch (IOException e) {
            throw new RuntimeException("could not start: gh", e);
        }
        if (raw.exitCode() != 0) {
            throw new RuntimeException("exit " + raw.exitCode() + ": " + raw.stderr().strip());
        }
        return raw.stdout();
    }
}

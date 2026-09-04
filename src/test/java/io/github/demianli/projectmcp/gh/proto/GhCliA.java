package io.github.demianli.projectmcp.gh.proto;

// PROTOTYPE. Seam A: the executable name and the timeout are injected.
// Body is otherwise a verbatim copy of GhCli.

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class GhCliA {

    private final String executable;
    private final long timeoutSeconds;

    public GhCliA(String executable, long timeoutSeconds) {
        this.executable = executable;
        this.timeoutSeconds = timeoutSeconds;
    }

    public String run(List<String> args) {
        List<String> command = new ArrayList<>(args.size() + 1);
        command.add(executable);
        command.addAll(args);

        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException e) {
            throw new RuntimeException("could not start: " + executable, e);
        }

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<byte[]> stdout = executor.submit(() -> process.getInputStream().readAllBytes());
            Future<byte[]> stderr = executor.submit(() -> process.getErrorStream().readAllBytes());

            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new RuntimeException("timed out after " + timeoutSeconds + "s");
            }
            String out = new String(stdout.get(), StandardCharsets.UTF_8);
            String err = new String(stderr.get(), StandardCharsets.UTF_8).strip();
            if (process.exitValue() != 0) {
                throw new RuntimeException("exit " + process.exitValue() + ": " + err);
            }
            return out;
        } catch (InterruptedException | ExecutionException e) {
            process.destroyForcibly();
            throw new RuntimeException(e);
        }
    }
}

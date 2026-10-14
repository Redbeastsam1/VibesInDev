package dev.vibesindev.vibesmusic.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class ProcessRunner {

    private ProcessRunner() {
    }

    public record Result(int exitCode, String stdout, String stderr) {
    }

    public record ActiveProcess(long pid, String command, long startTime) {
    }

    private static final java.util.Map<Long, ActiveProcess> activeProcesses =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static java.util.List<ActiveProcess> getActiveProcesses() {
        return List.copyOf(activeProcesses.values());
    }

    public static Result run(List<String> command, Duration timeout)
            throws IOException, InterruptedException {

        Process process = new ProcessBuilder(command)
                .redirectErrorStream(false)
                .start();

        long pid = process.pid();
        ActiveProcess active = new ActiveProcess(
                pid,
                String.join(" ", command),
                System.currentTimeMillis()
        );
        activeProcesses.put(pid, active);

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        Thread stdoutThread = new Thread(
                () -> copy(process.getInputStream(), stdout),
                "vibesindev-process-stdout"
        );

        Thread stderrThread = new Thread(
                () -> copy(process.getErrorStream(), stderr),
                "vibesindev-process-stderr"
        );

        stdoutThread.start();
        stderrThread.start();

        boolean finished = false;
        try {
            finished = process.waitFor(
                    timeout.toMillis(),
                    TimeUnit.MILLISECONDS
            );
        } catch (InterruptedException e) {
            process.destroyForcibly();
            stdoutThread.join(1000);
            stderrThread.join(1000);
            throw e;
        } finally {
            activeProcesses.remove(process.pid());
        }

        if (!finished) {
            process.destroyForcibly();
            stdoutThread.join(1000);
            stderrThread.join(1000);

            throw new IOException(
                    "Process timed out after "
                            + timeout.toSeconds()
                            + " seconds"
            );
        }

        stdoutThread.join();
        stderrThread.join();

        return new Result(
                process.exitValue(),
                stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8)
        );
    }

    private static void copy(
            InputStream input,
            ByteArrayOutputStream output
    ) {
        try (input) {
            input.transferTo(output);
        } catch (IOException ignored) {
        }
    }

    public static void cancelProcessesContaining(String term) {
        if (term == null || term.isBlank()) return;
        for (ActiveProcess p : activeProcesses.values()) {
            if (p.command().contains(term)) {
                try {
                    ProcessHandle.of(p.pid()).ifPresent(ProcessHandle::destroyForcibly);
                } catch (Exception ignored) {
                }
            }
        }
    }
}

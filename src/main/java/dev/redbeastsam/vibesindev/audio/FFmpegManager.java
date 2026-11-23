package dev.redbeastsam.vibesindev.audio;

import dev.redbeastsam.vibesindev.VibesInDevPlugin;
import dev.redbeastsam.vibesindev.util.ProcessRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class FFmpegManager {
  private final VibesInDevPlugin plugin;
  private final String executable;
  private final Duration timeout;
  private final java.util.concurrent.ExecutorService ffmpegExecutor;

  public FFmpegManager(VibesInDevPlugin plugin) {
    this.plugin = plugin;
    this.executable = plugin.getConfig().getString("tools.ffmpeg", "ffmpeg");
    this.timeout =
        Duration.ofSeconds(plugin.getConfig().getLong("tools.process-timeout-seconds", 300));
    int maxFfmpeg = plugin.getConfig().getInt("limits.max-concurrent-ffmpeg", 2);
    this.ffmpegExecutor = java.util.concurrent.Executors.newFixedThreadPool(maxFfmpeg);
  }

  public void shutdown() {
    ffmpegExecutor.shutdownNow();
  }

  public CompletableFuture<Path> convert(Path input, Path outputDirectory) {
    CompletableFuture<Path> future = new CompletableFuture<>();
    java.util.concurrent.Future<?> task =
        ffmpegExecutor.submit(
            () -> {
              try {
                Files.createDirectories(outputDirectory);

                String fileName = input.getFileName().toString();
                int dot = fileName.lastIndexOf('.');

                String id = dot > 0 ? fileName.substring(0, dot) : fileName;

                Path output = outputDirectory.resolve(id + ".ogg");

                int sampleRate = plugin.getConfig().getInt("audio.sample-rate", 48000);

                int channels = plugin.getConfig().getInt("audio.channels", 2);

                int quality = plugin.getConfig().getInt("audio.vorbis-quality", 5);

                List<String> command =
                    List.of(
                        executable,
                        "-y",
                        "-nostdin",
                        "-hide_banner",
                        "-loglevel",
                        "error",
                        "-i",
                        input.toString(),
                        "-vn",
                        "-ar",
                        Integer.toString(sampleRate),
                        "-ac",
                        Integer.toString(channels),
                        "-c:a",
                        "libvorbis",
                        "-q:a",
                        Integer.toString(quality),
                        output.toString());

                ProcessRunner.Result result = ProcessRunner.run(command, timeout);

                if (result.exitCode() != 0) {
                  plugin.metrics().incrementFfmpegFailures();
                  throw new IllegalStateException(
                      "FFmpeg conversion failed: " + cleanError(result));
                }

                if (!Files.isRegularFile(output)) {
                  throw new IllegalStateException(
                      "FFmpeg completed but output file was not created");
                }

                future.complete(output);
              } catch (IOException | InterruptedException e) {
                Thread.currentThread().interrupt();
                future.completeExceptionally(new RuntimeException("Could not run FFmpeg", e));
              } catch (Throwable t) {
                future.completeExceptionally(t);
              }
            });

    return future.whenComplete(
        (res, ex) -> {
          if (ex instanceof java.util.concurrent.CancellationException || future.isCancelled()) {
            task.cancel(true);
          }
        });
  }

  private static String cleanError(ProcessRunner.Result result) {
    String error = result.stderr();

    if (error == null || error.isBlank()) {
      error = result.stdout();
    }

    return error == null ? "unknown error" : error.strip();
  }
}

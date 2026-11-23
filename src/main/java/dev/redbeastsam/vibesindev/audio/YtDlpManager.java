package dev.redbeastsam.vibesindev.audio;

import dev.redbeastsam.vibesindev.VibesInDevPlugin;
import dev.redbeastsam.vibesindev.model.Track;
import dev.redbeastsam.vibesindev.util.ProcessRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public final class YtDlpManager {
  private final VibesInDevPlugin plugin;
  private final String executable;
  private final Duration timeout;
  private final java.util.concurrent.ExecutorService resolutionExecutor;
  private final java.util.concurrent.ExecutorService downloadExecutor;

  public YtDlpManager(VibesInDevPlugin plugin) {
    this.plugin = plugin;
    this.executable = plugin.getConfig().getString("tools.yt-dlp", "yt-dlp");

    this.timeout =
        Duration.ofSeconds(plugin.getConfig().getLong("tools.process-timeout-seconds", 300));
    int maxDlp = plugin.getConfig().getInt("limits.max-concurrent-downloads", 2);
    this.resolutionExecutor = java.util.concurrent.Executors.newFixedThreadPool(maxDlp);
    this.downloadExecutor = java.util.concurrent.Executors.newFixedThreadPool(maxDlp);
  }

  public void shutdown() {
    resolutionExecutor.shutdownNow();
    downloadExecutor.shutdownNow();
  }

  /** Returns the yt-dlp base args common to all invocations (includes optional cookies). */
  private List<String> baseArgs() {
    List<String> args = new ArrayList<>();
    args.add(executable);
    args.add("--no-update");
    args.add("--ignore-config");
    args.add("--no-warnings");
    args.add("--encoding");
    args.add("utf-8");

    // Cookie support — lets yt-dlp bypass age restrictions and rate limits
    String cookiesFromBrowser = plugin.getConfig().getString("tools.cookies-from-browser", "");
    String cookiesFile = plugin.getConfig().getString("tools.cookies-file", "");

    if (!cookiesFromBrowser.isBlank()) {
      args.add("--cookies-from-browser");
      args.add(cookiesFromBrowser.trim());
    } else if (!cookiesFile.isBlank()) {
      args.add("--cookies");
      args.add(cookiesFile.trim());
    }

    return args;
  }

  @SuppressWarnings("null")
  public CompletableFuture<Track> resolve(String input) {
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            String printFormat = "%(id)s\t%(title)s\t%(uploader)s\t%(duration)s\t%(webpage_url)s";

            List<String> args = baseArgs();
            args.add("--no-playlist");
            args.add("--print");
            args.add(printFormat);
            args.add(input);

            ProcessRunner.Result result = ProcessRunner.run(args, timeout);

            if (result.exitCode() != 0) {
              plugin.metrics().incrementYtDlpFailures();
              throw new IllegalStateException("yt-dlp resolve failed: " + cleanError(result));
            }

            String line =
                result
                    .stdout()
                    .lines()
                    .filter(s -> !s.isBlank())
                    .findFirst()
                    .orElseThrow(
                        () -> new IllegalStateException("yt-dlp returned no track information"));

            String[] parts = line.split("\t", 5);

            if (parts.length < 5) {
              throw new IllegalStateException("Unexpected yt-dlp output: " + line);
            }

            String id = parts[0].trim();
            String title = parts[1].trim();
            String uploader = parts[2].trim();
            long duration = parseDuration(parts[3].trim());
            String webpageUrl = parts[4].trim();

            if (id.isBlank() || id.equalsIgnoreCase("NA") || id.equalsIgnoreCase("none")) {
              throw new IllegalStateException("yt-dlp did not return a track id");
            }

            if (title.isBlank() || title.equalsIgnoreCase("NA") || title.equalsIgnoreCase("none")) {
              title = id;
            }

            if (uploader.isBlank()
                || uploader.equalsIgnoreCase("NA")
                || uploader.equalsIgnoreCase("none")) {
              uploader = "Unknown";
            }

            if (webpageUrl.isBlank()
                || webpageUrl.equalsIgnoreCase("NA")
                || webpageUrl.equalsIgnoreCase("none")) {
              webpageUrl = input;
            }

            checkDuration(duration);

            return new Track(id, title, uploader, duration, webpageUrl);
          } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new RuntimeException("Could not run yt-dlp", e);
          }
        },
        resolutionExecutor);
  }

  public CompletableFuture<List<Track>> resolvePlaylist(String input) {
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            String printFormat = "%(id)s\t%(title)s\t%(duration)s\t%(webpage_url)s";

            ProcessRunner.Result result =
                ProcessRunner.run(
                    List.of(
                        executable,
                        "--ignore-config",
                        "--flat-playlist",
                        "--no-warnings",
                        "--print",
                        printFormat,
                        input),
                    timeout);

            if (result.exitCode() != 0) {
              plugin.metrics().incrementYtDlpFailures();
              throw new IllegalStateException(
                  "yt-dlp playlist resolve failed: " + cleanError(result));
            }

            List<Track> tracks = new ArrayList<>();

            for (String line : result.stdout().split("\\R")) {
              if (line.isBlank()) {
                continue;
              }

              String[] parts = line.split("\t", 4);

              if (parts.length < 4) {
                continue;
              }

              String id = parts[0].trim();
              String title = parts[1].trim();
              String durationText = parts[2].trim();
              String url = parts[3].trim();

              if (id.isBlank() || id.equalsIgnoreCase("NA") || id.equalsIgnoreCase("none")) {
                continue;
              }

              if (title.isBlank()
                  || title.equalsIgnoreCase("NA")
                  || title.equalsIgnoreCase("none")) {
                title = id;
              }

              if (url.isBlank() || url.equalsIgnoreCase("NA") || url.equalsIgnoreCase("none")) {
                url = "https://www.youtube.com/watch?v=" + id;
              }

              long duration = parseDuration(durationText);

              checkDuration(duration);

              tracks.add(new Track(id, title, "Unknown", duration, url));
            }

            if (tracks.isEmpty()) {
              throw new IllegalStateException("No tracks were found in the playlist.");
            }

            return tracks;
          } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new RuntimeException("Could not read playlist with yt-dlp", e);
          }
        },
        resolutionExecutor);
  }

  /**
   * Resolves a YouTube Radio Mix playlist (RD{id}) to a list of candidate tracks.
   *
   * <p>Uses {@code --flat-playlist} so this is fast (~2 seconds) — it only fetches metadata, not
   * audio. Returns tracks 2 through 8 (skips item 1 which is the seed itself). Items already in
   * {@code recentIds} are included in the list so that the caller can decide how many to skip.
   *
   * @param radioUrl full YouTube Radio Mix URL: {@code
   *     https://www.youtube.com/watch?v={id}&list=RD{id}}
   * @param recentIds IDs of recently played tracks (used for ordering, not filtering here)
   * @return future resolving to an ordered list of candidate {@link Track} objects
   */
  public CompletableFuture<List<Track>> resolveRadioMix(
      String radioUrl, java.util.Set<String> recentIds) {
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            String printFormat = "%(id)s\t%(title)s\t%(uploader)s\t%(duration)s\t%(webpage_url)s";

            List<String> args = baseArgs();
            args.add("--flat-playlist");
            args.add("--playlist-items");
            args.add("2-8"); // skip item 1 (the seed track itself)
            args.add("--print");
            args.add(printFormat);
            args.add(radioUrl);

            ProcessRunner.Result result = ProcessRunner.run(args, timeout);

            if (result.exitCode() != 0) {
              throw new IllegalStateException("yt-dlp radio mix failed: " + cleanError(result));
            }

            List<Track> tracks = new ArrayList<>();

            for (String line : result.stdout().split("\\R")) {
              if (line.isBlank()) continue;

              String[] parts = line.split("\t", 5);
              if (parts.length < 4) continue;

              String id = parts[0].trim();
              String title = parts[1].trim();
              String uploader = parts[2].trim();
              String durationStr = parts[3].trim();
              String url = parts.length >= 5 ? parts[4].trim() : "";

              if (id.isBlank() || id.equalsIgnoreCase("NA") || id.equalsIgnoreCase("none")) {
                continue;
              }
              if (title.isBlank() || title.equalsIgnoreCase("NA")) title = id;
              if (uploader.isBlank() || uploader.equalsIgnoreCase("NA")) uploader = "Unknown";
              if (url.isBlank() || url.equalsIgnoreCase("NA")) {
                url = "https://www.youtube.com/watch?v=" + id;
              }

              long duration = parseDuration(durationStr);

              // Do NOT enforce duration check here; the scorer/filter will handle it
              tracks.add(new Track(id, title, uploader, duration, url));
            }

            return tracks;
          } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Could not resolve YouTube radio mix", e);
          }
        },
        resolutionExecutor);
  }

  public CompletableFuture<Track> searchAndResolve(String query) {
    return searchAndResolve(query, -1);
  }

  public CompletableFuture<Track> searchAndResolve(String query, long targetDurationSeconds) {
    if (query == null || query.isBlank()) {
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("Search query cannot be empty."));
    }

    String title = query.trim();
    String artist = null;
    if (title.contains(" - ")) {
      String[] parts = title.split(" - ", 2);
      artist = parts[0].trim();
      title = parts[1].trim();
    }

    return searchAndResolve(title, artist, targetDurationSeconds);
  }

  public CompletableFuture<Track> searchAndResolve(
      String title, String artist, long targetDurationSeconds) {
    if ((title == null || title.isBlank()) && (artist == null || artist.isBlank())) {
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("Search title/artist cannot be empty."));
    }

    return CompletableFuture.supplyAsync(
        () -> {
          try {
            String cleanTitle = (title != null) ? title.trim() : "";
            String cleanArtist = (artist != null) ? artist.trim() : "";

            // Formulate the highest-accuracy search query
            String query;
            if (!cleanArtist.isBlank() && !cleanTitle.isBlank()) {
              String primaryArtist = cleanArtist.split("[,&]|\\bfeat\\b|\\bft\\b")[0].trim();
              query = primaryArtist + " " + cleanTitle + " Audio";
            } else if (!cleanTitle.isBlank()) {
              query = cleanTitle + " Audio";
            } else {
              query = cleanArtist;
            }

            String printFormat = "%(id)s\t%(title)s\t%(uploader)s\t%(duration)s\t%(webpage_url)s";

            List<String> args = baseArgs();
            args.add("--no-playlist");
            args.add("--flat-playlist");
            args.add("--print");
            args.add(printFormat);
            args.add("ytsearch8:" + query);

            ProcessRunner.Result result = ProcessRunner.run(args, timeout);

            if (result.exitCode() != 0) {
              plugin.metrics().incrementYtDlpFailures();
              throw new IllegalStateException("yt-dlp resolve failed: " + cleanError(result));
            }

            Track bestMatch = null;
            int bestScore = Integer.MIN_VALUE;

            for (String line : result.stdout().split("\\R")) {
              if (line.isBlank()) continue;

              String[] parts = line.split("\t", 5);
              if (parts.length < 5) continue;

              String id = parts[0].trim();
              String candTitle = parts[1].trim();
              String uploader = parts[2].trim();
              long duration = parseDuration(parts[3].trim());
              String webpageUrl = parts[4].trim();

              if (id.isBlank() || id.equalsIgnoreCase("NA") || id.equalsIgnoreCase("none")) {
                continue;
              }
              if (candTitle.isBlank()
                  || candTitle.equalsIgnoreCase("NA")
                  || candTitle.equalsIgnoreCase("none")) {
                candTitle = id;
              }
              if (uploader.isBlank()
                  || uploader.equalsIgnoreCase("NA")
                  || uploader.equalsIgnoreCase("none")) {
                uploader = "Unknown";
              }
              if (webpageUrl.isBlank()
                  || webpageUrl.equalsIgnoreCase("NA")
                  || webpageUrl.equalsIgnoreCase("none")) {
                webpageUrl = "https://www.youtube.com/watch?v=" + id;
              }

              int score =
                  scoreCandidate(
                      candTitle,
                      uploader,
                      duration,
                      cleanTitle,
                      cleanArtist,
                      targetDurationSeconds);

              if (score > bestScore) {
                bestScore = score;
                bestMatch = new Track(id, candTitle, uploader, duration, webpageUrl);
              }
            }

            if (bestMatch == null) {
              throw new IllegalStateException("yt-dlp returned no track information for: " + query);
            }

            plugin
                .getLogger()
                .info(
                    "[VibesInDev] Selected best audio match: "
                        + bestMatch.title()
                        + " ("
                        + bestMatch.durationSeconds()
                        + "s) [Score: "
                        + bestScore
                        + "] for "
                        + cleanArtist
                        + " - "
                        + cleanTitle);

            checkDuration(bestMatch.durationSeconds());
            return bestMatch;
          } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Could not run yt-dlp", e);
          }
        },
        resolutionExecutor);
  }

  private int scoreCandidate(
      String title,
      String uploader,
      long duration,
      String targetTitle,
      String targetArtist,
      long targetDuration) {
    int score = 1000;
    String tLower = title.toLowerCase(Locale.ROOT);
    String uLower = uploader.toLowerCase(Locale.ROOT);
    String targetTLower = (targetTitle != null) ? targetTitle.toLowerCase(Locale.ROOT) : "";
    String targetALower = (targetArtist != null) ? targetArtist.toLowerCase(Locale.ROOT) : "";

    // 1. Title keyword matching
    if (!targetTLower.isBlank()) {
      String[] words = targetTLower.split("\\W+");
      List<String> validWords = new ArrayList<>();
      for (String w : words) {
        if (w.length() >= 2) validWords.add(w);
      }
      if (!validWords.isEmpty()) {
        int matched = 0;
        for (String w : validWords) {
          if (tLower.contains(w)) matched++;
        }
        double ratio = (double) matched / validWords.size();
        if (ratio >= 0.99) {
          score += 350;
        } else if (ratio >= 0.5) {
          score += 150;
        } else {
          score -= 600;
        }
      }
    }

    // 2. Primary artist match
    if (!targetALower.isBlank()) {
      String primaryArtist = targetALower.split("[,&]|\\bfeat\\b|\\bft\\b")[0].trim();
      String[] aWords = primaryArtist.split("\\W+");
      boolean artistFound = false;
      for (String w : aWords) {
        if (w.length() >= 2 && (uLower.contains(w) || tLower.contains(w))) {
          artistFound = true;
          break;
        }
      }
      if (artistFound) {
        if (uLower.contains(primaryArtist)) {
          score += 250;
        } else {
          score += 150;
        }
      }
    }

    // 3. YouTube Music Topic channel bonus
    if (uLower.endsWith(" - topic") || uLower.contains("topic")) {
      score += 350;
    }

    // 4. Official Audio / Video bonus
    if (tLower.contains("official audio")
        || tLower.contains("áudio oficial")
        || tLower.contains("audio oficial")
        || tLower.contains("full audio")
        || tLower.contains("official track")) {
      score += 250;
    } else if (tLower.contains("official music video")
        || tLower.contains("official video")
        || tLower.contains("clip officiel")
        || tLower.contains("music video")) {
      score += 180;
    } else if (tLower.contains("audio")
        || tLower.contains("lyrics")
        || tLower.contains("lyric video")
        || tLower.contains("letra")) {
      score += 100;
    }

    // 5. Negative keywords (unless asked for in target title)
    String[][] penalties = {
      {"translation", "700"},
      {"translated", "700"},
      {"terjemahan", "700"},
      {"reaction", "1200"},
      {"slowed", "500"},
      {"reverb", "500"},
      {"8d audio", "500"},
      {"bass boosted", "500"},
      {"cover", "600"},
      {"karaoke", "500"},
      {"instrumental", "500"},
      {"live", "350"},
      {"compilation", "1200"},
      {"full album", "1200"},
      {"mashup", "700"},
      {"edit", "500"},
      {"amv", "500"},
      {"clip", "400"},
      {"scene", "500"},
      {"fight", "600"},
      {"gameplay", "800"},
      {"montage", "600"},
      {"tiktok", "300"}
    };
    for (String[] p : penalties) {
      String word = p[0];
      int penalty = Integer.parseInt(p[1]);
      if (tLower.contains(word) && !targetTLower.contains(word)) {
        score -= penalty;
      }
    }

    // 6. Duration difference scoring
    if (targetDuration > 0 && duration > 0) {
      long diff = Math.abs(duration - targetDuration);
      if (diff <= 3) {
        score += 200;
      } else if (diff <= 8) {
        score += 120;
      } else if (diff <= 15) {
        score += 60;
      } else if (diff <= 30) {
        score += 0;
      } else if (diff <= 60) {
        score -= 250;
      } else {
        score -= Math.min(1500, (int) diff * 12);
      }
    }

    return score;
  }

  public boolean isPlaylistUrl(String input) {
    if (input == null || input.isBlank()) {
      return false;
    }

    String lower = input.toLowerCase();

    return lower.contains("list=") || lower.contains("/sets/") || lower.contains("/playlist/");
  }

  public CompletableFuture<Path> downloadAudio(Track track, Path outputDirectory) {
    CompletableFuture<Path> future = new CompletableFuture<>();
    java.util.concurrent.Future<?> task =
        downloadExecutor.submit(
            () -> {
              try {
                Files.createDirectories(outputDirectory);

                Path template = outputDirectory.resolve(track.id() + ".%(ext)s");

                List<String> dlArgs = baseArgs();
                dlArgs.add("--no-playlist");
                dlArgs.add("-f");
                dlArgs.add("bestaudio/best");
                dlArgs.add("--no-part");
                dlArgs.add("-o");
                dlArgs.add(template.toString());
                dlArgs.add(track.sourceUrl());

                ProcessRunner.Result result = ProcessRunner.run(dlArgs, timeout);

                if (result.exitCode() != 0) {
                  plugin.metrics().incrementYtDlpFailures();
                  throw new IllegalStateException("yt-dlp download failed: " + cleanError(result));
                }

                try (var files = Files.list(outputDirectory)) {
                  Path path =
                      files
                          .filter(Files::isRegularFile)
                          .filter(p -> p.getFileName().toString().startsWith(track.id() + "."))
                          .filter(p -> !p.getFileName().toString().endsWith(".part"))
                          .findFirst()
                          .orElseThrow(
                              () ->
                                  new IllegalStateException(
                                      "yt-dlp completed but no downloaded file was found."));
                  future.complete(path);
                }
              } catch (IOException | InterruptedException e) {
                Thread.currentThread().interrupt();

                future.completeExceptionally(
                    new RuntimeException("Could not download audio with yt-dlp", e));
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

  public CompletableFuture<Track> search(String query) {
    String trimmed = query == null ? "" : query.trim();

    if (trimmed.isBlank()) {
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("Search query cannot be empty."));
    }

    return resolve("ytsearch1:" + trimmed);
  }

  private void checkDuration(long duration) {
    long maxDuration = plugin.getConfig().getLong("limits.max-track-duration-seconds", 3600);

    if (duration <= 0) {
      return;
    }

    if (maxDuration > 0 && duration > maxDuration) {
      throw new IllegalStateException(
          "Track is too long. Maximum duration is " + maxDuration + " seconds.");
    }
  }

  private static long parseDuration(String value) {
    if (value == null
        || value.isBlank()
        || value.equalsIgnoreCase("NA")
        || value.equalsIgnoreCase("none")) {
      return 0;
    }

    try {
      return Math.round(Double.parseDouble(value));
    } catch (NumberFormatException ignored) {
      return 0;
    }
  }

  private static String cleanError(ProcessRunner.Result result) {
    String error = result.stderr();

    if (error == null || error.isBlank()) {
      error = result.stdout();
    }

    if (error == null || error.isBlank()) {
      return "unknown error";
    }

    return error.strip();
  }
}

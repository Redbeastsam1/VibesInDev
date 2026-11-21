package dev.vibesindev.vibesmusic.recommendation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.vibesindev.vibesmusic.VibesInDevPlugin;
import dev.vibesindev.vibesmusic.cache.TrackCache;
import dev.vibesindev.vibesmusic.spotify.SpotifyHttp;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Function;

/**
 * Resolves micro-genre tags for music artists through a tiered resolution ladder:
 *
 * <ol>
 *   <li>In-memory cache & local SQLite {@code artist_genres} table
 *   <li>Spotify Web API artist search / lookup (if available)
 *   <li>Optional Last.fm {@code artist.getTopTags} (if API key configured)
 *   <li>Negative cache with 7-day TTL for unresolvable artists
 * </ol>
 *
 * <p>Never called synchronously inside scoring loops; scoring accesses cached genres via {@link
 * #getCachedGenres(String)}, while candidate warmers run in the background.
 */
public class GenreResolver {
  private static final long NEGATIVE_CACHE_TTL_MS = 7L * 24 * 3600_000L; // 7 days
  private static final long POSITIVE_CACHE_TTL_MS = 30L * 24 * 3600_000L; // 30 days

  public record GenreEntry(List<String> genres, String source, long fetchedAt) {}

  private final VibesInDevPlugin plugin;
  private final Map<String, GenreEntry> memoryCache = new ConcurrentHashMap<>();
  private final Map<String, CompletableFuture<List<String>>> inFlight = new ConcurrentHashMap<>();
  private final HttpClient httpClient;
  private final ExecutorService executor;

  // Optional provider hooks for testing
  private Function<String, CompletableFuture<List<String>>> spotifyLookupOverride;
  private Function<String, CompletableFuture<List<String>>> lastFmLookupOverride;

  public GenreResolver(VibesInDevPlugin plugin) {
    this(
        plugin,
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
        ForkJoinPool.commonPool());
  }

  public GenreResolver(VibesInDevPlugin plugin, HttpClient httpClient, ExecutorService executor) {
    this.plugin = plugin;
    this.httpClient = httpClient != null ? httpClient : HttpClient.newHttpClient();
    this.executor = executor != null ? executor : ForkJoinPool.commonPool();
  }

  public void setSpotifyLookupOverride(Function<String, CompletableFuture<List<String>>> override) {
    this.spotifyLookupOverride = override;
  }

  public void setLastFmLookupOverride(Function<String, CompletableFuture<List<String>>> override) {
    this.lastFmLookupOverride = override;
  }

  /**
   * Synchronous non-blocking read of cached genres for an artist (in-memory or SQLite). Returns an
   * unmodifiable list of genres, or an empty list if not yet cached or negative-cached.
   */
  public List<String> getCachedGenres(String artist) {
    if (artist == null || artist.isBlank()) return Collections.emptyList();
    String norm = ArtistNames.normalize(artist);
    if (norm.isEmpty()) return Collections.emptyList();

    long now = System.currentTimeMillis();

    // 1. Check in-memory cache
    GenreEntry mem = memoryCache.get(norm);
    if (mem != null) {
      if (mem.genres().isEmpty() && (now - mem.fetchedAt()) > NEGATIVE_CACHE_TTL_MS) {
        memoryCache.remove(norm);
      } else if (!mem.genres().isEmpty() && (now - mem.fetchedAt()) > POSITIVE_CACHE_TTL_MS) {
        memoryCache.remove(norm);
      } else {
        return mem.genres();
      }
    }

    // 2. Check local SQLite cache
    if (plugin != null && plugin.cache() != null) {
      TrackCache.CachedArtistGenres dbEntry = plugin.cache().getCachedArtistGenres(norm);
      if (dbEntry != null) {
        if (dbEntry.genres().isEmpty() && (now - dbEntry.fetchedAt()) > NEGATIVE_CACHE_TTL_MS) {
          // Expired negative cache
          return Collections.emptyList();
        }
        memoryCache.put(
            norm, new GenreEntry(dbEntry.genres(), dbEntry.source(), dbEntry.fetchedAt()));
        return dbEntry.genres();
      }
    }

    return Collections.emptyList();
  }

  /**
   * Resolves micro-genre tags for an artist through the tiered resolution ladder asynchronously.
   * Coalesces concurrent calls for the same artist.
   */
  public CompletableFuture<List<String>> genresFor(String artist) {
    if (artist == null || artist.isBlank()) {
      return CompletableFuture.completedFuture(Collections.emptyList());
    }
    String norm = ArtistNames.normalize(artist);
    if (norm.isEmpty()) {
      return CompletableFuture.completedFuture(Collections.emptyList());
    }

    // Fast path: cached & valid
    List<String> cached = getCachedGenres(norm);
    if (!cached.isEmpty()) {
      return CompletableFuture.completedFuture(cached);
    }
    GenreEntry mem = memoryCache.get(norm);
    if (mem != null
        && mem.genres().isEmpty()
        && (System.currentTimeMillis() - mem.fetchedAt()) < NEGATIVE_CACHE_TTL_MS) {
      return CompletableFuture.completedFuture(Collections.emptyList());
    }

    // Deduplicate in-flight requests safely without ConcurrentHashMap recursive update
    CompletableFuture<List<String>> existing = inFlight.get(norm);
    if (existing != null) {
      return existing;
    }
    CompletableFuture<List<String>> future = new CompletableFuture<>();
    CompletableFuture<List<String>> previous = inFlight.putIfAbsent(norm, future);
    if (previous != null) {
      return previous;
    }

    resolveLadderAsync(norm)
        .whenComplete(
            (res, ex) -> {
              inFlight.remove(norm);
              if (ex != null) {
                future.completeExceptionally(ex);
              } else {
                future.complete(res != null ? res : Collections.emptyList());
              }
            });
    return future;
  }

  /**
   * Background warmer that tops up cached genres via GenreResolver for the player's top artists
   * (Task 4.2). Non-blocking and async; ignores artists whose genres are already cached.
   *
   * @param profile player taste profile
   * @param limit maximum number of top artists to check (default 50)
   * @return CompletableFuture completing with count of successfully warmed artists
   */
  public CompletableFuture<Integer> warmTopArtists(PlayerTasteProfile profile, int limit) {
    if (profile == null) return CompletableFuture.completedFuture(0);
    List<String> topArtists = profile.getTopArtists(limit > 0 ? limit : 50);
    if (topArtists.isEmpty()) return CompletableFuture.completedFuture(0);

    List<String> missing = new ArrayList<>();
    for (String artist : topArtists) {
      if (getCachedGenres(artist).isEmpty()) {
        missing.add(artist);
      }
    }
    if (missing.isEmpty()) {
      return CompletableFuture.completedFuture(0);
    }

    return CompletableFuture.supplyAsync(
        () -> {
          int warmed = 0;
          for (String artist : missing) {
            try {
              List<String> genres = genresFor(artist).join();
              if (genres != null && !genres.isEmpty()) {
                warmed++;
              }
            } catch (Exception ignored) {
            }
          }
          if (plugin != null) {
            plugin
                .getLogger()
                .fine(
                    "[VibesInDev] Background genre warmer topped up "
                        + warmed
                        + " / "
                        + missing.size()
                        + " artists.");
          }
          return warmed;
        },
        executor);
  }

  private CompletableFuture<List<String>> resolveLadderAsync(String artistNorm) {
    // Step 1: Spotify Web API
    return lookupSpotify(artistNorm)
        .thenCompose(
            spotifyGenres -> {
              if (spotifyGenres != null && !spotifyGenres.isEmpty()) {
                saveAndCache(artistNorm, spotifyGenres, "SPOTIFY");
                return CompletableFuture.completedFuture(spotifyGenres);
              }

              // Step 2: Last.fm
              return lookupLastFm(artistNorm)
                  .thenApply(
                      lastFmGenres -> {
                        if (lastFmGenres != null && !lastFmGenres.isEmpty()) {
                          saveAndCache(artistNorm, lastFmGenres, "LASTFM");
                          return lastFmGenres;
                        }

                        // Step 3: Negative cache (7 days)
                        saveAndCache(artistNorm, Collections.emptyList(), "NEGATIVE_CACHE");
                        return Collections.<String>emptyList();
                      });
            })
        .exceptionally(
            ex -> {
              if (plugin != null) {
                plugin
                    .getLogger()
                    .fine(
                        "[VibesInDev] Error in genre resolution ladder for "
                            + artistNorm
                            + ": "
                            + ex.getMessage());
              }
              saveAndCache(artistNorm, Collections.emptyList(), "NEGATIVE_CACHE_ERROR");
              return Collections.<String>emptyList();
            });
  }

  private CompletableFuture<List<String>> lookupSpotify(String artistNorm) {
    if (spotifyLookupOverride != null) {
      return spotifyLookupOverride.apply(artistNorm);
    }
    if (plugin == null || plugin.spotify() == null) {
      return CompletableFuture.completedFuture(Collections.emptyList());
    }
    if (!plugin.spotify().isFeatureAvailable(SpotifyHttp.Feature.ARTIST_LOOKUP)) {
      return CompletableFuture.completedFuture(Collections.emptyList());
    }
    return plugin.spotify().searchArtistGenres(artistNorm);
  }

  private CompletableFuture<List<String>> lookupLastFm(String artistNorm) {
    if (lastFmLookupOverride != null) {
      return lastFmLookupOverride.apply(artistNorm);
    }
    String apiKey =
        (plugin != null && plugin.getConfig() != null)
            ? plugin.getConfig().getString("recommendations.external.lastfm-api-key", "")
            : "";
    if (apiKey == null || apiKey.isBlank()) {
      return CompletableFuture.completedFuture(Collections.emptyList());
    }

    return CompletableFuture.supplyAsync(
        () -> {
          try {
            String enc = URLEncoder.encode(artistNorm, StandardCharsets.UTF_8);
            String url =
                "https://ws.audioscrobbler.com/2.0/?method=artist.gettoptags&artist="
                    + enc
                    + "&api_key="
                    + URLEncoder.encode(apiKey, StandardCharsets.UTF_8)
                    + "&format=json&autocorrect=1";
            HttpRequest req =
                HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("User-Agent", "VibesInDev-Minecraft-Plugin/1.0.0")
                    .GET()
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200 && resp.body() != null) {
              JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
              if (root.has("toptags") && root.getAsJsonObject("toptags").has("tag")) {
                JsonElement tagEl = root.getAsJsonObject("toptags").get("tag");
                List<String> tags = new ArrayList<>();
                if (tagEl.isJsonArray()) {
                  JsonArray arr = tagEl.getAsJsonArray();
                  for (int i = 0; i < Math.min(10, arr.size()); i++) {
                    JsonObject obj = arr.get(i).getAsJsonObject();
                    if (obj.has("name")) {
                      tags.add(obj.get("name").getAsString());
                    }
                  }
                }
                return tags;
              }
            }
          } catch (Exception e) {
            if (plugin != null) {
              plugin
                  .getLogger()
                  .fine(
                      "[VibesInDev] Last.fm genre lookup failed for "
                          + artistNorm
                          + ": "
                          + e.getMessage());
            }
          }
          return Collections.emptyList();
        },
        executor);
  }

  public void saveAndCache(String artistNorm, List<String> genres, String source) {
    long now = System.currentTimeMillis();
    List<String> unmodifiable = Collections.unmodifiableList(new ArrayList<>(genres));
    memoryCache.put(artistNorm, new GenreEntry(unmodifiable, source, now));
    if (plugin != null && plugin.cache() != null) {
      plugin.cache().saveArtistGenres(artistNorm, unmodifiable, source);
    }
  }

  /**
   * Warms micro-genres for candidates asynchronously in the background. Prevents synchronous cache
   * misses during subsequent scoring passes.
   */
  public void warmCandidateGenres(List<String> artists) {
    if (artists == null || artists.isEmpty()) return;
    Set<String> uniqueNorms = ConcurrentHashMap.newKeySet();
    for (String a : artists) {
      if (a == null || a.isBlank()) continue;
      String norm = ArtistNames.normalize(a);
      if (!norm.isEmpty() && !memoryCache.containsKey(norm)) {
        uniqueNorms.add(norm);
      }
    }
    if (uniqueNorms.isEmpty()) return;

    executor.execute(
        () -> {
          for (String norm : uniqueNorms) {
            try {
              genresFor(norm).join();
            } catch (Exception ignored) {
            }
          }
        });
  }

  public void clearMemoryCache() {
    memoryCache.clear();
    inFlight.clear();
  }
}

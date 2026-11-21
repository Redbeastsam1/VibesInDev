package dev.vibesindev.vibesmusic.recommendation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.vibesindev.vibesmusic.VibesInDevPlugin;
import dev.vibesindev.vibesmusic.cache.TrackCache;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Service that enriches artist lineage from external data sources (MusicBrainz, Last.fm, Spotify)
 * and persists discovered relationships into SQLite (artist_relations table). All external
 * integrations are optional and off by default.
 */
public class ArtistLineageEnricher {
  private final VibesInDevPlugin plugin;
  private final HttpClient httpClient;
  private final ExecutorService executor;
  private static final String USER_AGENT = "VibesInDev/1.0.0 ( https://github.com/VibesInDev )";
  private final AtomicLong lastMusicBrainzRequest = new AtomicLong(0);
  private static final long MUSICBRAINZ_RATE_LIMIT_MS = 1000L;

  // Optional test overrides
  private Function<String, CompletableFuture<List<TrackCache.ArtistRelation>>> musicBrainzOverride;
  private Function<String, CompletableFuture<List<TrackCache.ArtistRelation>>> lastFmOverride;

  public ArtistLineageEnricher(VibesInDevPlugin plugin) {
    this(
        plugin,
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
        ForkJoinPool.commonPool());
  }

  public ArtistLineageEnricher(
      VibesInDevPlugin plugin, HttpClient httpClient, ExecutorService executor) {
    this.plugin = plugin;
    this.httpClient = httpClient != null ? httpClient : HttpClient.newHttpClient();
    this.executor = executor != null ? executor : ForkJoinPool.commonPool();
  }

  public void setMusicBrainzOverride(
      Function<String, CompletableFuture<List<TrackCache.ArtistRelation>>> override) {
    this.musicBrainzOverride = override;
  }

  public void setLastFmOverride(
      Function<String, CompletableFuture<List<TrackCache.ArtistRelation>>> override) {
    this.lastFmOverride = override;
  }

  /**
   * Enriches artist relationships from local SQLite cache or external sources. All calls are
   * non-blocking and return a CompletableFuture.
   */
  public CompletableFuture<List<TrackCache.ArtistRelation>> enrichArtist(String artistName) {
    if (artistName == null || artistName.isBlank()) {
      return CompletableFuture.completedFuture(Collections.emptyList());
    }
    String norm = ArtistNames.normalize(artistName);
    if (norm.isEmpty()) {
      return CompletableFuture.completedFuture(Collections.emptyList());
    }

    // 1. Check SQLite cache first
    if (plugin != null && plugin.cache() != null) {
      Map<String, Double> cached = plugin.cache().getArtistRelations(norm);
      if (!cached.isEmpty()) {
        List<TrackCache.ArtistRelation> res = new ArrayList<>();
        for (Map.Entry<String, Double> e : cached.entrySet()) {
          res.add(
              new TrackCache.ArtistRelation(
                  norm, e.getKey(), "cached", e.getValue(), "cache", System.currentTimeMillis()));
        }
        return CompletableFuture.completedFuture(res);
      }
    }

    // 2. Query enabled external enrichers
    List<CompletableFuture<List<TrackCache.ArtistRelation>>> futures = new ArrayList<>();

    if (isMusicBrainzEnabled() || musicBrainzOverride != null) {
      futures.add(fetchMusicBrainzRelations(norm, artistName));
    }

    String lastFmKey = getLastFmApiKey();
    if ((lastFmKey != null && !lastFmKey.isBlank()) || lastFmOverride != null) {
      futures.add(fetchLastFmRelations(norm, artistName, lastFmKey));
    }

    if (futures.isEmpty()) {
      return CompletableFuture.completedFuture(Collections.emptyList());
    }

    return CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]))
        .thenApply(
            v -> {
              List<TrackCache.ArtistRelation> aggregated = new ArrayList<>();
              Map<String, Double> seen = new HashMap<>();

              for (CompletableFuture<List<TrackCache.ArtistRelation>> fut : futures) {
                try {
                  List<TrackCache.ArtistRelation> rels = fut.join();
                  if (rels != null) {
                    for (TrackCache.ArtistRelation r : rels) {
                      if (r != null && !r.bNorm().equals(norm)) {
                        seen.merge(r.bNorm(), r.weight(), Math::max);
                      }
                    }
                  }
                } catch (Exception ignored) {
                }
              }

              long now = System.currentTimeMillis();
              for (Map.Entry<String, Double> e : seen.entrySet()) {
                aggregated.add(
                    new TrackCache.ArtistRelation(
                        norm, e.getKey(), "enricher", e.getValue(), "external", now));
                aggregated.add(
                    new TrackCache.ArtistRelation(
                        e.getKey(), norm, "enricher", e.getValue(), "external", now));
              }

              // Save to SQLite & update global in-memory lineage
              if (!aggregated.isEmpty()) {
                if (plugin != null && plugin.cache() != null) {
                  plugin.cache().saveArtistRelations(aggregated);
                }
                PlayerTasteProfile.loadGlobalLineage(aggregated);
              }

              return aggregated;
            });
  }

  private boolean isMusicBrainzEnabled() {
    return plugin != null
        && plugin.getConfig().getBoolean("recommendations.external.musicbrainz-enabled", false);
  }

  private String getLastFmApiKey() {
    return plugin != null
        ? plugin.getConfig().getString("recommendations.external.lastfm-api-key", "")
        : "";
  }

  /** MusicBrainz enricher with 1 req/sec polite rate limit. */
  public CompletableFuture<List<TrackCache.ArtistRelation>> fetchMusicBrainzRelations(
      String normArtist, String rawArtist) {
    if (musicBrainzOverride != null) {
      return musicBrainzOverride.apply(normArtist);
    }

    return CompletableFuture.supplyAsync(
        () -> {
          try {
            // Rate limit: 1 request per second
            long last = lastMusicBrainzRequest.get();
            long now = System.currentTimeMillis();
            long diff = now - last;
            if (diff < MUSICBRAINZ_RATE_LIMIT_MS) {
              try {
                Thread.sleep(MUSICBRAINZ_RATE_LIMIT_MS - diff);
              } catch (InterruptedException ignored) {
              }
            }
            lastMusicBrainzRequest.set(System.currentTimeMillis());

            String query =
                URLEncoder.encode("artist:\"" + rawArtist + "\"", StandardCharsets.UTF_8);
            String url = "https://musicbrainz.org/ws/2/artist?query=" + query + "&limit=1&fmt=json";

            HttpRequest request =
                HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
              return Collections.<TrackCache.ArtistRelation>emptyList();
            }

            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!root.has("artists") || !root.get("artists").isJsonArray()) {
              return Collections.<TrackCache.ArtistRelation>emptyList();
            }

            JsonArray artists = root.getAsJsonArray("artists");
            if (artists.isEmpty()) return Collections.<TrackCache.ArtistRelation>emptyList();

            JsonObject firstArtist = artists.get(0).getAsJsonObject();
            String mbid = firstArtist.has("id") ? firstArtist.get("id").getAsString() : null;
            if (mbid == null || mbid.isBlank())
              return Collections.<TrackCache.ArtistRelation>emptyList();

            // Now query relationships for this mbid
            // Wait another 1s for rate limit
            Thread.sleep(MUSICBRAINZ_RATE_LIMIT_MS);
            lastMusicBrainzRequest.set(System.currentTimeMillis());

            String relUrl =
                "https://musicbrainz.org/ws/2/artist/" + mbid + "?inc=artist-rels&fmt=json";
            HttpRequest relReq =
                HttpRequest.newBuilder()
                    .uri(URI.create(relUrl))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> relResp =
                httpClient.send(relReq, HttpResponse.BodyHandlers.ofString());
            if (relResp.statusCode() != 200) {
              return Collections.<TrackCache.ArtistRelation>emptyList();
            }

            JsonObject relRoot = JsonParser.parseString(relResp.body()).getAsJsonObject();
            List<TrackCache.ArtistRelation> results = new ArrayList<>();
            long nowTs = System.currentTimeMillis();

            if (relRoot.has("relations") && relRoot.get("relations").isJsonArray()) {
              for (JsonElement el : relRoot.getAsJsonArray("relations")) {
                if (el.isJsonObject()) {
                  JsonObject rel = el.getAsJsonObject();
                  String type = rel.has("type") ? rel.get("type").getAsString() : "";
                  // Band members, is person, spin-offs
                  if (rel.has("artist") && rel.get("artist").isJsonObject()) {
                    JsonObject targetArtist = rel.getAsJsonObject("artist");
                    String targetName =
                        targetArtist.has("name") ? targetArtist.get("name").getAsString() : "";
                    String targetNorm = ArtistNames.normalize(targetName);
                    if (!targetNorm.isEmpty() && !targetNorm.equals(normArtist)) {
                      results.add(
                          new TrackCache.ArtistRelation(
                              normArtist, targetNorm, type, 1.0, "musicbrainz", nowTs));
                    }
                  }
                }
              }
            }
            return results;
          } catch (Exception e) {
            if (plugin != null) {
              plugin
                  .getLogger()
                  .fine(
                      "[VibesInDev] MusicBrainz enricher error for "
                          + rawArtist
                          + ": "
                          + e.getMessage());
            }
            return Collections.<TrackCache.ArtistRelation>emptyList();
          }
        },
        executor);
  }

  /** Last.fm artist.getSimilar enricher. Match score is parsed as weight (0.0 to 1.0). */
  public CompletableFuture<List<TrackCache.ArtistRelation>> fetchLastFmRelations(
      String normArtist, String rawArtist, String apiKey) {
    if (lastFmOverride != null) {
      return lastFmOverride.apply(normArtist);
    }

    return CompletableFuture.supplyAsync(
        () -> {
          try {
            String enc = URLEncoder.encode(rawArtist, StandardCharsets.UTF_8);
            String url =
                "https://ws.audioscrobbler.com/2.0/?method=artist.getsimilar&artist="
                    + enc
                    + "&api_key="
                    + apiKey
                    + "&format=json&limit=10";

            HttpRequest request =
                HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
              return Collections.<TrackCache.ArtistRelation>emptyList();
            }

            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!root.has("similarartists") || !root.get("similarartists").isJsonObject()) {
              return Collections.<TrackCache.ArtistRelation>emptyList();
            }

            JsonObject sim = root.getAsJsonObject("similarartists");
            if (!sim.has("artist") || !sim.get("artist").isJsonArray()) {
              return Collections.<TrackCache.ArtistRelation>emptyList();
            }

            List<TrackCache.ArtistRelation> results = new ArrayList<>();
            long now = System.currentTimeMillis();

            for (JsonElement el : sim.getAsJsonArray("artist")) {
              if (el.isJsonObject()) {
                JsonObject aObj = el.getAsJsonObject();
                String name = aObj.has("name") ? aObj.get("name").getAsString() : "";
                double match = 0.5;
                if (aObj.has("match")) {
                  try {
                    match = Double.parseDouble(aObj.get("match").getAsString());
                  } catch (NumberFormatException ignored) {
                  }
                }
                String targetNorm = ArtistNames.normalize(name);
                if (!targetNorm.isEmpty() && !targetNorm.equals(normArtist)) {
                  results.add(
                      new TrackCache.ArtistRelation(
                          normArtist, targetNorm, "similar", match, "lastfm", now));
                }
              }
            }
            return results;
          } catch (Exception e) {
            if (plugin != null) {
              plugin
                  .getLogger()
                  .fine(
                      "[VibesInDev] Last.fm enricher error for "
                          + rawArtist
                          + ": "
                          + e.getMessage());
            }
            return Collections.<TrackCache.ArtistRelation>emptyList();
          }
        },
        executor);
  }
}

package dev.redbeastsam.vibesindev.recommendation;

import static org.junit.jupiter.api.Assertions.*;

import dev.redbeastsam.vibesindev.model.Track;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@SuppressWarnings("null")
public class GenreResolverTest {
  @Test
  @DisplayName(
      "Cosine similarity returns 1.0 for identical genres, 0.0 for disjoint, and -1.0 for missing")
  public void testCosineSimilarityCalculations() {
    PlayerTasteProfile profile = new PlayerTasteProfile(UUID.randomUUID());

    // Test with empty profile -> missing feature (-1.0)
    assertEquals(-1.0, profile.calculateGenreCosineSimilarity(List.of("synthwave")));
    assertEquals(0.20, profile.calculateGenreMatch(List.of("synthwave")), 1e-6);

    // Add 2 genres with equal mass: synthwave = 0.5, electronic = 0.5
    profile.ingestArtistGenres(List.of("synthwave", "electronic"), 1.0);

    // 1. Missing candidate genres -> -1.0
    assertEquals(-1.0, profile.calculateGenreCosineSimilarity(Collections.emptyList()));
    assertEquals(-1.0, profile.calculateGenreCosineSimilarity(null));
    assertEquals(0.20, profile.calculateGenreMatch(Collections.emptyList()), 1e-6);

    // 2. Disjoint genres -> 0.0
    assertEquals(
        0.0, profile.calculateGenreCosineSimilarity(List.of("country", "bluegrass")), 1e-6);

    // 3. Exact matching genres -> 1.0
    // C = {synthwave, electronic}, |C| = 2, candidate norm = sqrt(2)
    // P(synthwave) = 0.5, P(electronic) = 0.5. sum P^2 = 0.25 + 0.25 = 0.5. profile norm =
    // sqrt(0.5)
    // dot = 0.5 + 0.5 = 1.0. cosine = 1.0 / (sqrt(2) * sqrt(0.5)) = 1.0 / 1.0 = 1.0
    double exactCosine = profile.calculateGenreCosineSimilarity(List.of("synthwave", "electronic"));
    assertEquals(1.0, exactCosine, 1e-6);

    // 4. Single matching genre out of 2 profile genres:
    // C = {synthwave}, |C| = 1, candidate norm = 1.0
    // dot = 0.5. cosine = 0.5 / (1.0 * sqrt(0.5)) = 0.5 / 0.707106 = 1 / sqrt(2) ≈ 0.707106
    double partialCosine = profile.calculateGenreCosineSimilarity(List.of("synthwave"));
    assertEquals(1.0 / Math.sqrt(2.0), partialCosine, 1e-6);
  }

  @Test
  @DisplayName("Tiered resolution ladder queries Spotify first, then Last.fm")
  public void testResolutionLadderOrder() {
    GenreResolver resolver = new GenreResolver(null);

    AtomicInteger spotifyCalls = new AtomicInteger();
    AtomicInteger lastFmCalls = new AtomicInteger();

    resolver.setSpotifyLookupOverride(
        artist -> {
          spotifyCalls.incrementAndGet();
          if ("daft punk".equalsIgnoreCase(artist)) {
            return CompletableFuture.completedFuture(List.of("french house", "electronic"));
          }
          return CompletableFuture.completedFuture(Collections.emptyList());
        });

    resolver.setLastFmLookupOverride(
        artist -> {
          lastFmCalls.incrementAndGet();
          if ("obscure indie band".equalsIgnoreCase(artist)) {
            return CompletableFuture.completedFuture(List.of("indie pop", "lo-fi"));
          }
          return CompletableFuture.completedFuture(Collections.emptyList());
        });

    // Test 1: Hit Spotify
    List<String> daftGenres = resolver.genresFor("Daft Punk").join();
    assertEquals(List.of("french house", "electronic"), daftGenres);
    assertEquals(1, spotifyCalls.get());
    assertEquals(0, lastFmCalls.get(), "Last.fm must not be called when Spotify succeeds");

    // Subsequent getCachedGenres should hit memory cache synchronously
    assertEquals(List.of("french house", "electronic"), resolver.getCachedGenres("Daft Punk"));

    // Test 2: Miss Spotify -> hit Last.fm
    List<String> indieGenres = resolver.genresFor("Obscure Indie Band").join();
    assertEquals(List.of("indie pop", "lo-fi"), indieGenres);
    assertEquals(2, spotifyCalls.get());
    assertEquals(1, lastFmCalls.get());
  }

  @Test
  @DisplayName(
      "Negative caching: empty results are cached so unresolvable artists are not re-queried")
  public void testNegativeCaching() {
    GenreResolver resolver = new GenreResolver(null);

    AtomicInteger spotifyCalls = new AtomicInteger();
    AtomicInteger lastFmCalls = new AtomicInteger();

    resolver.setSpotifyLookupOverride(
        artist -> {
          spotifyCalls.incrementAndGet();
          return CompletableFuture.completedFuture(Collections.emptyList());
        });

    resolver.setLastFmLookupOverride(
        artist -> {
          lastFmCalls.incrementAndGet();
          return CompletableFuture.completedFuture(Collections.emptyList());
        });

    // First call: misses both
    List<String> res1 = resolver.genresFor("Completely Unknown Artist").join();
    assertTrue(res1.isEmpty());
    assertEquals(1, spotifyCalls.get());
    assertEquals(1, lastFmCalls.get());

    // Second call: should hit negative cache and NOT call providers again
    List<String> res2 = resolver.genresFor("Completely Unknown Artist").join();
    assertTrue(res2.isEmpty());
    assertEquals(1, spotifyCalls.get(), "Should not re-query Spotify on negative cache hit");
    assertEquals(1, lastFmCalls.get(), "Should not re-query Last.fm on negative cache hit");

    // Synchronous check returns empty list
    assertTrue(resolver.getCachedGenres("Completely Unknown Artist").isEmpty());
  }

  @Test
  @DisplayName(
      "Stream-based priors removed in v2: YouTube candidate with matching genres beats Stream B"
          + " with missing genres")
  public void testStreamPriorsRemovedInV2() {
    RecommendationEngine engine = new RecommendationEngine(null);
    GenreResolver resolver = engine.genreResolver();

    PlayerTasteProfile profile = new PlayerTasteProfile(UUID.randomUUID());
    // Profile likes synthwave and retrowave
    profile.ingestArtistGenres(List.of("synthwave", "retrowave"), 1.0);

    // Pre-cache genres for "Kavinsky"
    resolver.saveAndCache("kavinsky", List.of("synthwave", "retrowave"), "TEST");

    Track seed = new Track("seed", "Seed Song", "Initial Artist", 200, "url0");

    // Candidate 1: YouTube Radio stream, artist Kavinsky (has exact matching genres)
    Track kavinskyTrack = new Track("kav", "Nightcall", "Kavinsky", 240, "url1");
    RecommendationCandidate ytCandidate =
        new RecommendationCandidate(
            kavinskyTrack, 0.0, RecommendationCandidate.Source.YOUTUBE_RADIO, "YT Radio", false);

    // Candidate 2: Spotify Genre stream, but artist has no genres in profile
    Track genericGenreTrack = new Track("gen", "Generic Song", "Unmatched Artist", 240, "url2");
    RecommendationCandidate genreCandidate =
        new RecommendationCandidate(
            genericGenreTrack,
            0.0,
            RecommendationCandidate.Source.SPOTIFY_GENRE,
            "Spotify Genre",
            false);

    // In v2 (enabled in config or via scoreAndPickBestV2):
    Track chosen = engine.scoreAndPickBestV2(List.of(genreCandidate, ytCandidate), seed, profile);
    assertNotNull(chosen);
    assertEquals(
        "kav",
        chosen.id(),
        "Candidate with real genre match must win over candidate relying on stream B prior");
  }
}

package dev.redbeastsam.vibesindev.recommendation;

import static org.junit.jupiter.api.Assertions.*;

import dev.redbeastsam.vibesindev.model.Track;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@SuppressWarnings("null")
public class EngineTimeoutTest {
  private RecommendationEngine engine;

  @BeforeEach
  void setUp() {
    engine = new RecommendationEngine(null);
  }

  @AfterEach
  void tearDown() {
    if (engine != null) {
      engine.shutdown();
    }
  }

  private Track makeTrack(String id, String title, String artist) {
    return new Track(id, title, artist, 180L, "http://example.com/audio.mp3");
  }

  @Test
  @DisplayName("Simulate hung stream: engine returns healthy stream result within 3500ms")
  void testHungStreamCompletesWithHealthyStreamCandidatesWithinTimeout() throws Exception {
    Track seed = makeTrack("seed", "Seed Song", "Artist X");
    PlayerTasteProfile profile = new PlayerTasteProfile(UUID.randomUUID());

    // 1. Hung stream that never completes
    CompletableFuture<List<RecommendationCandidate>> hungStream = new CompletableFuture<>();

    // 2. Healthy stream that immediately provides a candidate
    Track healthyTrack = makeTrack("h1", "Healthy Choice", "Artist Y");
    RecommendationCandidate healthyCandidate =
        new RecommendationCandidate(
            healthyTrack, 0.0, RecommendationCandidate.Source.YOUTUBE_RADIO, "Healthy", false);
    CompletableFuture<List<RecommendationCandidate>> healthyStream =
        CompletableFuture.completedFuture(List.of(healthyCandidate));

    long start = System.currentTimeMillis();

    CompletableFuture<Track> pickFuture =
        engine.gatherAndPick(List.of(hungStream, healthyStream), seed, profile);

    // Should complete in ~3000ms (timeout) + small buffer (under 4000ms)
    Track chosen = pickFuture.get(4000, TimeUnit.MILLISECONDS);
    long elapsed = System.currentTimeMillis() - start;

    assertNotNull(chosen, "Should pick the candidate from the healthy stream");
    assertEquals("h1", chosen.id());
    assertTrue(
        elapsed >= 2800 && elapsed <= 3800,
        "Elapsed time should be around 3000ms timeout: " + elapsed);
  }

  @Test
  @DisplayName("Simulate all streams failing/hung: falls back without throwing within timeout")
  void testAllStreamsHangingFallsBackWithoutThrowingWithinTimeout() throws Exception {
    Track seed = makeTrack("seed", "Seed Song", "Artist X");
    PlayerTasteProfile profile = new PlayerTasteProfile(UUID.randomUUID());

    CompletableFuture<List<RecommendationCandidate>> hungStream1 = new CompletableFuture<>();
    CompletableFuture<List<RecommendationCandidate>> hungStream2 = new CompletableFuture<>();

    long start = System.currentTimeMillis();

    CompletableFuture<Track> pickFuture =
        engine.gatherAndPick(List.of(hungStream1, hungStream2), seed, profile);

    // Since plugin is null, fallbackTrending completes with null without throwing
    Track result = pickFuture.get(4000, TimeUnit.MILLISECONDS);
    long elapsed = System.currentTimeMillis() - start;

    assertNull(result, "Null fallback when all streams hang and plugin cache is null");
    assertTrue(
        elapsed >= 2800 && elapsed <= 3800,
        "Elapsed time should be around 3000ms timeout: " + elapsed);
  }
}

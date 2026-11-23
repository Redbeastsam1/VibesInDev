package dev.redbeastsam.vibesindev.recommendation;

import static org.junit.jupiter.api.Assertions.*;

import dev.redbeastsam.vibesindev.model.Track;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@SuppressWarnings("null")
public class ProfileThreadSafetyTest {
  private Track makeTrack(String id, String title, String artist) {
    return new Track(id, title, artist, 180L, "http://example.com/audio.mp3");
  }

  @Test
  @DisplayName(
      "Concurrent reads and mutations on PlayerTasteProfile across 10 threads do not corrupt state"
          + " or throw")
  void testConcurrentReadAndWriteSafety() throws Exception {
    PlayerTasteProfile profile = new PlayerTasteProfile(UUID.randomUUID());
    int threads = 10;
    int operationsPerThread = 100;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch latch = new CountDownLatch(threads);
    AtomicInteger errorCount = new AtomicInteger();

    for (int t = 0; t < threads; t++) {
      final int threadId = t;
      pool.submit(
          () -> {
            try {
              for (int i = 0; i < operationsPerThread; i++) {
                Track track =
                    makeTrack(
                        "t_" + threadId + "_" + i, "Song " + (i % 5), "Artist " + (threadId % 3));
                // Interleave starts, outcomes, likes, dislikes, and queries
                profile.recordStart(track);
                profile.recordOutcome(
                    track, 180_000L, 180_000L, RecommendationManager.Outcome.COMPLETED, null);
                if (i % 4 == 0) {
                  profile.recordLike(track);
                } else if (i % 7 == 0) {
                  profile.recordDislike(track);
                }

                // Concurrent reads
                profile.getArtistAffinity("Artist " + (threadId % 3));
                profile.isFatigued(track, 20, 45 * 60_000L);
                profile.getRecentArtistCount("Artist " + (threadId % 3));
              }
            } catch (Throwable ex) {
              errorCount.incrementAndGet();
              ex.printStackTrace();
            } finally {
              latch.countDown();
            }
          });
    }

    assertTrue(
        latch.await(10, TimeUnit.SECONDS), "Concurrent operations must complete within 10 seconds");
    pool.shutdown();

    assertEquals(0, errorCount.get(), "No exceptions should occur during concurrent mutations");

    // Verify bounds integrity
    assertTrue(
        profile.getFatigueHistory().size() <= 50, "Fatigue history must not exceed capacity 50");
    // Verify affinities are non-negative
    for (double aff : profile.getArtistAffinities().values()) {
      assertTrue(aff >= 0.0 && aff <= 1.0, "Affinity must remain bounded in [0.0, 1.0]");
    }
  }
}

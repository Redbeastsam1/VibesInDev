package dev.redbeastsam.vibesindev.recommendation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class GenreWarmerTest {
  @Test
  @DisplayName("Task 4.2: PlayerTasteProfile.getTopArtists returns top artists by affinity")
  void testGetTopArtists() {
    UUID uuid = UUID.randomUUID();
    PlayerTasteProfile profile = new PlayerTasteProfile(uuid);

    long now = System.currentTimeMillis();
    profile.restoreFromSnapshot(
        Map.of(
            "artist1", 0.9,
            "artist2", 0.3,
            "artist3", 0.7,
            "artist4", 0.5),
        Collections.emptyMap(),
        null,
        null,
        Collections.emptyMap(),
        Collections.emptyMap(),
        now,
        Map.of("artist1", now, "artist2", now, "artist3", now, "artist4", now));

    List<String> top2 = profile.getTopArtists(2);
    assertEquals(2, top2.size());
    assertEquals("artist1", top2.get(0));
    assertEquals("artist3", top2.get(1));

    List<String> all = profile.getTopArtists(10);
    assertEquals(4, all.size());
    assertEquals("artist1", all.get(0));
    assertEquals("artist3", all.get(1));
    assertEquals("artist4", all.get(2));
    assertEquals("artist2", all.get(3));
  }

  @Test
  @DisplayName("Task 4.2: Background genre warmer tops up missing genres for top artists")
  void testGenreWarmerTopsUpMissing() {
    GenreResolver resolver = new GenreResolver(null);

    // Spotify override returns genres
    resolver.setSpotifyLookupOverride(
        artist -> {
          if ("artist1".equals(artist)) {
            return CompletableFuture.completedFuture(List.of("synthwave", "retrowave"));
          }
          if ("artist2".equals(artist)) {
            return CompletableFuture.completedFuture(List.of("french house"));
          }
          return CompletableFuture.completedFuture(Collections.emptyList());
        });

    UUID uuid = UUID.randomUUID();
    PlayerTasteProfile profile = new PlayerTasteProfile(uuid);
    long now = System.currentTimeMillis();
    profile.restoreFromSnapshot(
        Map.of(
            "artist1", 0.8,
            "artist2", 0.6),
        Collections.emptyMap(),
        null,
        null,
        Collections.emptyMap(),
        Collections.emptyMap(),
        now,
        Map.of("artist1", now, "artist2", now));

    // Before warming, nothing in cache
    assertTrue(resolver.getCachedGenres("artist1").isEmpty());
    assertTrue(resolver.getCachedGenres("artist2").isEmpty());

    // Warm top artists
    int count = resolver.warmTopArtists(profile, 50).join();
    assertEquals(2, count);

    // Now both should be cached
    assertEquals(List.of("synthwave", "retrowave"), resolver.getCachedGenres("artist1"));
    assertEquals(List.of("french house"), resolver.getCachedGenres("artist2"));

    // Running warmer again should find 0 missing
    int countSecond = resolver.warmTopArtists(profile, 50).join();
    assertEquals(0, countSecond);
  }
}

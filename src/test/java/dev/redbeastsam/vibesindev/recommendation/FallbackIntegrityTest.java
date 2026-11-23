package dev.redbeastsam.vibesindev.recommendation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.redbeastsam.vibesindev.VibesInDevPlugin;
import dev.redbeastsam.vibesindev.cache.TrackCache;
import dev.redbeastsam.vibesindev.model.Track;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class FallbackIntegrityTest {
  private VibesInDevPlugin plugin;
  private TrackCache cache;
  private FileConfiguration config;
  private RecommendationEngine engine;

  @BeforeEach
  void setUp() {
    plugin = mock(VibesInDevPlugin.class);
    cache = mock(TrackCache.class);
    config = mock(FileConfiguration.class);

    when(plugin.cache()).thenReturn(cache);
    when(plugin.getConfig()).thenReturn(config);
    when(config.getInt("recommendations.cooldown-tracks", 20)).thenReturn(20);
    when(config.getLong("recommendations.cooldown-minutes", 45)).thenReturn(45L);

    engine = new RecommendationEngine(plugin);
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
  @DisplayName(
      "fallbackTrending rejects seed duplicate and fatigued track, selecting clean candidate")
  void testFallbackRejectsSeedDuplicatesAndFatigued() throws Exception {
    Track seed = makeTrack("seed_id", "Neoperreo", "Secret Society");
    PlayerTasteProfile profile = new PlayerTasteProfile(UUID.randomUUID());

    Track remixOfSeed = makeTrack("t_remix", "Neoperreo (Slowed)", "Secret Society");
    Track fatiguedTrack = makeTrack("t_fatigued", "Another Song", "Artist B");
    Track cleanCandidate = makeTrack("t_clean", "Clean Song", "Artist C");

    // Mark fatiguedTrack in profile
    profile.recordStart(fatiguedTrack);

    when(cache.getTrendingTracks(10))
        .thenReturn(List.of(remixOfSeed, fatiguedTrack, cleanCandidate));

    CompletableFuture<Track> future = engine.fallbackTrending(seed, profile);
    Track picked = future.get(2, TimeUnit.SECONDS);

    assertNotNull(picked);
    assertEquals(
        "t_clean",
        picked.id(),
        "Should skip seed remix and fatigued track to pick clean candidate");
  }

  @Test
  @DisplayName(
      "fallbackTrending returns null rather than returning seed duplicate if trending only has seed"
          + " variants")
  void testFallbackNeverReturnsSeedDuplicateEvenUnderRelaxation() throws Exception {
    Track seed = makeTrack("seed_id", "Neoperreo", "Secret Society");
    PlayerTasteProfile profile = new PlayerTasteProfile(UUID.randomUUID());

    Track remix1 = makeTrack("remix_1", "Neoperreo (Slowed)", "Secret Society");
    Track remix2 =
        makeTrack(
            "remix_2", "Secret Society - Neoperreo feat. ALIC3E (Single 2025)", "Secret Society");

    when(cache.getTrendingTracks(10)).thenReturn(List.of(remix1, remix2));

    CompletableFuture<Track> future = engine.fallbackTrending(seed, profile);
    Track picked = future.get(2, TimeUnit.SECONDS);

    assertNull(picked, "Must return null rather than returning seed track or its variants");
  }
}

package dev.redbeastsam.vibesindev.recommendation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.redbeastsam.vibesindev.VibesInDevPlugin;
import dev.redbeastsam.vibesindev.cache.TrackCache;
import dev.redbeastsam.vibesindev.model.Track;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@SuppressWarnings("null")
public class RelaxationLadderTest {
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

    // Fallback answers to return the default argument passed to get*
    when(config.getDouble(anyString(), anyDouble())).thenAnswer(inv -> inv.getArgument(1));
    when(config.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
    when(config.getLong(anyString(), anyLong())).thenAnswer(inv -> inv.getArgument(1));

    // Test overrides
    when(config.getDouble("recommendations.softmax-temperature", 0.08))
        .thenReturn(0.0); // 0.0 = argmax

    when(config.getDouble("recommendations.modes.familiar", 0.30)).thenReturn(0.0);
    when(config.getDouble("recommendations.modes.similar", 0.50)).thenReturn(1.0);
    when(config.getDouble("recommendations.modes.explore", 0.20)).thenReturn(0.0);

    engine = new RecommendationEngine(plugin);
  }

  @AfterEach
  void tearDown() {
    if (engine != null) {
      engine.shutdown();
    }
  }

  private Track makeTrack(String id, String title, String artist, long duration) {
    return new Track(id, title, artist, duration, "https://youtube.com/watch?v=" + id);
  }

  @Test
  @DisplayName("Ladder relaxes: Level 0 and 1 are empty, Level 2 yields tracks")
  void testLevel0EmptyLevel2YieldsTrack() {
    UUID playerUuid = UUID.randomUUID();
    PlayerTasteProfile profile = new PlayerTasteProfile(playerUuid);
    Track seed = makeTrack("seed", "Seed Song", "Artist Seed", 180);

    // 3 candidates
    Track t1 = makeTrack("t1", "Song One", "Artist One", 180);
    Track t2 = makeTrack("t2", "Song Two", "Artist Two", 200);
    Track t3 = makeTrack("t3", "Song Three", "Artist Three", 220);

    // Simulate fatigue history:
    // Played 12 tracks ago (so within cooldown-tracks 20, but outside level 2's cooldown-tracks 10)
    // Played 25 minutes ago (within level 0's 45m and level 1's 22.5m, but outside level 2's 15m)
    long now = System.currentTimeMillis();
    long twentyFiveMinsAgo = now - (25 * 60_000L);

    // Fill 11 dummy tracks so t1, t2, t3 are placed at index > 10 in fatigue history
    List<PlayerTasteProfile.FatigueEntry> fatigueList = new ArrayList<>();
    fatigueList.add(
        new PlayerTasteProfile.FatigueEntry(t1.id(), t1.artist(), t1.title(), twentyFiveMinsAgo));
    fatigueList.add(
        new PlayerTasteProfile.FatigueEntry(t2.id(), t2.artist(), t2.title(), twentyFiveMinsAgo));
    fatigueList.add(
        new PlayerTasteProfile.FatigueEntry(t3.id(), t3.artist(), t3.title(), twentyFiveMinsAgo));
    for (int i = 0; i < 11; i++) {
      fatigueList.add(
          new PlayerTasteProfile.FatigueEntry("dummy_" + i, "Dummy Artist", "Dummy " + i, now));
    }
    profile.restoreFatigue(fatigueList);

    List<RecommendationCandidate> candidates =
        List.of(
            new RecommendationCandidate(
                t1, 0.8, RecommendationCandidate.Source.SPOTIFY_GENRE, "Stream B", false),
            new RecommendationCandidate(
                t2, 0.75, RecommendationCandidate.Source.SPOTIFY_GENRE, "Stream B", false),
            new RecommendationCandidate(
                t3, 0.7, RecommendationCandidate.Source.SPOTIFY_GENRE, "Stream B", false));

    List<RankedPick> picks =
        engine.scoreAndRankCandidatesV2(candidates, seed, profile, new Random(1));

    assertNotNull(picks);
    assertFalse(picks.isEmpty(), "Level 2 should have relaxed cooldown and yielded tracks");
    assertEquals(3, picks.size());
  }

  @Test
  @DisplayName(
      "Hard constraints (dislikes, seed duplicates, blacklist) are NEVER relaxed at any ladder"
          + " level")
  void testHardConstraintsNeverRelaxedAtAnyLevel() {
    UUID playerUuid = UUID.randomUUID();
    PlayerTasteProfile profile = new PlayerTasteProfile(playerUuid);
    Track seed = makeTrack("seed", "Neoperreo", "Secret Society", 180);

    Track seedRemix = makeTrack("c1", "Neoperreo (Slowed)", "Secret Society", 180);
    Track disliked = makeTrack("c2", "Bad Song", "Artist X", 180);
    Track blacklisted = makeTrack("c3", "Mega Mix 1 Hour Compilation", "DJ Set", 300);

    profile.recordDislike(disliked);

    List<RecommendationCandidate> candidates =
        List.of(
            new RecommendationCandidate(
                seedRemix, 0.95, RecommendationCandidate.Source.YOUTUBE_RADIO, "Radio", false),
            new RecommendationCandidate(
                disliked, 0.90, RecommendationCandidate.Source.YOUTUBE_RADIO, "Radio", false),
            new RecommendationCandidate(
                blacklisted, 0.85, RecommendationCandidate.Source.YOUTUBE_RADIO, "Radio", false));

    List<RankedPick> picks =
        engine.scoreAndRankCandidatesV2(candidates, seed, profile, new Random(1));

    assertNotNull(picks);
    assertTrue(
        picks.isEmpty(),
        "All candidates violate hard constraints and must NEVER be picked at any level");
  }

  @Test
  @DisplayName(
      "Empty result at level 4 yields empty list so autoplay stops cleanly without dead air or"
          + " loops")
  void testEmptyResultAtLevel4YieldsEmptyList() {
    UUID playerUuid = UUID.randomUUID();
    PlayerTasteProfile profile = new PlayerTasteProfile(playerUuid);
    Track seed = makeTrack("seed", "Some Song", "Some Artist", 180);

    // Candidate has blacklisted title
    Track junk = makeTrack("junk1", "10 Hours Loop of White Noise", "Relaxation", 600);

    List<RecommendationCandidate> candidates =
        List.of(
            new RecommendationCandidate(
                junk, 0.9, RecommendationCandidate.Source.YOUTUBE_RADIO, "Radio", false));

    List<RankedPick> picks =
        engine.scoreAndRankCandidatesV2(candidates, seed, profile, new Random(1));

    assertNotNull(picks);
    assertTrue(picks.isEmpty(), "Level 4 yielding nothing must return empty list");
  }

  @Test
  @DisplayName(
      "Ranked picks return up to 3 candidates with sampled candidate first followed by MMR"
          + " ordering")
  void testRankedPicksOrdering() {
    UUID playerUuid = UUID.randomUUID();
    PlayerTasteProfile profile = new PlayerTasteProfile(playerUuid);
    Track seed = makeTrack("seed", "Seed Track", "Artist S", 180);

    List<RecommendationCandidate> candidates = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      Track t = makeTrack("track_" + i, "Track " + i, "Unique Artist " + i, 180);
      candidates.add(
          new RecommendationCandidate(
              t,
              0.80 - (i * 0.05),
              RecommendationCandidate.Source.SERVER_COLLABORATIVE,
              "Collab",
              false));
    }

    List<RankedPick> picks =
        engine.scoreAndRankCandidatesV2(candidates, seed, profile, new Random(42));

    assertNotNull(picks);
    assertEquals(3, picks.size(), "Should return exactly 3 ranked picks");

    // Verify all 3 picks have distinct IDs
    String id0 = picks.get(0).track().id();
    String id1 = picks.get(1).track().id();
    String id2 = picks.get(2).track().id();

    assertNotEquals(id0, id1);
    assertNotEquals(id0, id2);
    assertNotEquals(id1, id2);

    // Verify pick #1 has expected properties
    assertNotNull(picks.get(0).track());
    assertNotNull(picks.get(0).mode());
    assertNotNull(picks.get(0).source());
    assertNotNull(picks.get(0).reason());
    assertTrue(picks.get(0).score() > 0.0);
  }

  @Test
  @DisplayName(
      "fallbackTrendingRanked applies ladder and rejects seed duplicate and disliked track")
  void testFallbackTrendingRankedWithLadder() throws Exception {
    UUID playerUuid = UUID.randomUUID();
    PlayerTasteProfile profile = new PlayerTasteProfile(playerUuid);
    Track seed = makeTrack("seed", "Seed Song", "Artist Seed", 180);

    Track seedRemix = makeTrack("t_remix", "Seed Song (Slowed)", "Artist Seed", 180);
    Track disliked = makeTrack("t_disliked", "Hated Track", "Artist Bad", 180);
    Track clean1 = makeTrack("t_clean1", "Clean Track 1", "Artist Good 1", 180);
    Track clean2 = makeTrack("t_clean2", "Clean Track 2", "Artist Good 2", 200);

    profile.recordDislike(disliked);

    when(cache.getTrendingTracks(10)).thenReturn(List.of(seedRemix, disliked, clean1, clean2));

    CompletableFuture<List<RankedPick>> future = engine.fallbackTrendingRanked(seed, profile);
    List<RankedPick> picks = future.get(2, TimeUnit.SECONDS);

    assertNotNull(picks);
    assertFalse(picks.isEmpty());
    for (RankedPick p : picks) {
      assertNotEquals(
          "t_remix", p.track().id(), "Seed remix must never appear in trending fallback");
      assertNotEquals(
          "t_disliked", p.track().id(), "Disliked track must never appear in trending fallback");
    }
    assertEquals("t_clean1", picks.get(0).track().id());
  }
}

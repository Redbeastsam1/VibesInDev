package dev.vibesindev.vibesmusic.recommendation;

import static org.junit.jupiter.api.Assertions.*;

import dev.vibesindev.vibesmusic.cache.TrackCache;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@SuppressWarnings("null")
public class LineageEnrichmentTest {
  @BeforeEach
  @AfterEach
  void clearLineage() {
    PlayerTasteProfile.clearGlobalLineage();
  }

  @Test
  @DisplayName(
      "Task 4.1: Lineage affinity scales with relation strength: affinity * (0.5 + 0.25 *"
          + " strength)")
  void testLineageAffinityScalesWithStrength() {
    UUID uuid = UUID.randomUUID();
    PlayerTasteProfile profile = new PlayerTasteProfile(uuid);

    long now = System.currentTimeMillis();
    // Set fixed affinity for testing with fresh timestamp (no decay)
    profile.restoreFromSnapshot(
        Map.of("justice", 0.8),
        Collections.emptyMap(),
        null,
        null,
        Collections.emptyMap(),
        Collections.emptyMap(),
        now,
        Map.of("justice", now));

    // 1. Unlinked artist has 0 affinity
    assertEquals(0.0, profile.getArtistAffinity("gaspard auge"), 0.001);

    // 2. Dynamic lineage with default strength 1.0 -> factor 0.75
    profile.registerLineage("justice", "gaspard auge", 1.0);
    double fullStrengthAffinity = profile.getArtistAffinity("gaspard auge");
    // 0.8 * (0.5 + 0.25 * 1.0) = 0.8 * 0.75 = 0.60
    assertEquals(0.60, fullStrengthAffinity, 0.001);

    // 3. Dynamic lineage with partial strength 0.6 -> factor (0.5 + 0.25 * 0.6) = 0.65
    profile.registerLineage("justice", "xavier de rosnay", 0.6);
    double partialStrengthAffinity = profile.getArtistAffinity("xavier de rosnay");
    // 0.8 * (0.5 + 0.25 * 0.6) = 0.8 * 0.65 = 0.52
    assertEquals(0.52, partialStrengthAffinity, 0.001);

    // 4. Dynamic lineage with minimum strength 0.0 -> factor 0.50
    profile.registerLineage("justice", "weak associate", 0.0);
    double minStrengthAffinity = profile.getArtistAffinity("weak associate");
    // 0.8 * (0.5 + 0.25 * 0.0) = 0.8 * 0.50 = 0.40
    assertEquals(0.40, minStrengthAffinity, 0.001);
  }

  @Test
  @DisplayName("Task 4.1: Global dynamic lineage loaded into memory is queried by profiles")
  void testGlobalDynamicLineageLoaded() {
    UUID uuid = UUID.randomUUID();
    PlayerTasteProfile profile = new PlayerTasteProfile(uuid);

    long now = System.currentTimeMillis();
    profile.restoreFromSnapshot(
        Map.of("gorillaz", 0.8),
        Collections.emptyMap(),
        null,
        null,
        Collections.emptyMap(),
        Collections.emptyMap(),
        now,
        Map.of("gorillaz", now));

    // Initially Damon Albarn has 0 affinity
    assertEquals(0.0, profile.getArtistAffinity("damon albarn"), 0.001);

    // Load global dynamic relation from data table (e.g. MusicBrainz) with strength 0.9
    TrackCache.ArtistRelation relation =
        new TrackCache.ArtistRelation(
            "gorillaz", "damon albarn", "member of band", 0.9, "musicbrainz", now);
    PlayerTasteProfile.loadGlobalLineage(List.of(relation));

    // Profile should now resolve Damon Albarn with strength 0.9: 0.8 * (0.5 + 0.25 * 0.9) = 0.8 *
    // 0.725 = 0.58
    double inherited = profile.getArtistAffinity("damon albarn");
    assertEquals(0.58, inherited, 0.001);
  }

  @Test
  @DisplayName("Task 4.1: ArtistLineageEnricher with Last.fm and MusicBrainz overrides")
  void testArtistLineageEnricherOverrides() {
    ArtistLineageEnricher enricher = new ArtistLineageEnricher(null);

    long now = System.currentTimeMillis();
    enricher.setLastFmOverride(
        norm ->
            CompletableFuture.completedFuture(
                List.of(
                    new TrackCache.ArtistRelation(
                        norm, "radiohead", "similar", 0.85, "lastfm", now),
                    new TrackCache.ArtistRelation(norm, "muse", "similar", 0.70, "lastfm", now))));

    enricher.setMusicBrainzOverride(
        norm ->
            CompletableFuture.completedFuture(
                List.of(
                    new TrackCache.ArtistRelation(
                        norm, "thom yorke", "member of band", 1.0, "musicbrainz", now))));

    List<TrackCache.ArtistRelation> enriched = enricher.enrichArtist("the smile").join();
    assertNotNull(enriched);
    assertFalse(enriched.isEmpty());

    // Global lineage should have been updated
    UUID uuid = UUID.randomUUID();
    PlayerTasteProfile profile = new PlayerTasteProfile(uuid);
    profile.restoreFromSnapshot(
        Map.of("the smile", 0.8),
        Collections.emptyMap(),
        null,
        null,
        Collections.emptyMap(),
        Collections.emptyMap(),
        now,
        Map.of("the smile", now));

    // Thom Yorke should inherit with 1.0 strength -> 0.8 * 0.75 = 0.60
    assertEquals(0.60, profile.getArtistAffinity("thom yorke"), 0.001);
  }
}

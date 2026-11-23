package dev.redbeastsam.vibesindev.recommendation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.redbeastsam.vibesindev.VibesInDevPlugin;
import dev.redbeastsam.vibesindev.cache.TrackCache;
import dev.redbeastsam.vibesindev.model.Track;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings("null")
public class TimeDecayTest {
  static class MutableClock extends Clock {
    private Instant currentInstant;
    private final ZoneId zoneId = ZoneId.of("UTC");

    public MutableClock(Instant start) {
      this.currentInstant = start;
    }

    public void advance(Duration duration) {
      this.currentInstant = this.currentInstant.plus(duration);
    }

    public void set(Instant instant) {
      this.currentInstant = instant;
    }

    @Override
    public ZoneId getZone() {
      return zoneId;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return currentInstant;
    }
  }

  @Test
  @DisplayName("Artist affinity of 0.8 decays to ~0.4 after 30 days and floors at prior of 0.02")
  void testAffinityDecaysOverTimeWithClockInjection() {
    MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    UUID uuid = UUID.randomUUID();
    PlayerTasteProfile profile = new PlayerTasteProfile(uuid, clock);
    profile.setHalfLifeDays(30.0);

    // Ingest artist with affinity 0.8
    Track track = new Track("vid_1", "Test Song", "Daft Punk", 180, "http://example.com");
    profile.recordOutcome(track, 180_000L, 180_000L, RecommendationManager.Outcome.COMPLETED, null);

    // Directly verify baseline affinity is positive
    double baselineAffinity = profile.getArtistAffinity("daft punk");
    assertTrue(baselineAffinity > 0.0, "Baseline affinity must be > 0");

    // Set exact affinity 0.8 for clean math validation
    profile.restoreFromSnapshot(
        Map.of("daft punk", 0.80),
        null,
        null,
        null,
        null,
        null,
        clock.millis(),
        Map.of("daft punk", clock.millis()));

    assertEquals(
        0.80, profile.getArtistAffinity("daft punk"), 0.001, "Initial affinity must be 0.80");

    // Advance 30 days (1 half-life): should decay to 0.40
    clock.advance(Duration.ofDays(30));
    profile.invalidateAffinityCache();
    double after30Days = profile.getArtistAffinity("daft punk");
    assertEquals(0.40, after30Days, 0.01, "Affinity after 30 days (1 half life) should be ~0.40");

    // Advance another 30 days (60 days total, 2 half-lives): should decay to 0.20
    clock.advance(Duration.ofDays(30));
    profile.invalidateAffinityCache();
    double after60Days = profile.getArtistAffinity("daft punk");
    assertEquals(0.20, after60Days, 0.01, "Affinity after 60 days (2 half lives) should be ~0.20");

    // Advance 300 days (10 half-lives): should be floored at 0.02 prior
    clock.advance(Duration.ofDays(300));
    profile.invalidateAffinityCache();
    double after360Days = profile.getArtistAffinity("daft punk");
    assertEquals(
        0.02, after360Days, 0.001, "Affinity after 10+ half lives must be floored at 0.02");

    // getArtistAffinities() also returns effective decayed values
    Map<String, Double> effectiveMap = profile.getArtistAffinities();
    assertEquals(0.02, effectiveMap.get("daft punk"), 0.001);
  }

  @Test
  @DisplayName("Spotify sync re-stamps updatedAt and resets decay")
  void testSyncResetsDecay() {
    MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    UUID uuid = UUID.randomUUID();
    PlayerTasteProfile profile = new PlayerTasteProfile(uuid, clock);
    profile.setHalfLifeDays(30.0);

    // Initial sync of Daft Punk at T=0
    profile.ingestSpotifyArtists(List.of("Daft Punk"), null);
    double initial = profile.getArtistAffinity("daft punk");
    assertEquals(1.0, initial, 0.001);

    // Advance 30 days: decays by 50%
    clock.advance(Duration.ofDays(30));
    profile.invalidateAffinityCache();
    double decayed = profile.getArtistAffinity("daft punk");
    assertEquals(0.50, decayed, 0.01);

    // Re-sync on day 30: re-stamps updatedAt to day 30
    profile.ingestSpotifyArtists(List.of("Daft Punk"), null);
    profile.invalidateAffinityCache();
    double refreshed = profile.getArtistAffinity("daft punk");
    assertEquals(1.0, refreshed, 0.001, "Sync must reset decay back to full strength");
  }

  @Test
  @DisplayName("Old snapshots without affinity_ts_json still load safely using default timestamp")
  void testOldSnapshotsWithoutTimestampsStillLoad() {
    MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    UUID uuid = UUID.randomUUID();
    PlayerTasteProfile profile = new PlayerTasteProfile(uuid, clock);
    profile.setHalfLifeDays(30.0);

    long syncTime = clock.millis();
    // Restore from snapshot with null affinityTimestamps (simulating legacy database record)
    profile.restoreFromSnapshot(
        Map.of("queen", 0.80), Map.of("rock", 1.0), null, null, null, null, syncTime, null);

    // Immediately after load (deltaMs = 0), affinity is full 0.80
    assertEquals(0.80, profile.getArtistAffinity("queen"), 0.001);

    // Advance 30 days: decays to 0.40 using syncTime as the fallback timestamp
    clock.advance(Duration.ofDays(30));
    profile.invalidateAffinityCache();
    assertEquals(0.40, profile.getArtistAffinity("queen"), 0.01);
  }

  @Test
  @DisplayName("Play genre mass decays over time in getGenreDistribution")
  void testPlayGenreMassDecaysOverTime() {
    MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    UUID uuid = UUID.randomUUID();
    PlayerTasteProfile profile = new PlayerTasteProfile(uuid, clock);
    profile.setHalfLifeDays(30.0);

    // Ingest rock at T=0
    profile.ingestArtistGenres(List.of("rock"), 1.0);

    // Advance 30 days and ingest electronic at T=30
    clock.advance(Duration.ofDays(30));
    profile.ingestArtistGenres(List.of("electronic"), 1.0);

    // At T=30: rock mass decayed by 0.5 (effective 0.5), electronic is 1.0
    // Total sum = 1.5 -> rock = 0.5/1.5 = 0.333, electronic = 1.0/1.5 = 0.667
    Map<String, Double> dist = profile.getGenreDistribution();
    assertEquals(0.333, dist.get("rock"), 0.02);
    assertEquals(0.667, dist.get("electronic"), 0.02);
  }

  @Test
  @DisplayName("SQLite persistence round-trips affinity_ts_json and supports migration")
  void testSQLitePersistenceAndMigrationRoundTrip(@TempDir Path tempDir) {
    VibesInDevPlugin plugin = mock(VibesInDevPlugin.class);
    when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
    when(plugin.getLogger()).thenReturn(Logger.getLogger("TimeDecayTest"));

    FileConfiguration config = mock(FileConfiguration.class);
    when(config.getBoolean("cache.enabled", true)).thenReturn(true);
    when(config.getString("cache.directory", "cache")).thenReturn("cache");
    when(config.getString("cache.raw-directory", "raw")).thenReturn("raw");
    when(config.getInt("cache.max-size-gb", 20)).thenReturn(20);
    when(config.getBoolean("cache.cleanup-enabled", true)).thenReturn(true);
    when(plugin.getConfig()).thenReturn(config);

    TrackCache cache = new TrackCache(plugin);
    UUID playerUuid = UUID.randomUUID();
    long now = 1700000000000L;

    String affinitiesJson = "{\"daft punk\":0.8}";
    String genresJson = "{\"electronic\":1.0}";
    String dislikesJson = "{}";
    String lineageJson = "{}";
    long lastSync = now;
    String genreMassJson = "{\"play\":{\"electronic\":1.0}}";
    String affinityTsJson = "{\"daft punk\":" + now + "}";

    // Save with affinity timestamps
    cache.saveTasteProfile(
        playerUuid,
        affinitiesJson,
        genresJson,
        dislikesJson,
        lineageJson,
        lastSync,
        genreMassJson,
        affinityTsJson);

    // Load and verify snapshot
    TrackCache.TasteProfileSnapshot snapshot = cache.loadTasteProfile(playerUuid);
    assertNotNull(snapshot);
    assertEquals(0.8, snapshot.affinities().get("daft punk"), 0.001);
    assertNotNull(snapshot.affinityTimestamps());
    assertEquals(now, snapshot.affinityTimestamps().get("daft punk"));
  }
}

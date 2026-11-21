package dev.vibesindev.vibesmusic.recommendation;

import static org.junit.jupiter.api.Assertions.*;

import dev.vibesindev.vibesmusic.model.Track;
import java.util.Collections;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class SignalTest {
  private Track makeTrack(String id, String title, String artist) {
    return new Track(id, title, artist, 180L, "http://example.com/audio.mp3");
  }

  @Test
  @DisplayName("Single 5s skip puts track in 24h cooldown without penalizing artist")
  void testSingleEarlySkipCooldownedWithoutArtistPenalty() {
    PlayerTasteProfile profile = new PlayerTasteProfile(UUID.randomUUID());
    Track track = makeTrack("t1", "Song A", "Artist X");

    // Set initial affinity
    profile.ingestSpotifyArtists(Collections.singletonList("Artist X"), Collections.emptyList());
    double initialAffinity = profile.getArtistAffinity("Artist X");
    assertTrue(initialAffinity > 0.0, "Initial affinity should be > 0");

    // Record start
    profile.recordStart(track);

    // Record 5s skip (< 10% / < 30s)
    profile.recordOutcome(
        track, 5_000L, 180_000L, RecommendationManager.Outcome.SKIPPED_EARLY, null);

    // Artist affinity should NOT be penalized on first skip
    double afterSkipAffinity = profile.getArtistAffinity("Artist X");
    assertEquals(
        initialAffinity, afterSkipAffinity, 0.001, "First early skip should not penalize artist");

    // Track should be fatigued under 24h cooldown
    assertTrue(profile.isFatigued(track, 20, 45 * 60 * 1000L), "Skipped track must be fatigued");
    // Also check by title/artist
    assertTrue(
        profile.isFatigued("other_id", "Song A", "Artist X", 20, 45 * 60 * 1000L),
        "Composition should be in cooldown");
  }

  @Test
  @DisplayName("Second early skip within 24h penalizes artist affinity by 10%")
  void testSecondEarlySkipPenalizesArtist() {
    PlayerTasteProfile profile = new PlayerTasteProfile(UUID.randomUUID());
    Track track1 = makeTrack("t1", "Song 1", "Artist Y");
    Track track2 = makeTrack("t2", "Song 2", "Artist Y");

    profile.ingestSpotifyArtists(Collections.singletonList("Artist Y"), Collections.emptyList());
    double initialAffinity = profile.getArtistAffinity("Artist Y");

    // 1st skip
    profile.recordStart(track1);
    profile.recordOutcome(
        track1, 5_000L, 180_000L, RecommendationManager.Outcome.SKIPPED_EARLY, null);
    assertEquals(initialAffinity, profile.getArtistAffinity("Artist Y"), 0.001);

    // 2nd skip within 24h
    profile.recordStart(track2);
    profile.recordOutcome(
        track2, 10_000L, 180_000L, RecommendationManager.Outcome.SKIPPED_EARLY, null);

    double expected = initialAffinity * 0.90;
    assertEquals(
        expected,
        profile.getArtistAffinity("Artist Y"),
        0.001,
        "Second early skip must penalize artist by 10%");
  }

  @Test
  @DisplayName(
      "100 completions monotonically increase affinity with diminishing returns, strictly < 1.0")
  void testCompletionsDiminishingReturns() {
    PlayerTasteProfile profile = new PlayerTasteProfile(UUID.randomUUID());
    Track track = makeTrack("t_dim", "Song Dim", "Artist Dim");

    double prev = 0.0;
    for (int i = 0; i < 100; i++) {
      profile.recordStart(track);
      profile.recordOutcome(
          track, 180_000L, 180_000L, RecommendationManager.Outcome.COMPLETED, null);
      double curr = profile.getArtistAffinity("Artist Dim");
      assertTrue(curr >= prev, "Affinity must be monotonically non-decreasing");
      assertTrue(curr < 1.0, "Affinity must remain strictly < 1.0");
      prev = curr;
    }

    // Even after 100 completions, affinity approaches 1.0 without overflowing
    assertTrue(prev > 0.99 && prev < 1.0, "Affinity should asymptotically approach 1.0");
  }

  @Test
  @DisplayName("Start + skip yields exactly 1 fatigue entry, not 2 duplicate entries")
  void testStartAndOutcomeProduceSingleFatigueEntry() {
    PlayerTasteProfile profile = new PlayerTasteProfile(UUID.randomUUID());
    Track track = makeTrack("t_single", "Single Song", "Single Artist");

    assertEquals(0, profile.getFatigueHistory().size());

    // Play starts
    profile.recordStart(track);
    assertEquals(1, profile.getFatigueHistory().size(), "Record start adds 1 fatigue entry");

    // Track is skipped or completed
    profile.recordOutcome(
        track, 10_000L, 180_000L, RecommendationManager.Outcome.SKIPPED_EARLY, null);
    assertEquals(
        1, profile.getFatigueHistory().size(), "Record outcome does NOT duplicate fatigue entry");
  }

  @Test
  @DisplayName("Manual stop outcome is neutral and does not alter affinity")
  void testManualStopIsNeutral() {
    PlayerTasteProfile profile = new PlayerTasteProfile(UUID.randomUUID());
    Track track = makeTrack("t_stop", "Stop Song", "Stop Artist");

    profile.ingestSpotifyArtists(Collections.singletonList("Stop Artist"), Collections.emptyList());
    double initial = profile.getArtistAffinity("Stop Artist");

    profile.recordStart(track);
    profile.recordOutcome(
        track, 15_000L, 180_000L, RecommendationManager.Outcome.MANUAL_STOP, null);

    assertEquals(
        initial,
        profile.getArtistAffinity("Stop Artist"),
        0.001,
        "MANUAL_STOP must not alter affinity");
  }
}

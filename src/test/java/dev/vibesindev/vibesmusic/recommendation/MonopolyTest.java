package dev.vibesindev.vibesmusic.recommendation;

import static org.junit.jupiter.api.Assertions.*;

import dev.vibesindev.vibesmusic.model.Track;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class MonopolyTest {
  private Track makeTrack(String id, String title, String artist) {
    return new Track(id, title, artist, 180L, "http://example.com/audio.mp3");
  }

  @Test
  @DisplayName("Monopoly multiplier table matches spec defaults: 1.00, 0.80, 0.40, 0.15")
  void testMonopolyMultiplierDefaults() {
    RecommendationEngine engine = new RecommendationEngine(null);

    assertEquals(1.00, engine.getMonopolyMultiplier(0), 0.001);
    assertEquals(0.80, engine.getMonopolyMultiplier(1), 0.001);
    assertEquals(0.40, engine.getMonopolyMultiplier(2), 0.001);
    assertEquals(0.15, engine.getMonopolyMultiplier(3), 0.001);
    assertEquals(0.15, engine.getMonopolyMultiplier(4), 0.001);
  }

  @Test
  @DisplayName(
      "getRecentArtistCount counts occurrences in 4-track window using ArtistNames.sameArtist")
  void testRecentArtistCountOccurrences() {
    PlayerTasteProfile profile = new PlayerTasteProfile(UUID.randomUUID());

    assertEquals(0, profile.getRecentArtistCount("Taylor Swift"));

    profile.recordStart(makeTrack("t1", "Cardigan", "Taylor Swift"));
    assertEquals(1, profile.getRecentArtistCount("Taylor Swift"));
    // Token collab match
    assertEquals(1, profile.getRecentArtistCount("Taylor Swift feat. Bon Iver"));

    profile.recordStart(makeTrack("t2", "Willow", "Taylor Swift"));
    assertEquals(2, profile.getRecentArtistCount("Taylor Swift"));

    profile.recordStart(makeTrack("t3", "Levitating", "Dua Lipa"));
    assertEquals(2, profile.getRecentArtistCount("Taylor Swift"));
    assertEquals(1, profile.getRecentArtistCount("Dua Lipa"));

    profile.recordStart(makeTrack("t4", "Anti-Hero", "Taylor Swift"));
    assertEquals(3, profile.getRecentArtistCount("Taylor Swift"));

    // 5th track pushes out oldest (t1: Cardigan by Taylor Swift)
    profile.recordStart(makeTrack("t5", "Blinding Lights", "The Weeknd"));
    // Last 4 tracks: t2 (Taylor Swift), t3 (Dua Lipa), t4 (Taylor Swift), t5 (The Weeknd)
    assertEquals(2, profile.getRecentArtistCount("Taylor Swift"));
    assertEquals(1, profile.getRecentArtistCount("The Weeknd"));
    assertEquals(0, profile.getRecentArtistCount("Unknown Artist"));
  }

  @Test
  @DisplayName("Candidate score receives 0.80x after 1 prior play and <= 0.15x after 3 prior plays")
  void testMonopolyCandidateScoring() {
    RecommendationEngine engine = new RecommendationEngine(null);
    Track seed = makeTrack("seed", "Seed Song", "Artist X");

    Track candidateTrack = makeTrack("c1", "Candidate Song", "Artist X");
    RecommendationCandidate candidate =
        new RecommendationCandidate(
            candidateTrack, 0.0, RecommendationCandidate.Source.SPOTIFY_ARTIST_TOP, "Top", false);

    // 1. Profile with 0 plays of Artist X
    PlayerTasteProfile p0 = new PlayerTasteProfile(UUID.randomUUID());
    p0.ingestSpotifyArtists(Collections.singletonList("Artist X"), Collections.emptyList());
    Track chosen0 = engine.scoreAndPickBest(List.of(candidate), seed, p0);
    assertNotNull(chosen0);

    // 2. Profile with 1 play of Artist X (seed was just played)
    PlayerTasteProfile p1 = new PlayerTasteProfile(UUID.randomUUID());
    p1.ingestSpotifyArtists(Collections.singletonList("Artist X"), Collections.emptyList());
    p1.recordStart(seed);
    assertEquals(1, p1.getRecentArtistCount("Artist X"));

    // 3. Profile with 3 plays of Artist X
    PlayerTasteProfile p3 = new PlayerTasteProfile(UUID.randomUUID());
    p3.ingestSpotifyArtists(Collections.singletonList("Artist X"), Collections.emptyList());
    p3.recordStart(makeTrack("t1", "Song 1", "Artist X"));
    p3.recordStart(makeTrack("t2", "Song 2", "Artist X"));
    p3.recordStart(makeTrack("t3", "Song 3", "Artist X"));
    assertEquals(3, p3.getRecentArtistCount("Artist X"));

    double mult1 = engine.getMonopolyMultiplier(p1.getRecentArtistCount("Artist X"));
    double mult3 = engine.getMonopolyMultiplier(p3.getRecentArtistCount("Artist X"));

    assertEquals(0.80, mult1, 0.001, "Candidate after 1 prior play must keep 80% multiplier");
    assertEquals(0.15, mult3, 0.001, "Candidate after 3 prior plays must drop to 15% multiplier");
  }
}

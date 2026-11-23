package dev.redbeastsam.vibesindev.recommendation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.redbeastsam.vibesindev.VibesInDevPlugin;
import dev.redbeastsam.vibesindev.model.Track;
import dev.redbeastsam.vibesindev.party.MusicParty;
import dev.redbeastsam.vibesindev.party.MusicPartyManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@SuppressWarnings("null")
public class LeastMiseryBlendTest {
  @Test
  @DisplayName(
      "Artist loved by A and hated by B scores lower than an artist both like moderately"
          + " (least-misery)")
  void testArtistLovedByAAndHatedByBScoresLowerThanModerateConsensus() {
    UUID playerA = UUID.randomUUID();
    UUID playerB = UUID.randomUUID();

    PlayerTasteProfile profileA = new PlayerTasteProfile(playerA);
    PlayerTasteProfile profileB = new PlayerTasteProfile(playerB);

    // Artist X: Polarized (Loved by A with 1.0, Hated/Unknown by B with 0.0)
    profileA.ingestSpotifyArtists(List.of("Artist Polarized"), null);

    // Artist Y: Consensus (Both like moderately with 0.50)
    // Set up directly via snapshot restoration for precise mathematical comparison
    profileA.restoreFromSnapshot(
        Map.of("artist polarized", 1.0, "artist consensus", 0.50),
        null,
        null,
        null,
        null,
        null,
        System.currentTimeMillis(),
        null);
    profileB.restoreFromSnapshot(
        Map.of("artist consensus", 0.50),
        null,
        null,
        null,
        null,
        null,
        System.currentTimeMillis(),
        null);

    UUID partyUuid = UUID.randomUUID();
    PlayerTasteProfile blend = PlayerTasteProfile.blend(partyUuid, List.of(profileA, profileB));
    blend.setMiseryLambda(0.5);

    double scorePolarized = blend.getArtistAffinity("artist polarized");
    double scoreConsensus = blend.getArtistAffinity("artist consensus");

    // Mathematical verification:
    // Polarized: mean = (1.0 + 0.0)/2 = 0.50, stddev = sqrt(((0.5)^2 + (-0.5)^2)/2) = 0.50
    // least-misery: 0.50 - 0.5 * 0.50 = 0.25
    // Consensus: mean = 0.50, stddev = 0.0
    // least-misery: 0.50 - 0.0 = 0.50
    assertEquals(0.25, scorePolarized, 0.01, "Polarized artist must receive least-misery penalty");
    assertEquals(
        0.50, scoreConsensus, 0.01, "Consensus artist has 0 stddev and receives full mean");
    assertTrue(
        scorePolarized < scoreConsensus,
        "Artist loved by A and hated by B must score lower than artist both like moderately");
  }

  @Test
  @DisplayName("Genre distribution blending is mean-based over normalized views")
  void testGenreDistributionBlendOverNormalizedViews() {
    UUID playerA = UUID.randomUUID();
    UUID playerB = UUID.randomUUID();

    PlayerTasteProfile profileA = new PlayerTasteProfile(playerA);
    PlayerTasteProfile profileB = new PlayerTasteProfile(playerB);

    // Player A has 10 tags of rock
    profileA.ingestArtistGenres(List.of("rock"), 10.0);
    // Player B has 1 tag of jazz
    profileB.ingestArtistGenres(List.of("jazz"), 1.0);

    // Blending over normalized views:
    // A's normalized distribution: {rock: 1.0}
    // B's normalized distribution: {jazz: 1.0}
    // Blend mean: rock = 0.5, jazz = 0.5
    PlayerTasteProfile blend = PlayerTasteProfile.blend(List.of(profileA, profileB));
    Map<String, Double> blendDist = blend.getGenreDistribution();

    assertNotNull(blendDist);
    assertEquals(0.50, blendDist.get("rock"), 0.01, "Rock should have 50% normalized share");
    assertEquals(
        0.50,
        blendDist.get("jazz"),
        0.01,
        "Jazz should have 50% normalized share despite fewer raw plays");
  }

  @Test
  @DisplayName("Rotate-leader in party mode rotates to individual member profiles on interval")
  void testRotateLeaderInPartyAutoplay() {
    VibesInDevPlugin plugin = mock(VibesInDevPlugin.class);
    MusicPartyManager partyManager = mock(MusicPartyManager.class);
    FileConfiguration config = mock(FileConfiguration.class);

    when(plugin.parties()).thenReturn(partyManager);
    when(plugin.getConfig()).thenReturn(config);
    when(config.getBoolean("recommendations.party.rotate-leader", false)).thenReturn(true);
    when(config.getInt("recommendations.party.rotate-leader-interval", 3)).thenReturn(3);
    when(config.getDouble("recommendations.party.misery-lambda", 0.5)).thenReturn(0.5);

    RecommendationManager recManager = new RecommendationManager(plugin);

    UUID playerA = UUID.randomUUID();
    UUID playerB = UUID.randomUUID();
    UUID partyId = UUID.randomUUID();

    MusicParty party = mock(MusicParty.class);
    when(party.getPartyId()).thenReturn(partyId);
    when(party.getMemberCount()).thenReturn(2);
    when(party.getMembers()).thenReturn(List.of(playerA, playerB));
    when(partyManager.getPartyByPlayer(playerA)).thenReturn(party);

    // Member profiles
    PlayerTasteProfile profileA = recManager.getOrCreateProfile(playerA);
    profileA.restoreFromSnapshot(
        Map.of("artist_a", 0.9), null, null, null, null, null, System.currentTimeMillis(), null);

    PlayerTasteProfile profileB = recManager.getOrCreateProfile(playerB);
    profileB.restoreFromSnapshot(
        Map.of("artist_b", 0.9), null, null, null, null, null, System.currentTimeMillis(), null);

    Track seed = new Track("seed_1", "Test Seed", "Artist Seed", 180, "http://seed");

    // Mock recommendation engine
    RecommendationEngine engine = mock(RecommendationEngine.class);
    recManager.setEngine(engine);

    // Pick 1: not 3rd pick -> blend
    recManager.getOrPredictNextRanked(playerA, seed);
    verify(engine, times(1))
        .recommendNextRanked(eq(seed), argThat(p -> !p.getSourceProfiles().isEmpty()), any());

    // Pick 2: not 3rd pick -> blend
    recManager.getOrPredictNextRanked(playerA, seed);
    verify(engine, times(2))
        .recommendNextRanked(eq(seed), argThat(p -> !p.getSourceProfiles().isEmpty()), any());

    // Pick 3: 3rd pick -> rotates to leader (individual member profile with empty sourceProfiles)
    recManager.getOrPredictNextRanked(playerA, seed);
    verify(engine, times(1))
        .recommendNextRanked(eq(seed), argThat(p -> p.getSourceProfiles().isEmpty()), any());
  }
}

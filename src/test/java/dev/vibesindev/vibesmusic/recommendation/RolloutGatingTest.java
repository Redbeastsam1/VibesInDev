package dev.vibesindev.vibesmusic.recommendation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.vibesindev.vibesmusic.VibesInDevPlugin;
import dev.vibesindev.vibesmusic.model.Track;
import java.util.List;
import java.util.UUID;
import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class RolloutGatingTest {
  private VibesInDevPlugin plugin;
  private FileConfiguration config;
  private RecommendationEngine engine;
  private RecommendationManager manager;

  @BeforeEach
  void setUp() {
    plugin = mock(VibesInDevPlugin.class);
    config = mock(FileConfiguration.class);

    when(plugin.getConfig()).thenReturn(config);
    when(config.getDouble(anyString(), anyDouble())).thenAnswer(inv -> inv.getArgument(1));
    when(config.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
    when(config.getLong(anyString(), anyLong())).thenAnswer(inv -> inv.getArgument(1));
    when(config.getBoolean(anyString(), anyBoolean())).thenAnswer(inv -> inv.getArgument(1));

    engine = new RecommendationEngine(plugin);
    when(plugin.recommendations()).thenReturn(manager);
  }

  @AfterEach
  void tearDown() {
    if (engine != null) {
      engine.shutdown();
    }
  }

  @Test
  @DisplayName("Rollout percent 0 and flag false serves legacy v1 to all players")
  void testRolloutZeroServesLegacy() {
    when(config.getBoolean("recommendations.engine-v2", false)).thenReturn(false);
    when(config.getInt("recommendations.engine-v2-rollout-percent", 0)).thenReturn(0);

    for (int i = 0; i < 50; i++) {
      UUID randomUuid = UUID.randomUUID();
      assertFalse(
          engine.isEngineV2Enabled(randomUuid),
          "Player " + randomUuid + " should NOT receive v2 when rollout is 0%");
    }
  }

  @Test
  @DisplayName(
      "Explicit engine-v2 flag true forces v2 for all players regardless of rollout percentage")
  void testExplicitFlagTrueForcesV2() {
    when(config.getBoolean("recommendations.engine-v2", false)).thenReturn(true);
    when(config.getInt("recommendations.engine-v2-rollout-percent", 0)).thenReturn(0);

    for (int i = 0; i < 50; i++) {
      UUID randomUuid = UUID.randomUUID();
      assertTrue(
          engine.isEngineV2Enabled(randomUuid),
          "Player " + randomUuid + " should receive v2 when flag is true");
    }
  }

  @Test
  @DisplayName("Staged rollout gates v2 based on floorMod(hashCode, 100)")
  void testStagedRolloutBuckets() {
    when(config.getBoolean("recommendations.engine-v2", false)).thenReturn(false);
    when(config.getInt("recommendations.engine-v2-rollout-percent", 0))
        .thenReturn(25); // 25% rollout

    UUID inBucketUuid = null;
    UUID outOfBucketUuid = null;

    // Find representative UUIDs for bucket < 25 and bucket >= 25
    for (int i = 0; i < 1000; i++) {
      UUID u = UUID.randomUUID();
      int bucket = Math.floorMod(u.hashCode(), 100);
      if (bucket < 25 && inBucketUuid == null) {
        inBucketUuid = u;
      } else if (bucket >= 25 && outOfBucketUuid == null) {
        outOfBucketUuid = u;
      }
      if (inBucketUuid != null && outOfBucketUuid != null) break;
    }

    assertNotNull(inBucketUuid);
    assertNotNull(outOfBucketUuid);

    assertTrue(engine.isEngineV2Enabled(inBucketUuid), "UUID in bucket < 25 must get v2");
    assertFalse(engine.isEngineV2Enabled(outOfBucketUuid), "UUID in bucket >= 25 must stay on v1");
  }

  @Test
  @DisplayName("Party rollout uses party UUID bucket rather than individual player UUID")
  void testPartyRolloutUsesPartyUuid() {
    when(config.getBoolean("recommendations.engine-v2", false)).thenReturn(false);
    when(config.getInt("recommendations.engine-v2-rollout-percent", 0)).thenReturn(50);

    UUID partyUuidInBucket = null;
    for (int i = 0; i < 1000; i++) {
      UUID u = UUID.randomUUID();
      if (Math.floorMod(u.hashCode(), 100) < 50) {
        partyUuidInBucket = u;
        break;
      }
    }
    assertNotNull(partyUuidInBucket);

    PlayerTasteProfile p1 = new PlayerTasteProfile(UUID.randomUUID());
    PlayerTasteProfile p2 = new PlayerTasteProfile(UUID.randomUUID());

    // Blend with partyUuidInBucket
    PlayerTasteProfile partyBlend = PlayerTasteProfile.blend(partyUuidInBucket, List.of(p1, p2));

    assertEquals(
        partyUuidInBucket, partyBlend.getPlayerUuid(), "Party blend profile must carry party UUID");
    assertTrue(
        engine.isEngineV2Enabled(partyBlend.getPlayerUuid()),
        "Party blend with bucket < 50 must get v2");
  }

  @Test
  @DisplayName("Explainability reason string contains mode, source, and top 2 contributing terms")
  void testExplainabilityReasonFormat() {
    double[] weights = new double[] {0.30, 0.20, 0.25, 0.05, 0.10, 0.10};

    String reason =
        RecommendationEngine.buildExplainabilityReason(
            RecommendationMode.SIMILAR,
            RecommendationCandidate.Source.YOUTUBE_RADIO,
            0.90,
            true, // genre contribution = 0.27
            0.20, // artist contribution = 0.04
            0.80, // continuity contribution = 0.20
            0.00, // familiarity contribution = 0.00
            1.00, // quality contribution = 0.10
            0.50, // novelty contribution = 0.05
            weights);

    assertNotNull(reason);
    assertTrue(reason.contains("SIMILAR mode via YOUTUBE_RADIO"), "Must contain mode and source");
    assertTrue(reason.contains("genre match"), "Must contain highest factor");
    assertTrue(reason.contains("vibe continuity"), "Must contain second highest factor");
  }

  @Test
  @DisplayName(
      "RecommendationManager correctly stores and retrieves lastAutoplayPick for player and party")
  void testLastAutoplayPickStorageAndRetrieval() {
    RecommendationManager recManager = new RecommendationManager(plugin);
    UUID playerUuid = UUID.randomUUID();
    UUID partyUuid = UUID.randomUUID();

    Track track = new Track("vid_1", "Test Song", "Test Artist", 180, "url");
    RankedPick pick =
        new RankedPick(
            track,
            RecommendationMode.SIMILAR,
            RecommendationCandidate.Source.YOUTUBE_RADIO,
            "Test reason",
            0.85);

    assertNull(recManager.getLastAutoplayPick(playerUuid));

    recManager.setLastAutoplayPick(playerUuid, pick);
    assertEquals(pick, recManager.getLastAutoplayPick(playerUuid));

    recManager.setLastAutoplayPick(partyUuid, pick);
    assertEquals(pick, recManager.getLastAutoplayPick(partyUuid));
  }
}

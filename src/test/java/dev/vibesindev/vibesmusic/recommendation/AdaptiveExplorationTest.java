package dev.vibesindev.vibesmusic.recommendation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Comparator;
import java.util.Random;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings("null")
public class AdaptiveExplorationTest {
  private Path tempDir;
  private String dbUrl;

  @BeforeEach
  public void setUp() throws Exception {
    tempDir = Files.createTempDirectory("adaptive_explore_test_");
    Path dbPath = tempDir.resolve("test_cache.db");
    dbUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath().toString();

    try (Connection conn = DriverManager.getConnection(dbUrl);
        Statement stmt = conn.createStatement()) {
      stmt.execute(
          "CREATE TABLE IF NOT EXISTS player_taste_profiles ("
              + "player_uuid TEXT PRIMARY KEY, "
              + "affinities_json TEXT, "
              + "genres_json TEXT, "
              + "dislikes_json TEXT, "
              + "lineage_json TEXT, "
              + "last_sync INTEGER, "
              + "genre_mass_json TEXT, "
              + "affinity_ts_json TEXT, "
              + "explore_ratio REAL DEFAULT 0.20"
              + ")");
    }
  }

  @AfterEach
  public void tearDown() throws Exception {
    if (tempDir != null && Files.exists(tempDir)) {
      Files.walk(tempDir).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
    }
  }

  @Test
  public void testDefaultAndClampingBounds() {
    PlayerTasteProfile profile = new PlayerTasteProfile(UUID.randomUUID());
    assertEquals(0.20, profile.getExploreRatio(), 1e-4);

    // Clamp upper bound to 0.40
    profile.setExploreRatio(0.85);
    assertEquals(0.40, profile.getExploreRatio(), 1e-4);

    // Clamp lower bound to 0.05
    profile.setExploreRatio(0.01);
    assertEquals(0.05, profile.getExploreRatio(), 1e-4);
  }

  @Test
  public void testGradualNudgeAfterMinOutcomesGate() {
    PlayerTasteProfile profile = new PlayerTasteProfile(UUID.randomUUID());
    profile.setExploreRatio(0.20);

    YamlConfiguration config = new YamlConfiguration();
    config.set("recommendations.exploration.min-outcomes", 10);
    config.set("recommendations.exploration.min", 0.05);
    config.set("recommendations.exploration.max", 0.40);

    // First 9 outcomes: gate not met -> no change in ratio
    for (int i = 0; i < 9; i++) {
      profile.recordExploreOutcome(RecommendationManager.Outcome.COMPLETED, config);
    }
    assertEquals(9, profile.getExploreOutcomesCount());
    assertEquals(0.20, profile.getExploreRatio(), 1e-4);

    // 10th outcome (completed): gate met -> nudges up to 0.21
    profile.recordExploreOutcome(RecommendationManager.Outcome.COMPLETED, config);
    assertEquals(10, profile.getExploreOutcomesCount());
    assertEquals(0.21, profile.getExploreRatio(), 1e-4);

    // 11th outcome (completed): nudges up to 0.22
    profile.recordExploreOutcome(RecommendationManager.Outcome.COMPLETED, config);
    assertEquals(11, profile.getExploreOutcomesCount());
    assertEquals(0.22, profile.getExploreRatio(), 1e-4);

    // 12th outcome (skipped early): nudges down to 0.21
    profile.recordExploreOutcome(RecommendationManager.Outcome.SKIPPED_EARLY, config);
    assertEquals(12, profile.getExploreOutcomesCount());
    assertEquals(0.21, profile.getExploreRatio(), 1e-4);
  }

  @Test
  public void testNonMeaningfulOutcomesAreIgnored() {
    PlayerTasteProfile profile = new PlayerTasteProfile(UUID.randomUUID());
    profile.setExploreRatio(0.20);
    profile.setExploreOutcomesCount(10); // already passed gate

    YamlConfiguration config = new YamlConfiguration();
    config.set("recommendations.exploration.min-outcomes", 10);

    profile.recordExploreOutcome(RecommendationManager.Outcome.AFK_OR_UNKNOWN, config);
    profile.recordExploreOutcome(RecommendationManager.Outcome.DISCONNECTED, config);
    profile.recordExploreOutcome(RecommendationManager.Outcome.MANUAL_STOP, config);
    profile.recordExploreOutcome(RecommendationManager.Outcome.REPEATED, config);
    profile.recordExploreOutcome(RecommendationManager.Outcome.REQUEUED, config);

    // Outcomes count and ratio remain unchanged
    assertEquals(10, profile.getExploreOutcomesCount());
    assertEquals(0.20, profile.getExploreRatio(), 1e-4);
  }

  @Test
  public void testSQLiteExploreRatioPersistence() throws Exception {
    UUID playerUuid = UUID.randomUUID();
    double customRatio = 0.32;

    try (Connection conn = DriverManager.getConnection(dbUrl)) {
      String insertSql =
          "INSERT INTO player_taste_profiles (player_uuid, affinities_json, genres_json,"
              + " dislikes_json, lineage_json, last_sync, genre_mass_json, affinity_ts_json,"
              + " explore_ratio) VALUES (?, '{}', '{}', '{}', '{}', ?, '{}', '{}', ?)";
      try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
        ps.setString(1, playerUuid.toString());
        ps.setLong(2, System.currentTimeMillis());
        ps.setDouble(3, customRatio);
        ps.executeUpdate();
      }

      String querySql = "SELECT explore_ratio FROM player_taste_profiles WHERE player_uuid = ?";
      try (PreparedStatement ps = conn.prepareStatement(querySql)) {
        ps.setString(1, playerUuid.toString());
        try (ResultSet rs = ps.executeQuery()) {
          assertTrue(rs.next());
          assertEquals(customRatio, rs.getDouble("explore_ratio"), 1e-4);
        }
      }
    }
  }

  @Test
  public void testWarmUpPreservationInModeSampling() {
    // Test that allowExplore is false during first 3 tracks
    Random rng = new Random(42);

    // When allowExplore is false, EXPLORE mode is never returned even if wExp > 0
    for (int i = 0; i < 20; i++) {
      RecommendationMode mode = RecommendationEngine.sampleMode(0.30, 0.50, 0.20, false, rng);
      assertNotEquals(
          RecommendationMode.EXPLORE, mode, "Explore mode must never be sampled during warm-up");
    }
  }
}

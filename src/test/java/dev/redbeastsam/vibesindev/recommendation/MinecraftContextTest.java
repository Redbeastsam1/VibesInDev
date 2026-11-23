package dev.redbeastsam.vibesindev.recommendation;

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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings("null")
public class MinecraftContextTest {
  private Path tempDir;
  private String dbUrl;

  @BeforeEach
  public void setUp() throws Exception {
    tempDir = Files.createTempDirectory("context_test_");
    Path dbPath = tempDir.resolve("test_features.db");
    dbUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath().toString();

    try (Connection conn = DriverManager.getConnection(dbUrl);
        Statement stmt = conn.createStatement()) {
      stmt.execute(
          "CREATE TABLE IF NOT EXISTS track_features ("
              + "track_id TEXT PRIMARY KEY, "
              + "loudness REAL, "
              + "dynamic_range REAL, "
              + "bpm REAL, "
              + "energy REAL, "
              + "computed_at INTEGER"
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
  public void testGenreEnergyEstimation() {
    assertEquals(
        0.85, RecommendationEngine.estimateEnergyFromGenre(List.of("death metal", "metalcore")));
    assertEquals(0.85, RecommendationEngine.estimateEnergyFromGenre(List.of("phonk")));
    assertEquals(0.85, RecommendationEngine.estimateEnergyFromGenre(List.of("drum and bass")));
    assertEquals(0.65, RecommendationEngine.estimateEnergyFromGenre(List.of("dance pop")));
    assertEquals(
        0.45, RecommendationEngine.estimateEnergyFromGenre(List.of("acoustic", "indie folk")));
    assertEquals(0.25, RecommendationEngine.estimateEnergyFromGenre(List.of("ambient", "drone")));
    assertEquals(
        0.25, RecommendationEngine.estimateEnergyFromGenre(List.of("lo-fi hip hop", "chillhop")));
    assertNull(RecommendationEngine.estimateEnergyFromGenre(null));
    assertNull(RecommendationEngine.estimateEnergyFromGenre(List.of()));
  }

  @Test
  public void testCombatDetectionWindow() {
    MinecraftContextSampler sampler = new MinecraftContextSampler(null);
    UUID playerUuid = UUID.randomUUID();

    assertFalse(sampler.isInCombat(playerUuid, 15_000L));

    sampler.recordCombat(playerUuid);
    assertTrue(sampler.isInCombat(playerUuid, 15_000L));
  }

  @Test
  public void testContextSnapshotDefaults() {
    MinecraftContextSampler.ContextSnapshot snapshot =
        MinecraftContextSampler.ContextSnapshot.NEUTRAL;
    assertEquals("OVERWORLD", snapshot.dimension());
    assertFalse(snapshot.inCombat());
    assertFalse(snapshot.isUnderground());
    assertEquals(0.50, snapshot.targetEnergy(), 1e-4);
    assertEquals("neutral", snapshot.moodHint());
  }

  @Test
  public void testTrackFeaturesSQLitePersistence() throws Exception {
    String trackId = "test_track_123";
    double loudness = -8.5;
    double dynRange = 6.2;
    double bpm = 128.0;
    double energy = 0.82;
    long now = System.currentTimeMillis();

    try (Connection conn = DriverManager.getConnection(dbUrl)) {
      String insertSql =
          "INSERT INTO track_features (track_id, loudness, dynamic_range, bpm, energy, computed_at)"
              + " VALUES (?, ?, ?, ?, ?, ?)";
      try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
        ps.setString(1, trackId);
        ps.setDouble(2, loudness);
        ps.setDouble(3, dynRange);
        ps.setDouble(4, bpm);
        ps.setDouble(5, energy);
        ps.setLong(6, now);
        ps.executeUpdate();
      }

      String selectSql =
          "SELECT loudness, dynamic_range, bpm, energy FROM track_features WHERE track_id = ?";
      try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
        ps.setString(1, trackId);
        try (ResultSet rs = ps.executeQuery()) {
          assertTrue(rs.next());
          assertEquals(loudness, rs.getDouble("loudness"), 0.01);
          assertEquals(dynRange, rs.getDouble("dynamic_range"), 0.01);
          assertEquals(bpm, rs.getDouble("bpm"), 0.01);
          assertEquals(energy, rs.getDouble("energy"), 0.01);
        }
      }
    }
  }
}

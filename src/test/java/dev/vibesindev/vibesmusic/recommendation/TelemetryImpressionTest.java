package dev.vibesindev.vibesmusic.recommendation;

import static org.junit.jupiter.api.Assertions.*;

import dev.vibesindev.vibesmusic.model.Track;
import dev.vibesindev.vibesmusic.recommendation.CandidateSelector.MmrCandidate;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings("null")
public class TelemetryImpressionTest {
  private Path tempDir;
  private String dbUrl;

  @BeforeEach
  public void setUp() throws Exception {
    tempDir = Files.createTempDirectory("telemetry_impr_test_");
    Path dbPath = tempDir.resolve("test_impr.db");
    dbUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath().toString();

    try (Connection conn = DriverManager.getConnection(dbUrl);
        Statement stmt = conn.createStatement()) {
      stmt.execute(
          "CREATE TABLE IF NOT EXISTS rec_impressions ("
              + "impression_id TEXT PRIMARY KEY, "
              + "ts INTEGER NOT NULL, "
              + "player_uuid TEXT, "
              + "party_uuid TEXT, "
              + "seed_track_id TEXT, "
              + "engine_version TEXT NOT NULL, "
              + "model_version TEXT, "
              + "requested_mode TEXT, "
              + "effective_mode TEXT, "
              + "mode_probability REAL, "
              + "mode_fallbacks_json TEXT, "
              + "relaxation_level INTEGER DEFAULT 0, "
              + "raw_candidates INTEGER, "
              + "eligible_candidates INTEGER, "
              + "rejection_counts_json TEXT, "
              + "provider_status_json TEXT, "
              + "latency_ms INTEGER, "
              + "selected_track_id TEXT, "
              + "completion REAL, "
              + "outcome TEXT"
              + ")");
      stmt.execute(
          "CREATE INDEX IF NOT EXISTS idx_rec_impr_player_ts ON rec_impressions(player_uuid, ts);");
      stmt.execute(
          "CREATE TABLE IF NOT EXISTS rec_candidates ("
              + "impression_id TEXT NOT NULL, "
              + "track_id TEXT NOT NULL, "
              + "rank INTEGER, "
              + "source TEXT, "
              + "score REAL, "
              + "heuristic_score REAL, "
              + "learned_score REAL, "
              + "selection_probability REAL, "
              + "selected INTEGER NOT NULL DEFAULT 0, "
              + "features_json TEXT, "
              + "PRIMARY KEY (impression_id, track_id)"
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
  public void testSoftmaxProbabilitiesSumToOne() {
    List<MmrCandidate> mmrList = new ArrayList<>();
    mmrList.add(
        new MmrCandidate(
            new RecommendationCandidate(
                new Track("1", "T1", "A1", 200, "u1"),
                0.9,
                RecommendationCandidate.Source.SPOTIFY_ARTIST_TOP,
                "r1",
                false),
            0.9));
    mmrList.add(
        new MmrCandidate(
            new RecommendationCandidate(
                new Track("2", "T2", "A2", 200, "u2"),
                0.7,
                RecommendationCandidate.Source.SPOTIFY_GENRE,
                "r2",
                false),
            0.7));
    mmrList.add(
        new MmrCandidate(
            new RecommendationCandidate(
                new Track("3", "T3", "A3", 200, "u3"),
                0.5,
                RecommendationCandidate.Source.YOUTUBE_RADIO,
                "r3",
                false),
            0.5));

    double[] probs = CandidateSelector.computeSoftmaxProbabilities(mmrList, 0.08);
    assertEquals(3, probs.length);

    double sum = 0.0;
    for (double p : probs) {
      sum += p;
    }
    assertEquals(1.0, sum, 1e-6);

    // Highest score must have highest selection probability
    assertTrue(probs[0] > probs[1]);
    assertTrue(probs[1] > probs[2]);
  }

  @Test
  public void testMarginalSelectionPropensities() {
    double effectiveModeProb = 0.50; // SIMILAR mode mass
    List<MmrCandidate> mmrList = new ArrayList<>();
    mmrList.add(
        new MmrCandidate(
            new RecommendationCandidate(
                new Track("1", "T1", "A1", 200, "u1"),
                0.8,
                RecommendationCandidate.Source.SPOTIFY_GENRE,
                "r1",
                false),
            0.8));
    mmrList.add(
        new MmrCandidate(
            new RecommendationCandidate(
                new Track("2", "T2", "A2", 200, "u2"),
                0.8,
                RecommendationCandidate.Source.SPOTIFY_GENRE,
                "r2",
                false),
            0.8));

    double[] probsInMode = CandidateSelector.computeSoftmaxProbabilities(mmrList, 0.1);
    assertEquals(0.5, probsInMode[0], 1e-4);
    assertEquals(0.5, probsInMode[1], 1e-4);

    double marginal1 = effectiveModeProb * probsInMode[0];
    double marginal2 = effectiveModeProb * probsInMode[1];

    assertEquals(0.25, marginal1, 1e-4);
    assertEquals(0.25, marginal2, 1e-4);
    assertEquals(effectiveModeProb, marginal1 + marginal2, 1e-4);
  }

  @Test
  public void testImpressionRetentionPruning() throws Exception {
    long now = System.currentTimeMillis();
    long oldTs = now - (35L * 86_400_000L); // 35 days ago (should be pruned)
    long recentTs = now - (5L * 86_400_000L); // 5 days ago (should stay)

    try (Connection conn = DriverManager.getConnection(dbUrl)) {
      try (PreparedStatement ps =
          conn.prepareStatement(
              "INSERT INTO rec_impressions (impression_id, ts, engine_version) VALUES (?, ?, ?)")) {
        ps.setString(1, "old_impr");
        ps.setLong(2, oldTs);
        ps.setString(3, "v2");
        ps.executeUpdate();

        ps.setString(1, "recent_impr");
        ps.setLong(2, recentTs);
        ps.setString(3, "v2");
        ps.executeUpdate();
      }

      try (PreparedStatement ps =
          conn.prepareStatement(
              "INSERT INTO rec_candidates (impression_id, track_id, rank, source, score) VALUES (?,"
                  + " ?, ?, ?, ?)")) {
        ps.setString(1, "old_impr");
        ps.setString(2, "old_track");
        ps.setInt(3, 1);
        ps.setString(4, "STREAM_B");
        ps.setDouble(5, 0.8);
        ps.executeUpdate();

        ps.setString(1, "recent_impr");
        ps.setString(2, "recent_track");
        ps.setInt(3, 1);
        ps.setString(4, "STREAM_B");
        ps.setDouble(5, 0.8);
        ps.executeUpdate();
      }

      // Prune with 30-day retention
      long cutoff = now - (30L * 86_400_000L);
      try (PreparedStatement ps =
          conn.prepareStatement("DELETE FROM rec_impressions WHERE ts < ?")) {
        ps.setLong(1, cutoff);
        ps.executeUpdate();
      }
      try (Statement stmt = conn.createStatement()) {
        stmt.execute(
            "DELETE FROM rec_candidates WHERE impression_id NOT IN (SELECT impression_id FROM"
                + " rec_impressions)");
      }

      // Verify old is deleted, recent remains
      try (Statement stmt = conn.createStatement();
          ResultSet rs = stmt.executeQuery("SELECT count(*) FROM rec_impressions")) {
        assertTrue(rs.next());
        assertEquals(1, rs.getInt(1));
      }
      try (Statement stmt = conn.createStatement();
          ResultSet rs = stmt.executeQuery("SELECT count(*) FROM rec_candidates")) {
        assertTrue(rs.next());
        assertEquals(1, rs.getInt(1));
      }
      try (Statement stmt = conn.createStatement();
          ResultSet rs = stmt.executeQuery("SELECT track_id FROM rec_candidates")) {
        assertTrue(rs.next());
        assertEquals("recent_track", rs.getString(1));
      }
    }
  }

  @Test
  public void testOutcomeUpdate() throws Exception {
    String imprId = "impr_test_outcome";
    try (Connection conn = DriverManager.getConnection(dbUrl)) {
      try (PreparedStatement ps =
          conn.prepareStatement(
              "INSERT INTO rec_impressions (impression_id, ts, engine_version) VALUES (?, ?, ?)")) {
        ps.setString(1, imprId);
        ps.setLong(2, System.currentTimeMillis());
        ps.setString(3, "v2");
        ps.executeUpdate();
      }

      try (PreparedStatement ps =
          conn.prepareStatement(
              "UPDATE rec_impressions SET completion = ?, outcome = ? WHERE impression_id = ?")) {
        ps.setDouble(1, 0.92);
        ps.setString(2, "COMPLETED");
        ps.setString(3, imprId);
        ps.executeUpdate();
      }

      try (PreparedStatement ps =
          conn.prepareStatement(
              "SELECT completion, outcome FROM rec_impressions WHERE impression_id = ?")) {
        ps.setString(1, imprId);
        try (ResultSet rs = ps.executeQuery()) {
          assertTrue(rs.next());
          assertEquals(0.92, rs.getDouble("completion"), 0.01);
          assertEquals("COMPLETED", rs.getString("outcome"));
        }
      }
    }
  }
}

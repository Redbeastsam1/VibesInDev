package dev.vibesindev.vibesmusic.recommendation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Comparator;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("null")
public class TelemetryTest {

    private Path tempDir;
    private String dbUrl;

    @BeforeEach
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("telemetry_test_");
        Path dbPath = tempDir.resolve("test_metadata.db");
        dbUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath().toString();

        try (Connection conn = DriverManager.getConnection(dbUrl); Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS rec_impressions (" +
                    "impression_id TEXT PRIMARY KEY, " +
                    "ts INTEGER NOT NULL, " +
                    "player_uuid TEXT NOT NULL, " +
                    "party_uuid TEXT, " +
                    "seed_track_id TEXT, " +
                    "engine_version TEXT NOT NULL, " +
                    "model_version TEXT, " +
                    "requested_mode TEXT, " +
                    "effective_mode TEXT, " +
                    "mode_probability REAL, " +
                    "mode_fallbacks_json TEXT, " +
                    "relaxation_level INTEGER DEFAULT 0, " +
                    "raw_candidates INTEGER, " +
                    "eligible_candidates INTEGER, " +
                    "rejection_counts_json TEXT, " +
                    "provider_status_json TEXT, " +
                    "latency_ms INTEGER, " +
                    "selected_track_id TEXT, " +
                    "completion REAL, " +
                    "outcome TEXT" +
                    ")");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_rec_impr_player_ts ON rec_impressions(player_uuid, ts);");
            stmt.execute("CREATE TABLE IF NOT EXISTS rec_candidates (" +
                    "impression_id TEXT NOT NULL, " +
                    "track_id TEXT NOT NULL, " +
                    "rank INTEGER, " +
                    "source TEXT, " +
                    "score REAL, " +
                    "heuristic_score REAL, " +
                    "learned_score REAL, " +
                    "selection_probability REAL, " +
                    "selected INTEGER NOT NULL DEFAULT 0, " +
                    "features_json TEXT, " +
                    "PRIMARY KEY (impression_id, track_id)" +
                    ")");
        }
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }

    @Test
    public void testImpressionAndCandidateLogging() throws Exception {
        String imprId = UUID.randomUUID().toString();
        UUID playerUuid = UUID.randomUUID();
        long now = System.currentTimeMillis();

        String insertImpr = "INSERT INTO rec_impressions (impression_id, ts, player_uuid, engine_version, raw_candidates, eligible_candidates, selected_track_id) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = DriverManager.getConnection(dbUrl); PreparedStatement ps = conn.prepareStatement(insertImpr)) {
            ps.setString(1, imprId);
            ps.setLong(2, now);
            ps.setString(3, playerUuid.toString());
            ps.setString(4, "v1");
            ps.setInt(5, 40);
            ps.setInt(6, 12);
            ps.setString(7, "track_selected");
            ps.executeUpdate();
        }

        String insertCand = "INSERT INTO rec_candidates (impression_id, track_id, rank, source, score, heuristic_score, selection_probability, selected, features_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = DriverManager.getConnection(dbUrl); PreparedStatement ps = conn.prepareStatement(insertCand)) {
            ps.setString(1, imprId);
            ps.setString(2, "track_selected");
            ps.setInt(3, 1);
            ps.setString(4, "YOUTUBE_RADIO");
            ps.setDouble(5, 0.88);
            ps.setDouble(6, 0.88);
            ps.setDouble(7, 1.0);
            ps.setInt(8, 1);
            ps.setString(9, "{}");
            ps.executeUpdate();
        }

        // Verify row exists with engine_version v1
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement ps = conn.prepareStatement("SELECT engine_version, selected_track_id FROM rec_impressions WHERE impression_id = ?")) {
            ps.setString(1, imprId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("v1", rs.getString("engine_version"));
                assertEquals("track_selected", rs.getString("selected_track_id"));
            }
        }

        // Test updating outcome
        String updateOutcome = "UPDATE rec_impressions SET completion = ?, outcome = ? WHERE impression_id = ?";
        try (Connection conn = DriverManager.getConnection(dbUrl); PreparedStatement ps = conn.prepareStatement(updateOutcome)) {
            ps.setDouble(1, 0.95);
            ps.setString(2, "COMPLETED");
            ps.setString(3, imprId);
            ps.executeUpdate();
        }

        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement ps = conn.prepareStatement("SELECT completion, outcome FROM rec_impressions WHERE impression_id = ?")) {
            ps.setString(1, imprId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(0.95, rs.getDouble("completion"), 0.001);
                assertEquals("COMPLETED", rs.getString("outcome"));
            }
        }
    }
}

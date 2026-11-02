package dev.vibesindev.vibesmusic.recommendation;

import dev.vibesindev.vibesmusic.model.Track;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class LearnedRankerTest {

    private Path tempDir;

    @BeforeEach
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("learned_ranker_test_");
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
    public void testKnownProbabilitiesFromWeightsJson() {
        LearnedRanker ranker = new LearnedRanker(true, 0.5);
        String json = """
                {
                  "version": "v1.0-test",
                  "intercept": 0.0,
                  "weights": {
                    "genre_match": 1.0,
                    "artist_affinity": -1.0
                  }
                }
                """;

        boolean loaded = ranker.loadFromJson(json);
        assertTrue(loaded);
        assertTrue(ranker.isLoaded());
        assertEquals("v1.0-test", ranker.getVersion());
        assertEquals(0.0, ranker.getIntercept(), 1e-6);

        // Case 1: z = 0.0 + 1.0*1.0 + (-1.0)*1.0 = 0.0 -> p = 0.5
        Map<String, Double> feats1 = Map.of("genre_match", 1.0, "artist_affinity", 1.0);
        double p1 = ranker.computeLearnedScore(feats1);
        assertEquals(0.50, p1, 1e-6);

        // Case 2: z = 0.0 + 1.0*1.0 + (-1.0)*0.0 = 1.0 -> p = 1 / (1 + e^-1) = 0.73105857863
        Map<String, Double> feats2 = Map.of("genre_match", 1.0, "artist_affinity", 0.0);
        double p2 = ranker.computeLearnedScore(feats2);
        double expected = 1.0 / (1.0 + Math.exp(-1.0));
        assertEquals(expected, p2, 1e-5);
    }

    @Test
    public void testMissingAndCorruptFileFallback() {
        LearnedRanker ranker = new LearnedRanker();

        // 1. Missing file
        File missingFile = tempDir.resolve("non_existent.json").toFile();
        assertFalse(ranker.loadFromFile(missingFile));
        assertFalse(ranker.isLoaded());

        // 2. Corrupted file
        File corruptFile = tempDir.resolve("corrupt.json").toFile();
        try {
            Files.writeString(corruptFile.toPath(), "{ not_valid_json: ");
        } catch (IOException e) {
            fail(e);
        }
        assertFalse(ranker.loadFromFile(corruptFile));
        assertFalse(ranker.isLoaded());

        // Baseline score fallback
        double score = ranker.blend(0.85, 0.20);
        assertEquals(0.85, score, 1e-6);
    }

    @Test
    public void testShadowModeAndActiveBlending() {
        LearnedRanker ranker = new LearnedRanker(true, 0.5); // shadow mode = true
        ranker.loadFromJson("""
                {
                  "version": "1.0",
                  "intercept": 0.5,
                  "weights": { "quality": 0.5 }
                }
                """);
        assertTrue(ranker.isLoaded());

        // In shadow mode, blend always serves heuristic
        double heuristic = 0.70;
        double learned = 0.30;
        assertEquals(heuristic, ranker.blend(heuristic, learned), 1e-6);

        // Turn shadow mode OFF: blends alpha * learned + (1 - alpha) * heuristic
        ranker.setShadowMode(false);
        ranker.setBlendAlpha(0.60);
        // 0.60 * 0.30 + 0.40 * 0.70 = 0.18 + 0.28 = 0.46
        double expected = (0.60 * learned) + (0.40 * heuristic);
        assertEquals(expected, ranker.blend(heuristic, learned), 1e-6);
    }

    @Test
    public void testFitScriptRefusalOnSmallData() throws Exception {
        Path dbPath = tempDir.resolve("test_sparse.db");
        String dbUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath().toString();

        try (Connection conn = DriverManager.getConnection(dbUrl); Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE rec_impressions (" +
                    "impression_id TEXT PRIMARY KEY, ts INTEGER, outcome TEXT, completion REAL)");
            stmt.execute("CREATE TABLE rec_candidates (" +
                    "impression_id TEXT, selected INTEGER, selection_probability REAL, features_json TEXT)");

            // Insert 50 rows (far below 2000 required minimum)
            try (PreparedStatement ps1 = conn.prepareStatement("INSERT INTO rec_impressions VALUES (?, ?, ?, ?)");
                 PreparedStatement ps2 = conn.prepareStatement("INSERT INTO rec_candidates VALUES (?, ?, ?, ?)")) {
                for (int i = 0; i < 50; i++) {
                    String id = "impr_" + i;
                    ps1.setString(1, id);
                    ps1.setLong(2, System.currentTimeMillis() + i * 1000);
                    ps1.setString(3, i % 2 == 0 ? "COMPLETED" : "SKIPPED_EARLY");
                    ps1.setDouble(4, i % 2 == 0 ? 1.0 : 0.1);
                    ps1.executeUpdate();

                    ps2.setString(1, id);
                    ps2.setInt(2, 1);
                    ps2.setDouble(3, 0.25);
                    ps2.setString(4, "{\"genre_match\": 0.8, \"quality\": 0.9}");
                    ps2.executeUpdate();
                }
            }
        }

        File scriptFile = new File("tools/fit_ranker.py");
        assertTrue(scriptFile.exists(), "tools/fit_ranker.py must exist");

        ProcessBuilder pb = new ProcessBuilder("python", scriptFile.getAbsolutePath(),
                "--db", dbPath.toAbsolutePath().toString(),
                "--out", tempDir.resolve("weights.json").toAbsolutePath().toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes());
        int exitCode = p.waitFor();

        assertEquals(2, exitCode, "Script must exit with code 2 when minimum data gate is not met. Output: " + output);
        assertTrue(output.contains("Minimum Data Gate Not Met"), "Output must contain gate refusal explanation: " + output);
        assertFalse(Files.exists(tempDir.resolve("weights.json")), "Weights file must NOT be written when gate fails.");
    }
}

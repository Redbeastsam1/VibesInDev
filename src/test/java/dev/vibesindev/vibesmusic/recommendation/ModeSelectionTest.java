package dev.vibesindev.vibesmusic.recommendation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

@SuppressWarnings("null")
public class ModeSelectionTest {
  @Test
  public void testSampleModeWithoutExploreDuringEarlySession() {
    Random rng = new Random(42);
    // During first 3 tracks, allowExplore = false
    for (int i = 0; i < 100; i++) {
      RecommendationMode mode = RecommendationEngine.sampleMode(0.30, 0.50, 0.20, false, rng);
      assertNotEquals(
          RecommendationMode.EXPLORE, mode, "Early session should not sample EXPLORE mode");
      assertTrue(mode == RecommendationMode.FAMILIAR || mode == RecommendationMode.SIMILAR);
    }
  }

  @Test
  public void testSampleModeWithExplore() {
    Random rng = new Random(42);
    boolean sawFamiliar = false;
    boolean sawSimilar = false;
    boolean sawExplore = false;

    for (int i = 0; i < 500; i++) {
      RecommendationMode mode = RecommendationEngine.sampleMode(0.30, 0.50, 0.20, true, rng);
      if (mode == RecommendationMode.FAMILIAR) sawFamiliar = true;
      if (mode == RecommendationMode.SIMILAR) sawSimilar = true;
      if (mode == RecommendationMode.EXPLORE) sawExplore = true;
    }

    assertTrue(
        sawFamiliar && sawSimilar && sawExplore, "All modes should be sampled over 500 trials");
  }

  @Test
  public void testModeWeightsSumToOne() {
    for (RecommendationMode mode : RecommendationMode.values()) {
      double[] weights = RecommendationEngine.getModeWeights(mode, null);
      assertEquals(6, weights.length);
      double sum = 0.0;
      for (double w : weights) sum += w;
      assertEquals(1.0, sum, 1e-6, mode.name() + " weights must sum to 1.0");
    }
  }

  @Test
  public void testCalculateCandidateScoreRenormalizesMissingFeatures() {
    double[] similarWeights = RecommendationEngine.getModeWeights(RecommendationMode.SIMILAR, null);
    // [genre: 0.30, artist: 0.20, continuity: 0.25, familiarity: 0.05, quality: 0.10, novelty:
    // 0.10]

    // Candidate with NO genre data (hasGenre = false)
    double scoreNoGenre =
        RecommendationEngine.calculateCandidateScore(
            -1.0,
            false,
            0.80, // artist
            0.70, // continuity
            0.50, // familiarity
            1.00, // quality
            0.20, // novelty
            similarWeights,
            0.35, // min coverage
            1.00, // monopoly
            false,
            0.02);

    assertTrue(scoreNoGenre > 0.0, "Score with missing genre must be valid and renormalized");
  }

  @Test
  public void testInsufficientFeatureCoverageRejectsCandidate() {
    // Candidate with only 1 feature (e.g. only quality available, all else weight=0 or unavailable)
    double[] strictWeights = new double[] {0.80, 0.0, 0.0, 0.0, 0.20, 0.0};

    double score =
        RecommendationEngine.calculateCandidateScore(
            -1.0,
            false, // genre missing (80% weight missing)
            0.0,
            0.0,
            0.0,
            1.0, // quality (only 20% coverage)
            0.0,
            strictWeights,
            0.35, // requires 35%
            1.0,
            false,
            0.02);

    assertEquals(-1.0, score, "Candidate below min feature coverage must be rejected with -1.0");
  }
}

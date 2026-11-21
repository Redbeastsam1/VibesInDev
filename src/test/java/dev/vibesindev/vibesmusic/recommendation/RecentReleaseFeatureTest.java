package dev.vibesindev.vibesmusic.recommendation;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@SuppressWarnings("null")
public class RecentReleaseFeatureTest {
  @Test
  @DisplayName("Task 4.3: Extract release year from titles and version metadata")
  void testExtractReleaseYear() {
    assertEquals(
        2025,
        RecommendationEngine.extractReleaseYear(
            "Secret Society - Neoperreo feat. ALIC3E (Single 2025)"));
    assertEquals(
        2024, RecommendationEngine.extractReleaseYear("Artist - Song (Official Video 2024)"));
    assertEquals(
        2001, RecommendationEngine.extractReleaseYear("Daft Punk - One More Time [2001 Remaster]"));
    assertEquals(1986, RecommendationEngine.extractReleaseYear("Queen - Live at Wembley (1986)"));
    assertEquals(
        0, RecommendationEngine.extractReleaseYear("Daft Punk - Harder Better Faster Stronger"));
    assertEquals(0, RecommendationEngine.extractReleaseYear(null));
    assertEquals(0, RecommendationEngine.extractReleaseYear(""));
  }

  @Test
  @DisplayName("Task 4.3: Calculate recent release score with reference year")
  void testCalculateRecentReleaseScore() {
    int refYear = 2026;
    // Age <= 1: 1.0
    assertEquals(1.0, RecommendationEngine.calculateRecentReleaseScore(2025, refYear), 0.001);
    assertEquals(1.0, RecommendationEngine.calculateRecentReleaseScore(2026, refYear), 0.001);

    // Age 2: 0.85
    assertEquals(0.85, RecommendationEngine.calculateRecentReleaseScore(2024, refYear), 0.001);

    // Age 5: 0.65
    assertEquals(0.65, RecommendationEngine.calculateRecentReleaseScore(2022, refYear), 0.001);

    // Old release: 0.05
    assertEquals(0.05, RecommendationEngine.calculateRecentReleaseScore(1990, refYear), 0.001);

    // Invalid / out of bounds (< 1950)
    assertEquals(0.0, RecommendationEngine.calculateRecentReleaseScore(1920, refYear), 0.001);
  }

  @Test
  @DisplayName("Task 4.3: Candidate score renormalizes when 7th feature (recentRelease) is missing")
  void testScoreRenormalizationWithMissingRecentRelease() {
    double[] weights = new double[] {0.20, 0.05, 0.25, 0.00, 0.15, 0.30, 0.05};

    // Candidate with NO recent release data (hasRecentRelease = false)
    double scoreNoRecent =
        RecommendationEngine.calculateCandidateScore(
            0.80, true, 0.50, 0.70, 0.00, 0.90, 0.80, 0.0, false, weights, 0.35, 1.0, false, 0.0);
    assertTrue(scoreNoRecent > 0.0);

    // Candidate with recent release data (hasRecentRelease = true, recentRelease = 1.0)
    double scoreWithRecent =
        RecommendationEngine.calculateCandidateScore(
            0.80, true, 0.50, 0.70, 0.00, 0.90, 0.80, 1.0, true, weights, 0.35, 1.0, false, 0.0);
    assertTrue(scoreWithRecent >= scoreNoRecent);
  }
}

package dev.redbeastsam.vibesindev.recommendation;

import java.util.Map;

/**
 * Extension point for scoring candidate tracks. Provides abstraction for heuristic,
 * offline-learned, and future contextual ranking models.
 */
public interface CandidateRanker {
  /**
   * Calculates ranking score for a candidate given its extracted feature vector and target mode.
   *
   * @param candidate candidate track metadata and provenance
   * @param features normalized feature vector [0, 1]
   * @param mode effective recommendation mode (FAMILIAR, SIMILAR, EXPLORE)
   * @return candidate score in [0.0, 1.0]
   */
  double score(
      RecommendationCandidate candidate, Map<String, Double> features, RecommendationMode mode);
}

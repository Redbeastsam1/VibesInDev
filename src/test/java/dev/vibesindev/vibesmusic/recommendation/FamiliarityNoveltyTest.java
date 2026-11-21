package dev.vibesindev.vibesmusic.recommendation;

import static org.junit.jupiter.api.Assertions.*;

import dev.vibesindev.vibesmusic.model.Track;
import org.junit.jupiter.api.Test;

public class FamiliarityNoveltyTest {
  @Test
  public void testFamiliarityHierarchy() {
    Track dummy = new Track("id", "Title", "Artist", 200, "url");
    RecommendationCandidate cand =
        new RecommendationCandidate(
            dummy, 0.0, RecommendationCandidate.Source.YOUTUBE_RADIO, "Radio", false);
    RecommendationCandidate familiarCand =
        new RecommendationCandidate(
            dummy, 0.0, RecommendationCandidate.Source.FAMILIAR, "Library", false);

    // 1. Exact liked/saved -> 1.0
    assertEquals(
        1.0, RecommendationEngine.calculateFamiliarity(cand, 0.2, 0.1, true, false, false));
    // Or Stream E candidate -> 1.0
    assertEquals(
        1.0,
        RecommendationEngine.calculateFamiliarity(familiarCand, 0.2, 0.1, false, false, false));

    // 2. Previously completed -> 0.9
    assertEquals(
        0.9, RecommendationEngine.calculateFamiliarity(cand, 0.2, 0.1, false, true, false));

    // 3. Spotify top/recent -> 0.8
    assertEquals(
        0.8, RecommendationEngine.calculateFamiliarity(cand, 0.2, 0.1, false, false, true));

    // 4. Frequently played artist (affinity >= 0.5) but unheard track -> 0.7
    assertEquals(
        0.7, RecommendationEngine.calculateFamiliarity(cand, 0.6, 0.1, false, false, false));

    // 5. Familiar genre with unfamiliar artist (genre >= 0.2, artist < 0.5) -> 0.4
    assertEquals(
        0.4, RecommendationEngine.calculateFamiliarity(cand, 0.1, 0.3, false, false, false));

    // 6. Unknown -> 0.0
    assertEquals(
        0.0, RecommendationEngine.calculateFamiliarity(cand, 0.1, 0.05, false, false, false));
  }

  @Test
  public void testNoveltyComputation() {
    // High familiarity / affinity -> zero novelty
    assertEquals(0.0, RecommendationEngine.calculateNovelty(1.0, 0.8), 1e-6);

    // Complete unknown -> 1.0 novelty
    assertEquals(1.0, RecommendationEngine.calculateNovelty(0.0, 0.0), 1e-6);

    // Familiar genre only (fam=0.4, aff=0.1) -> novelty = 1.0 - 0.4 = 0.6
    assertEquals(0.6, RecommendationEngine.calculateNovelty(0.4, 0.1), 1e-6);

    // Known artist, unknown song (fam=0.7, aff=0.7) -> novelty = 1.0 - 0.7 = 0.3
    assertEquals(0.3, RecommendationEngine.calculateNovelty(0.7, 0.7), 1e-6);

    // Boundary clamping
    assertEquals(0.0, RecommendationEngine.calculateNovelty(1.5, 0.5), 1e-6);
    assertEquals(1.0, RecommendationEngine.calculateNovelty(-0.5, -0.2), 1e-6);
  }
}

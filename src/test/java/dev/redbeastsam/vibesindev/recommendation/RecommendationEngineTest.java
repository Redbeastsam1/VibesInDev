package dev.redbeastsam.vibesindev.recommendation;

import static org.junit.jupiter.api.Assertions.*;

import dev.redbeastsam.vibesindev.model.Track;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"deprecation", "null"})
public class RecommendationEngineTest {
  private RecommendationEngine engine;
  private PlayerTasteProfile profile;

  @BeforeEach
  public void setUp() {
    engine = new RecommendationEngine(null);
    profile = new PlayerTasteProfile(UUID.randomUUID());
  }

  @Test
  public void testScoreAndPickBestFiltersSeedDuplicatesAndRemixes() {
    Track seed = new Track("seed_id", "Neoperreo", "Secret Society, ALIC3E", 210, "url1");
    Track remixCandidate = new Track("c1", "Neoperreo (Slowed)", "Secret Society", 240, "url2");
    Track validCandidate = new Track("c2", "Different Song", "Another Artist", 200, "url3");

    List<RecommendationCandidate> candidates =
        List.of(
            new RecommendationCandidate(
                remixCandidate, 0.0, RecommendationCandidate.Source.YOUTUBE_RADIO, "Radio", false),
            new RecommendationCandidate(
                validCandidate, 0.0, RecommendationCandidate.Source.YOUTUBE_RADIO, "Radio", false));

    Track chosen = engine.scoreAndPickBest(candidates, seed, profile);
    assertNotNull(chosen);
    assertEquals("c2", chosen.id(), "Must reject seed remix variant and pick valid candidate");
  }

  @Test
  public void testScoreAndPickBestFiltersDurationOutliers() {
    Track seed = new Track("seed_id", "Seed Song", "Artist A", 200, "url0");
    Track tooShort = new Track("short", "Meme Sound", "Artist A", 45, "url1"); // < 60s
    Track tooLong = new Track("long", "10 Hour Loop", "Artist A", 1200, "url2"); // > 600s
    Track normal = new Track("normal", "Regular Track", "Artist B", 210, "url3");

    List<RecommendationCandidate> candidates =
        List.of(
            new RecommendationCandidate(
                tooShort, 0.0, RecommendationCandidate.Source.YOUTUBE_RADIO, "Radio", false),
            new RecommendationCandidate(
                tooLong, 0.0, RecommendationCandidate.Source.YOUTUBE_RADIO, "Radio", false),
            new RecommendationCandidate(
                normal, 0.0, RecommendationCandidate.Source.YOUTUBE_RADIO, "Radio", false));

    Track chosen = engine.scoreAndPickBest(candidates, seed, profile);
    assertNotNull(chosen);
    assertEquals("normal", chosen.id(), "Outlier durations (<60s or >600s) must be rejected");
  }

  @Test
  public void testScoreAndPickBestFatigueRejection() {
    Track seed = new Track("seed_id", "Seed Song", "Artist A", 200, "url0");
    Track fatigued = new Track("fatigued", "Heard Recently", "Artist B", 180, "url1");
    Track fresh = new Track("fresh", "Never Heard", "Artist C", 190, "url2");

    profile.recordPlay(fatigued);

    List<RecommendationCandidate> candidates =
        List.of(
            new RecommendationCandidate(
                fatigued, 0.0, RecommendationCandidate.Source.YOUTUBE_RADIO, "Radio", false),
            new RecommendationCandidate(
                fresh, 0.0, RecommendationCandidate.Source.YOUTUBE_RADIO, "Radio", false));

    Track chosen = engine.scoreAndPickBest(candidates, seed, profile);
    assertNotNull(chosen);
    assertEquals("fresh", chosen.id(), "Fatigued tracks must not be selected");
  }

  @Test
  public void testScoreAndPickBestPrefersCachedAndHighAffinity() {
    Track seed = new Track("seed_id", "Seed Song", "Artist A", 200, "url0");
    Track candidateA = new Track("c_low", "Track 1", "Unknown Artist", 180, "url1");
    Track candidateB = new Track("c_fav", "Track 2", "Favorite Artist", 180, "url2");

    profile.ingestSpotifyArtists(List.of("Favorite Artist"), null);

    List<RecommendationCandidate> candidates =
        List.of(
            new RecommendationCandidate(
                candidateA, 0.0, RecommendationCandidate.Source.YOUTUBE_RADIO, "Radio", false),
            new RecommendationCandidate(
                candidateB, 0.0, RecommendationCandidate.Source.YOUTUBE_RADIO, "Radio", true));

    Track chosen = engine.scoreAndPickBest(candidates, seed, profile);
    assertNotNull(chosen);
    assertEquals(
        "c_fav",
        chosen.id(),
        "Candidate with high artist affinity and cache bonus should score highest");
  }
}

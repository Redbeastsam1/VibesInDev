package dev.vibesindev.vibesmusic.recommendation;

import static org.junit.jupiter.api.Assertions.*;

import dev.vibesindev.vibesmusic.model.Track;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class SessionContextTest {
  @Test
  @DisplayName("Session centroid changes gradually rather than abruptly as genres shift")
  void testSessionCentroidChangesGradually() {
    SessionContext context = new SessionContext();

    Track t1 = new Track("vid_1", "Song A", "Artist A", 180, "http://a");
    Track t2 = new Track("vid_2", "Song B", "Artist B", 180, "http://b");
    Track t3 = new Track("vid_3", "Song C", "Artist C", 180, "http://c");

    // First 2 tracks are electronic
    context.recordCompletedTrack(t1, List.of("electronic"), 2020, "en");
    context.recordCompletedTrack(t2, List.of("electronic"), 2021, "en");

    Map<String, Double> centroidAfterElectronic = context.getGenreCentroid();
    assertTrue(centroidAfterElectronic.containsKey("electronic"));
    assertEquals(1.0, centroidAfterElectronic.get("electronic"), 0.001);

    // 3rd track is rock (sudden switch)
    context.recordCompletedTrack(t3, List.of("rock"), 2022, "en");

    Map<String, Double> centroidAfterRock = context.getGenreCentroid();
    // The most recent rock has weight 1.0, previous electronic has 0.7 + 0.49 = 1.19
    // Both rock and electronic must be present with smooth, gradual transition
    assertTrue(centroidAfterRock.containsKey("electronic"), "Centroid must retain electronic mass");
    assertTrue(centroidAfterRock.containsKey("rock"), "Centroid must incorporate rock mass");
    assertTrue(
        centroidAfterRock.get("electronic") > 0.60,
        "Electronic mass should decay smoothly, not vanish");
    assertTrue(centroidAfterRock.get("rock") > 0.40, "Rock mass should be present proportionally");
  }

  @Test
  @DisplayName("Continuity calculation does not apply any Unicode script-based penalty")
  void testNoScriptMatchInContinuity() {
    SessionContext context = new SessionContext();
    Track seed = new Track("seed_1", "Tum Hi Ho", "Arijit Singh", 240, "http://seed");
    context.recordCompletedTrack(seed, List.of("bollywood", "pop"), 2013, "hi");

    // Candidate 1: Romanized Hindi title
    Track candRoman = new Track("c_1", "Kesariya", "Arijit Singh", 260, "http://c1");
    // Candidate 2: Devanagari Hindi title
    Track candDevanagari = new Track("c_2", "केसरिया", "Arijit Singh", 260, "http://c2");

    double contRoman =
        context.calculateContinuity(candRoman, List.of("bollywood"), 2022, seed, null, true);
    double contDev =
        context.calculateContinuity(candDevanagari, List.of("bollywood"), 2022, seed, null, true);

    // Neither candidate should be penalized for script mismatch; scores should be identical
    assertEquals(
        contRoman, contDev, 0.001, "Script mismatch must not cause any difference in continuity");
  }

  @Test
  @DisplayName("Continuity pro-rata redistributes weight when era or genre is missing")
  void testContinuityProRataWeightRedistribution() {
    SessionContext context = new SessionContext();
    Track seed = new Track("seed_1", "Original Song", "Known Artist", 200, "http://seed");

    // Candidate with NO genre and NO era
    Track candidate = new Track("cand_1", "Another Song", "Known Artist", 200, "http://cand");
    // Artist agreement = 1.0 (weight 0.25), Radio = 0.0 (weight 0.15). Available weight = 0.40
    // Weighted sum = 0.25 * 1.0 + 0.15 * 0.0 = 0.25
    // Normalized = 0.25 / 0.40 = 0.625
    double continuity = context.calculateContinuity(candidate, null, 0, seed, null, false);
    assertEquals(0.625, continuity, 0.01, "Missing genre and era must be redistributed pro-rata");
  }

  @Test
  @DisplayName("Stream C seed rotation adheres to 70% current seed / 30% previous completed tracks")
  void testStreamCSeedRotation() {
    SessionContext context = new SessionContext();
    Track t1 = new Track("t_1", "Past Song 1", "Artist 1", 200, "http://1");
    Track t2 = new Track("t_2", "Past Song 2", "Artist 2", 200, "http://2");
    Track current = new Track("t_curr", "Current Song", "Artist Curr", 200, "http://curr");

    context.recordCompletedTrack(t1, List.of("pop"), 2018, "en");
    context.recordCompletedTrack(t2, List.of("pop"), 2019, "en");

    // Seed with deterministic random
    // When nextFloat() < 0.70 -> returns currentSeed
    context.setRandom(
        new Random() {
          @Override
          public float nextFloat() {
            return 0.50f; // < 0.70
          }
        });
    assertEquals(current, context.getStreamCSeed(current));

    // When nextFloat() >= 0.70 -> returns one of prior completed tracks
    context.setRandom(
        new Random() {
          @Override
          public float nextFloat() {
            return 0.85f; // >= 0.70
          }

          @Override
          public int nextInt(int bound) {
            return 0;
          }
        });
    Track rotated = context.getStreamCSeed(current);
    assertNotEquals(current, rotated, "Must select prior track when p >= 0.70");
    assertTrue(rotated.equals(t1) || rotated.equals(t2));
  }
}

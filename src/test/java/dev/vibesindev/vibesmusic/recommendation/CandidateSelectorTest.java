package dev.vibesindev.vibesmusic.recommendation;

import dev.vibesindev.vibesmusic.model.Track;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

public class CandidateSelectorTest {

    @Test
    public void testSimilarityMetric() {
        Track a = new Track("1", "Blinding Lights", "The Weeknd", 200, "url");
        Track bSameArtist = new Track("2", "Starboy", "The Weeknd", 230, "url");
        Track cCover = new Track("3", "Blinding Lights (Live)", "Another Artist", 210, "url");
        Track dDifferent = new Track("4", "Levitating", "Dua Lipa", 200, "url");

        Map<String, List<String>> genres = Map.of(
                "the weeknd", List.of("pop", "r&b"),
                "dua lipa", List.of("pop", "dance")
        );

        // Same artist -> 1.0
        assertEquals(1.0, CandidateSelector.similarity(a, bSameArtist, genres), 1e-6);

        // Same composition title (live/cover) -> 0.9
        assertEquals(0.9, CandidateSelector.similarity(a, cCover, genres), 1e-6);

        // Pop genre overlap (intersection: "pop" (1), union: "pop", "r&b", "dance" (3)) -> Jaccard = 1/3 * 0.6 = 0.2
        assertEquals(0.2, CandidateSelector.similarity(a, dDifferent, genres), 1e-6);
    }

    @Test
    public void testGreedyMmrDiversifiesCandidates() {
        // Create 5 songs by the SAME artist (high score) and 5 songs by DIFFERENT artists (slightly lower score)
        List<RecommendationCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Track t = new Track("same_" + i, "Song " + i, "Same Artist", 200, "url");
            candidates.add(new RecommendationCandidate(t, 0.90 - (i * 0.01), RecommendationCandidate.Source.YOUTUBE_RADIO, "Radio", false));
        }
        for (int i = 0; i < 5; i++) {
            Track t = new Track("diff_" + i, "Track " + i, "Diverse Artist " + i, 200, "url");
            candidates.add(new RecommendationCandidate(t, 0.82 - (i * 0.01), RecommendationCandidate.Source.YOUTUBE_RADIO, "Radio", false));
        }

        // Run MMR to select 6 items
        List<CandidateSelector.MmrCandidate> selected = CandidateSelector.greedyMmr(
                candidates, Collections.emptyList(), Collections.emptyMap(), 0.70, 6
        );

        assertEquals(6, selected.size());

        // First item should be the highest-scoring candidate from "Same Artist"
        assertEquals("same_0", selected.get(0).candidate().track().id());

        // Subsequent items should NOT all be from "Same Artist" because MMR penalizes maxSim = 1.0!
        long diverseCount = selected.stream()
                .filter(m -> m.candidate().track().artist().startsWith("Diverse Artist"))
                .count();
        assertTrue(diverseCount >= 2, "MMR must interleave diverse artists instead of packing only the same artist");
    }

    @Test
    public void testTemperatureZeroReturnsArgmax() {
        RecommendationCandidate c1 = new RecommendationCandidate(new Track("1", "T1", "A1", 200, "u"), 0.8, RecommendationCandidate.Source.YOUTUBE_RADIO, "", false);
        RecommendationCandidate c2 = new RecommendationCandidate(new Track("2", "T2", "A2", 200, "u"), 0.95, RecommendationCandidate.Source.YOUTUBE_RADIO, "", false);
        RecommendationCandidate c3 = new RecommendationCandidate(new Track("3", "T3", "A3", 200, "u"), 0.7, RecommendationCandidate.Source.YOUTUBE_RADIO, "", false);

        List<CandidateSelector.MmrCandidate> mmrList = List.of(
                new CandidateSelector.MmrCandidate(c1, 0.8),
                new CandidateSelector.MmrCandidate(c2, 0.95),
                new CandidateSelector.MmrCandidate(c3, 0.7)
        );

        Random rng = new Random(42);
        RecommendationCandidate chosen = CandidateSelector.softmaxSample(mmrList, 0.0, rng);
        assertNotNull(chosen);
        assertEquals("2", chosen.track().id(), "Temperature 0 must return argmax (highest MMR score)");
    }

    @Test
    public void testSoftmaxDistributionOver10000Samples() {
        RecommendationCandidate cHigh = new RecommendationCandidate(new Track("high", "T1", "A1", 200, "u"), 0.95, RecommendationCandidate.Source.YOUTUBE_RADIO, "", false);
        RecommendationCandidate cLow = new RecommendationCandidate(new Track("low", "T2", "A2", 200, "u"), 0.80, RecommendationCandidate.Source.YOUTUBE_RADIO, "", false);

        List<CandidateSelector.MmrCandidate> mmrList = List.of(
                new CandidateSelector.MmrCandidate(cHigh, 0.95),
                new CandidateSelector.MmrCandidate(cLow, 0.80)
        );

        Random rng = new Random(42);
        int highCount = 0;
        int lowCount = 0;
        int trials = 10_000;

        for (int i = 0; i < trials; i++) {
            RecommendationCandidate chosen = CandidateSelector.softmaxSample(mmrList, 0.08, rng);
            if (chosen.track().id().equals("high")) {
                highCount++;
            } else {
                lowCount++;
            }
        }

        // With delta = 0.15 and temperature = 0.08, exp(0.15/0.08) = exp(1.875) ≈ 6.52
        // High count should be roughly ~86% of trials, but low count must also be non-zero
        assertTrue(highCount > 7000, "High scoring track should be chosen most of the time (>70%)");
        assertTrue(lowCount > 500, "Lower scoring track should still be chosen occasionally for diversity (>5%)");
    }
}

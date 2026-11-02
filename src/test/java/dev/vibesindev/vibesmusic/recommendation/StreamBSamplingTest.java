package dev.vibesindev.vibesmusic.recommendation;

import dev.vibesindev.vibesmusic.model.Track;
import dev.vibesindev.vibesmusic.spotify.SpotifyClient;
import dev.vibesindev.vibesmusic.spotify.SpotifyHttp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class StreamBSamplingTest {

    @Test
    @DisplayName("Temperature <= 0 deterministic mode returns exact argmax top genres")
    public void testDeterministicArgmax() {
        Map<String, Double> dist = Map.of(
                "synthwave", 0.50,
                "electronic", 0.30,
                "french house", 0.15,
                "rock", 0.05
        );

        List<String> top3 = RecommendationEngine.sampleGenres(dist, 3, 0.0, new Random(42));
        assertEquals(3, top3.size());
        assertEquals("synthwave", top3.get(0));
        assertEquals("electronic", top3.get(1));
        assertEquals("french house", top3.get(2));
    }

    @Test
    @DisplayName("Sampling without replacement produces unique genres")
    public void testSamplingWithoutReplacement() {
        Map<String, Double> dist = Map.of(
                "pop", 0.40,
                "dance", 0.30,
                "house", 0.20,
                "indie", 0.10
        );

        Random random = new Random(12345);
        for (int i = 0; i < 50; i++) {
            List<String> sampled = RecommendationEngine.sampleGenres(dist, 3, 1.0, random);
            assertEquals(3, sampled.size());
            Set<String> unique = new HashSet<>(sampled);
            assertEquals(3, unique.size(), "Sampled genres must be distinct without replacement");
        }
    }

    @Test
    @DisplayName("Weighted sampling samples lower genres proportional to temperature without starving them")
    public void testSamplingDistribution() {
        Map<String, Double> dist = Map.of(
                "dominant", 0.70,
                "secondary", 0.20,
                "rare", 0.10
        );

        Map<String, Integer> counts = new HashMap<>();
        Random random = new Random(999);
        int iterations = 10_000;

        for (int i = 0; i < iterations; i++) {
            List<String> sampled = RecommendationEngine.sampleGenres(dist, 1, 1.0, random);
            String picked = sampled.get(0);
            counts.put(picked, counts.getOrDefault(picked, 0) + 1);
        }

        int domCount = counts.getOrDefault("dominant", 0);
        int secCount = counts.getOrDefault("secondary", 0);
        int rareCount = counts.getOrDefault("rare", 0);

        assertTrue(domCount > secCount, "Dominant genre should be picked more than secondary");
        assertTrue(secCount > rareCount, "Secondary genre should be picked more than rare");
        assertTrue(rareCount > 500, "Rare genre must not be starved (got " + rareCount + " out of " + iterations + ")");
    }

    @Test
    @DisplayName("Feature circuit breaker: disabled GENRE_SEARCH marks feature as disabled")
    public void testDisabledGenreSearchCircuitBreaker() {
        SpotifyHttp http = new SpotifyHttp();
        assertFalse(http.isFeatureDisabled(SpotifyHttp.Feature.GENRE_SEARCH));

        http.disableFeature(SpotifyHttp.Feature.GENRE_SEARCH);
        assertTrue(http.isFeatureDisabled(SpotifyHttp.Feature.GENRE_SEARCH));
    }
}

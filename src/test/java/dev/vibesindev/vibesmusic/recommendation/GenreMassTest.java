package dev.vibesindev.vibesmusic.recommendation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings({"deprecation", "null"})
public class GenreMassTest {

    @Test
    @DisplayName("Ingesting the same artists in different orders gives the exact same distribution")
    public void testOrderIndependence() {
        PlayerTasteProfile profile1 = new PlayerTasteProfile(UUID.randomUUID());
        PlayerTasteProfile profile2 = new PlayerTasteProfile(UUID.randomUUID());

        List<String> artistAGenres = List.of("synthwave", "electronic", "retrowave");
        List<String> artistBGenres = List.of("french house", "electronic");
        List<String> artistCGenres = List.of("rock", "alternative rock", "indie");

        // Order 1: A -> B -> C
        profile1.ingestArtistGenres(artistAGenres, 1.0);
        profile1.ingestArtistGenres(artistBGenres, 0.8);
        profile1.ingestArtistGenres(artistCGenres, 0.6);

        // Order 2: C -> A -> B
        profile2.ingestArtistGenres(artistCGenres, 0.6);
        profile2.ingestArtistGenres(artistAGenres, 1.0);
        profile2.ingestArtistGenres(artistBGenres, 0.8);

        Map<String, Double> dist1 = profile1.getGenreDistribution();
        Map<String, Double> dist2 = profile2.getGenreDistribution();

        assertEquals(dist1.keySet(), dist2.keySet(), "Genre sets must match regardless of ingestion order");

        double sum1 = 0.0;
        double sum2 = 0.0;
        for (String g : dist1.keySet()) {
            double p1 = dist1.get(g);
            double p2 = dist2.get(g);
            assertEquals(p1, p2, 1e-9, "Probability of genre " + g + " must be equal regardless of order");
            sum1 += p1;
            sum2 += p2;
        }

        assertEquals(1.0, sum1, 1e-9, "Distribution 1 must sum to 1.0");
        assertEquals(1.0, sum2, 1e-9, "Distribution 2 must sum to 1.0");
    }

    @Test
    @DisplayName("Ingesting a 101st artist changes existing probabilities by only a small amount")
    public void testIncrementalArtistStability() {
        PlayerTasteProfile profile = new PlayerTasteProfile(UUID.randomUUID());

        // Ingest 100 artists with distinct or overlapping genres
        for (int i = 0; i < 100; i++) {
            profile.ingestArtistGenres(List.of("genre_" + (i % 20), "common_genre"), 1.0);
        }

        Map<String, Double> distBefore = profile.getGenreDistribution();
        double commonProbBefore = distBefore.getOrDefault("common_genre", 0.0);
        assertTrue(commonProbBefore > 0.40, "Common genre should have significant mass before 101st artist");

        // Ingest 101st artist
        profile.ingestArtistGenres(List.of("genre_999", "common_genre"), 1.0);

        Map<String, Double> distAfter = profile.getGenreDistribution();
        double commonProbAfter = distAfter.getOrDefault("common_genre", 0.0);

        // Relative change should be ≈ 1/101 ≈ 1%
        double relativeChange = Math.abs(commonProbBefore - commonProbAfter) / commonProbBefore;
        assertTrue(relativeChange < 0.02, "101st artist should change existing genre probability by < 2%, got: " + relativeChange);
    }

    @Test
    @DisplayName("Running Spotify sync twice is idempotent and gives the exact same distribution")
    public void testSpotifySyncIdempotence() {
        PlayerTasteProfile profile = new PlayerTasteProfile(UUID.randomUUID());

        // Simulate Spotify sync run 1
        profile.clearSpotifyGenreMass();
        profile.ingestSpotifyArtistGenres(List.of("pop", "dance pop"), 1.0);
        profile.ingestSpotifyArtistGenres(List.of("hip hop", "trap"), 0.8);
        profile.ingestSpotifyArtistGenres(List.of("indie rock"), 0.5);

        Map<String, Double> distFirstSync = profile.getGenreDistribution();

        // Simulate Spotify sync run 2 with same data
        profile.clearSpotifyGenreMass();
        profile.ingestSpotifyArtistGenres(List.of("pop", "dance pop"), 1.0);
        profile.ingestSpotifyArtistGenres(List.of("hip hop", "trap"), 0.8);
        profile.ingestSpotifyArtistGenres(List.of("indie rock"), 0.5);

        Map<String, Double> distSecondSync = profile.getGenreDistribution();

        assertEquals(distFirstSync.keySet(), distSecondSync.keySet());
        for (String g : distFirstSync.keySet()) {
            assertEquals(distFirstSync.get(g), distSecondSync.get(g), 1e-9,
                    "Genre " + g + " probability must be identical after repeat sync");
        }
    }

    @Test
    @DisplayName("Old snapshots without genre_mass_json load into distribution correctly")
    public void testLegacySnapshotLoading() {
        PlayerTasteProfile profile = new PlayerTasteProfile(UUID.randomUUID());

        // Legacy normalized snapshot (only genres map, no playMass or spotifyMass)
        Map<String, Double> legacyGenres = Map.of(
                "synthwave", 0.60,
                "electronic", 0.40
        );

        profile.restoreFromSnapshot(
                Collections.emptyMap(),
                legacyGenres,
                null,
                null,
                null,
                null,
                1000L
        );

        Map<String, Double> dist = profile.getGenreDistribution();
        assertEquals(2, dist.size());
        assertEquals(0.60, dist.get("synthwave"), 1e-9);
        assertEquals(0.40, dist.get("electronic"), 1e-9);
    }

    @Test
    @DisplayName("Full snapshot with playMass and spotifyMass restores both channels correctly")
    public void testFullMassSnapshotRoundTrip() {
        PlayerTasteProfile profile = new PlayerTasteProfile(UUID.randomUUID());

        profile.ingestArtistGenres(List.of("rock", "indie"), 1.0); // play mass
        profile.ingestSpotifyArtistGenres(List.of("ambient", "electronic"), 0.8); // spotify mass

        String massJson = profile.exportGenreMassJson();
        assertNotNull(massJson);
        assertTrue(massJson.contains("play"));
        assertTrue(massJson.contains("spotify"));

        com.google.gson.Gson gson = new com.google.gson.Gson();
        java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<Map<String, Map<String, Double>>>(){}.getType();
        Map<String, Map<String, Double>> parsed = gson.fromJson(massJson, type);

        PlayerTasteProfile restored = new PlayerTasteProfile(UUID.randomUUID());
        restored.restoreFromSnapshot(
                Collections.emptyMap(),
                Collections.emptyMap(),
                parsed.get("play"),
                parsed.get("spotify"),
                Collections.emptyMap(),
                Collections.emptyMap(),
                System.currentTimeMillis()
        );

        Map<String, Double> origDist = profile.getGenreDistribution();
        Map<String, Double> restDist = restored.getGenreDistribution();

        assertEquals(origDist.keySet(), restDist.keySet());
        for (String k : origDist.keySet()) {
            assertEquals(origDist.get(k), restDist.get(k), 1e-6);
        }
    }

    @Test
    @DisplayName("Artists with many genre tags do not swamp artists with few tags (tag dilution)")
    public void testTagDilutionWeighting() {
        PlayerTasteProfile profile = new PlayerTasteProfile(UUID.randomUUID());

        // Artist A: single specific genre tag with weight 1.0 -> 1.0 mass
        profile.ingestArtistGenres(List.of("synthwave"), 1.0);

        // Artist B: broad artist with 10 generic tags with weight 1.0 -> 0.1 mass each
        List<String> broadTags = new ArrayList<>();
        for (int i = 0; i < 10; i++) broadTags.add("broad_" + i);
        profile.ingestArtistGenres(broadTags, 1.0);

        Map<String, Double> dist = profile.getGenreDistribution();

        // Synthwave total mass = 1.0, broad tags total mass = 1.0, total = 2.0
        // synthwave prob should be 1.0 / 2.0 = 0.50
        // each broad tag prob should be 0.1 / 2.0 = 0.05
        assertEquals(0.50, dist.get("synthwave"), 1e-6);
        assertEquals(0.05, dist.get("broad_0"), 1e-6);
    }
}

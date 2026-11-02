package dev.vibesindev.vibesmusic.recommendation;

import dev.vibesindev.vibesmusic.model.Track;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * In-memory, non-persisted session context tracking the last completed tracks,
 * an exponentially decayed genre centroid (0.7 per track), and continuity calculation.
 */
public final class SessionContext {

    public record CompletedTrack(
            Track track,
            List<String> genres,
            int releaseYear,
            String language
    ) {}

    private final Deque<CompletedTrack> completedTracks = new ArrayDeque<>();
    private final Map<String, Double> genreCentroid = new HashMap<>();
    private Random random = new Random();
    private MinecraftContextSampler.ContextSnapshot minecraftContext;

    public void setMinecraftContext(MinecraftContextSampler.ContextSnapshot context) {
        this.minecraftContext = context;
    }

    public MinecraftContextSampler.ContextSnapshot getMinecraftContext() {
        return minecraftContext;
    }

    public void setRandom(Random random) {
        this.random = (random != null) ? random : new Random();
    }

    public synchronized void recordCompletedTrack(Track track, List<String> genres, int releaseYear, String language) {
        if (track == null) return;
        List<String> cleanGenres = new ArrayList<>();
        if (genres != null) {
            for (String g : genres) {
                if (g != null && !g.isBlank()) {
                    cleanGenres.add(g.toLowerCase(Locale.ROOT).trim());
                }
            }
        }
        completedTracks.addFirst(new CompletedTrack(track, cleanGenres, releaseYear, language));
        while (completedTracks.size() > 8) {
            completedTracks.removeLast();
        }
        recalculateGenreCentroid();
    }

    private void recalculateGenreCentroid() {
        genreCentroid.clear();
        if (completedTracks.isEmpty()) return;

        Map<String, Double> rawMass = new HashMap<>();
        double decayFactor = 0.70;
        int index = 0;

        for (CompletedTrack ct : completedTracks) {
            double trackWeight = Math.pow(decayFactor, index);
            List<String> gList = ct.genres();
            if (gList != null && !gList.isEmpty()) {
                double perTagWeight = trackWeight / gList.size();
                for (String g : gList) {
                    rawMass.merge(g, perTagWeight, Double::sum);
                }
            }
            index++;
        }

        double normSq = 0.0;
        for (double m : rawMass.values()) {
            normSq += m * m;
        }

        if (normSq > 0.0) {
            double norm = Math.sqrt(normSq);
            for (Map.Entry<String, Double> e : rawMass.entrySet()) {
                genreCentroid.put(e.getKey(), e.getValue() / norm);
            }
        }
    }

    public synchronized Map<String, Double> getGenreCentroid() {
        return Collections.unmodifiableMap(new HashMap<>(genreCentroid));
    }

    public synchronized List<CompletedTrack> getCompletedTracks() {
        return new ArrayList<>(completedTracks);
    }

    /**
     * Computes cosine similarity between candidate genres and the session centroid unit vector.
     * Returns -1.0 if candidate has no genres or session centroid is empty (missing feature).
     */
    public synchronized double cosineSimilarity(List<String> candidateGenres) {
        if (candidateGenres == null || candidateGenres.isEmpty() || genreCentroid.isEmpty()) {
            return -1.0;
        }
        Set<String> clean = new HashSet<>();
        for (String g : candidateGenres) {
            if (g != null && !g.isBlank()) {
                clean.add(g.toLowerCase(Locale.ROOT).trim());
            }
        }
        if (clean.isEmpty()) return -1.0;

        double dot = 0.0;
        for (String g : clean) {
            Double c = genreCentroid.get(g);
            if (c != null) {
                dot += c;
            }
        }
        if (dot <= 0.0) return 0.0;

        double candNorm = Math.sqrt(clean.size());
        return Math.max(0.0, Math.min(1.0, dot / candNorm));
    }

    /**
     * Determines the seed track for Stream C (YouTube Radio Mix):
     * 70% probability: currentSeed
     * 30% probability: one of the previous 1 or 2 completed tracks before the current one (if available).
     */
    public synchronized Track getStreamCSeed(Track currentSeed) {
        if (currentSeed == null) {
            CompletedTrack first = completedTracks.peekFirst();
            return (first != null) ? first.track() : null;
        }

        List<Track> priorTracks = new ArrayList<>();
        for (CompletedTrack ct : completedTracks) {
            if (ct.track() != null && !PlayerTasteProfile.isSameSongOrRemix(ct.track(), currentSeed)) {
                priorTracks.add(ct.track());
                if (priorTracks.size() >= 2) break;
            }
        }

        if (priorTracks.isEmpty()) {
            return currentSeed;
        }

        float p = random.nextFloat();
        if (p < 0.70f) {
            return currentSeed;
        } else {
            int idx = random.nextInt(priorTracks.size());
            return priorTracks.get(idx);
        }
    }

    /**
     * Evaluates vibe continuity using session context:
     * 0.50 * cosine(candidateGenres, sessionCentroid) +
     * 0.25 * sameArtistOrLineage +
     * 0.15 * fromRadioOfSeed +
     * 0.10 * eraProximity
     *
     * Missing terms are redistributed pro-rata. Script matching has 0 weight.
     */
    public synchronized double calculateContinuity(
            Track candidate,
            List<String> candidateGenres,
            int candidateYear,
            Track seed,
            PlayerTasteProfile profile,
            boolean fromRadioOfSeed
    ) {
        if (candidate == null) return 0.40;

        double weightedSum = 0.0;
        double availableWeight = 0.0;

        // 1. Genre centroid cosine similarity (weight 0.50)
        double genreCos = cosineSimilarity(candidateGenres);
        if (genreCos >= 0.0) {
            weightedSum += 0.50 * genreCos;
            availableWeight += 0.50;
        }

        // 2. Same artist or lineage (weight 0.25)
        double artistLineageScore = 0.0;
        if (seed != null) {
            if (ArtistNames.sameArtist(candidate.artist(), seed.artist())) {
                artistLineageScore = 1.0;
            } else if (profile != null) {
                Set<String> connected = profile.getConnectedArtists(seed.artist());
                String candNorm = ArtistNames.normalize(candidate.artist());
                if (connected.contains(candNorm)) {
                    artistLineageScore = 0.75;
                }
            }
        }
        weightedSum += 0.25 * artistLineageScore;
        availableWeight += 0.25;

        // 3. From radio of seed (weight 0.15)
        double radioScore = fromRadioOfSeed ? 1.0 : 0.0;
        weightedSum += 0.15 * radioScore;
        availableWeight += 0.15;

        // 4. Era proximity (weight 0.10)
        int seedYear = getSeedOrSessionAverageYear(seed);
        if (candidateYear > 1900 && seedYear > 1900) {
            int diff = Math.abs(candidateYear - seedYear);
            double eraScore = Math.max(0.0, 1.0 - (diff / 20.0));
            weightedSum += 0.10 * eraScore;
            availableWeight += 0.10;
        }

        if (availableWeight <= 0.0) {
            return 0.40;
        }

        return Math.max(0.0, Math.min(1.0, weightedSum / availableWeight));
    }

    private int getSeedOrSessionAverageYear(Track seed) {
        for (CompletedTrack ct : completedTracks) {
            if (ct.releaseYear() > 1900) {
                return ct.releaseYear();
            }
        }
        return 0;
    }
}

package dev.vibesindev.vibesmusic.recommendation;

import dev.vibesindev.vibesmusic.model.Track;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Maximal Marginal Relevance (MMR) re-ranking and temperature-weighted softmax sampling.
 */
public final class CandidateSelector {

    private CandidateSelector() {}

    public static double similarity(Track a, Track b, Map<String, List<String>> artistGenres) {
        if (a == null || b == null) return 0.0;

        // 1. Same artist
        if (ArtistNames.sameArtist(a.artist(), b.artist())) {
            return 1.0;
        }

        // 2. Same canonical / composition title (cover / live / remix)
        String compA = RecordingIdentity.compositionTitleKey(a.title(), a.artist());
        String compB = RecordingIdentity.compositionTitleKey(b.title(), b.artist());
        if (!compA.isEmpty() && compA.equals(compB)) {
            return 0.9;
        }

        // 3. Jaccard similarity of genre sets * 0.6
        if (artistGenres != null) {
            List<String> gA = artistGenres.get(ArtistNames.normalize(a.artist()));
            List<String> gB = artistGenres.get(ArtistNames.normalize(b.artist()));
            if (gA != null && !gA.isEmpty() && gB != null && !gB.isEmpty()) {
                Set<String> setA = new HashSet<>(gA);
                Set<String> setB = new HashSet<>(gB);
                Set<String> intersection = new HashSet<>(setA);
                intersection.retainAll(setB);
                Set<String> union = new HashSet<>(setA);
                union.addAll(setB);
                if (!union.isEmpty()) {
                    double jaccard = (double) intersection.size() / union.size();
                    return jaccard * 0.6;
                }
            }
        }

        return 0.0;
    }

    public record MmrCandidate(RecommendationCandidate candidate, double mmrScore) {}

    public static List<MmrCandidate> greedyMmr(
            List<RecommendationCandidate> candidates,
            List<Track> lastFivePlayed,
            Map<String, List<String>> artistGenres,
            double lambda,
            int maxSelect
    ) {
        if (candidates == null || candidates.isEmpty() || maxSelect <= 0) {
            return Collections.emptyList();
        }

        List<RecommendationCandidate> pool = new ArrayList<>(candidates);
        Collections.sort(pool);
        if (pool.size() > 15) {
            pool = new ArrayList<>(pool.subList(0, 15));
        }

        List<Track> contextTracks = new ArrayList<>();
        if (lastFivePlayed != null) {
            for (Track t : lastFivePlayed) {
                if (t != null) contextTracks.add(t);
            }
        }

        List<MmrCandidate> selected = new ArrayList<>();
        int targetCount = Math.min(maxSelect, pool.size());

        while (selected.size() < targetCount && !pool.isEmpty()) {
            double bestMmr = -Double.MAX_VALUE;
            RecommendationCandidate bestCandidate = null;

            for (RecommendationCandidate cand : pool) {
                double maxSim = 0.0;

                // maxSim against context (last 5 played)
                for (Track ctx : contextTracks) {
                    double s = similarity(cand.track(), ctx, artistGenres);
                    if (s > maxSim) maxSim = s;
                }

                // maxSim against already selected MMR candidates
                for (MmrCandidate sel : selected) {
                    double s = similarity(cand.track(), sel.candidate().track(), artistGenres);
                    if (s > maxSim) maxSim = s;
                }

                double mmr = lambda * cand.score() - (1.0 - lambda) * maxSim;
                if (mmr > bestMmr) {
                    bestMmr = mmr;
                    bestCandidate = cand;
                }
            }

            if (bestCandidate != null) {
                selected.add(new MmrCandidate(bestCandidate, bestMmr));
                pool.remove(bestCandidate);
            } else {
                break;
            }
        }

        return selected;
    }

    public static RecommendationCandidate softmaxSample(
            List<MmrCandidate> mmrList,
            double temperature,
            Random random
    ) {
        if (mmrList == null || mmrList.isEmpty()) {
            return null;
        }

        if (temperature <= 0.0 || mmrList.size() == 1) {
            MmrCandidate best = mmrList.get(0);
            for (MmrCandidate c : mmrList) {
                if (c.mmrScore() > best.mmrScore()) {
                    best = c;
                }
            }
            return best.candidate();
        }

        Random rng = (random != null) ? random : new Random();

        double maxScore = -Double.MAX_VALUE;
        for (MmrCandidate c : mmrList) {
            if (c.mmrScore() > maxScore) maxScore = c.mmrScore();
        }

        double[] expWeights = new double[mmrList.size()];
        double sumExp = 0.0;
        for (int i = 0; i < mmrList.size(); i++) {
            double v = Math.exp((mmrList.get(i).mmrScore() - maxScore) / temperature);
            expWeights[i] = v;
            sumExp += v;
        }

        if (sumExp <= 0.0 || Double.isNaN(sumExp)) {
            return mmrList.get(0).candidate();
        }

        double r = rng.nextDouble() * sumExp;
        double running = 0.0;
        for (int i = 0; i < mmrList.size(); i++) {
            running += expWeights[i];
            if (running >= r) {
                return mmrList.get(i).candidate();
            }
        }

        return mmrList.get(mmrList.size() - 1).candidate();
    }
}

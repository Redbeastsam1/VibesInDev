package dev.vibesindev.vibesmusic.recommendation;

import dev.vibesindev.vibesmusic.VibesInDevPlugin;
import dev.vibesindev.vibesmusic.model.Track;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Intelligent recommendation engine that simulates Spotify's actual recommendation algorithm.
 *
 * <p>Implements:
 * <ol>
 *   <li>The 4-Stream Candidate Funnel (Spotify Top Tracks, Micro-Genre Search, YouTube RD-Mix, Server Collaborative Filtering)</li>
 *   <li>Multi-Objective Scoring Function with cache bonuses and Daily Mix vibe locking</li>
 *   <li>Strict Fatigue Blacklist (last 20 tracks / 45 minutes) and artist soft decay (-70%)</li>
 *   <li>Epsilon-Greedy Discovery Exploration ($\epsilon = 0.20$)</li>
 * </ol>
 */
@SuppressWarnings("null")
public final class RecommendationEngine {

    private static final Random RANDOM = new Random();
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();

    private final VibesInDevPlugin plugin;
    private final ExecutorService providerExecutor;

    public RecommendationEngine(VibesInDevPlugin plugin) {
        this.plugin = plugin;
        this.providerExecutor = new ThreadPoolExecutor(
                2, 8,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(32),
                r -> {
                    Thread t = new Thread(r, "VibesMusic-RecProvider-" + THREAD_COUNTER.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    public void shutdown() {
        if (providerExecutor != null && !providerExecutor.isShutdown()) {
            providerExecutor.shutdown();
            try {
                if (!providerExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    providerExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                providerExecutor.shutdownNow();
            }
        }
    }

    /**
     * Ranks and selects the best next track given a finishing seed track and a player's taste profile.
     *
     * @param seed    the currently finishing track
     * @param profile the listener's (or party's) taste profile
     * @return future resolving to the highest-scoring recommended track, or {@code null} if all fail
     */
    public CompletableFuture<Track> recommendNext(Track seed, PlayerTasteProfile profile) {
        if (seed == null) {
            return fallbackTrending(seed, profile);
        }

        List<CompletableFuture<List<RecommendationCandidate>>> futures = new ArrayList<>();

        // Stream C: YouTube Algorithmic Radio Mix (fast, neural cluster)
        if (plugin != null && plugin.ytDlp() != null) {
            futures.add(fetchYouTubeRadioCandidates(seed));
        }

        // Stream D: Local Server Collaborative Filtering (instant SQLite graph)
        if (plugin != null && plugin.cache() != null) {
            futures.add(fetchCollaborativeCandidates(seed));
        }

        // Stream A & B: If Spotify is enabled and configured, query Spotify candidate streams
        if (plugin != null && plugin.spotify() != null && plugin.spotify().isConfigured()) {
            futures.add(fetchSpotifyCandidates(seed, profile));
        }

        return gatherAndPick(futures, seed, profile);
    }

    /**
     * Gathers candidates across parallel streams with 3 000 ms per-stream timeout and 6 000 ms total deadline.
     */
    public CompletableFuture<Track> gatherAndPick(
            List<CompletableFuture<List<RecommendationCandidate>>> streamFutures,
            Track seed,
            PlayerTasteProfile profile
    ) {
        List<CompletableFuture<List<RecommendationCandidate>>> guardedFutures = new ArrayList<>();
        if (streamFutures != null) {
            for (CompletableFuture<List<RecommendationCandidate>> f : streamFutures) {
                if (f != null) {
                    guardedFutures.add(f.completeOnTimeout(Collections.emptyList(), 3000L, TimeUnit.MILLISECONDS)
                            .exceptionally(err -> Collections.emptyList()));
                }
            }
        }

        CompletableFuture<Void> allGather = CompletableFuture.allOf(guardedFutures.toArray(new CompletableFuture[0]))
                .completeOnTimeout(null, 6000L, TimeUnit.MILLISECONDS);

        return allGather
                .thenApply(ignored -> {
                    List<RecommendationCandidate> allCandidates = new ArrayList<>();
                    for (CompletableFuture<List<RecommendationCandidate>> f : guardedFutures) {
                        try {
                            List<RecommendationCandidate> candidates = f.getNow(Collections.emptyList());
                            if (candidates != null) {
                                allCandidates.addAll(candidates);
                            }
                        } catch (Exception ignoredErr) {}
                    }
                    return allCandidates;
                })
                .thenCompose(candidates -> {
                    if (candidates.isEmpty()) {
                        return fallbackTrending(seed, profile);
                    }

                    // Score and rank candidates
                    Track best = scoreAndPickBest(candidates, seed, profile);
                    if (best != null) {
                        return CompletableFuture.completedFuture(best);
                    }
                    return fallbackTrending(seed, profile);
                })
                .exceptionallyCompose(err -> fallbackTrending(seed, profile));
    }

    // =========================================================
    // CANDIDATE GENERATION STREAMS
    // =========================================================

    /**
     * Stream C: Fetches YouTube Radio Mix candidates (RD{id}) via flat-playlist.
     */
    private CompletableFuture<List<RecommendationCandidate>> fetchYouTubeRadioCandidates(Track seed) {
        String ytId = extractYouTubeId(seed);
        if (ytId == null) {
            // Seed is a non-YouTube track (e.g. Spotify track).
            // Search YouTube to find the video ID of the seed track so we can query its real Radio Mix
            return plugin.ytDlp().search(seed.artist() + " " + seed.title())
                    .thenCompose(resolvedSeed -> {
                        if (resolvedSeed != null && resolvedSeed.id() != null && !resolvedSeed.id().isBlank()) {
                            String foundId = extractYouTubeId(resolvedSeed);
                            if (foundId != null) {
                                String radioUrl = "https://www.youtube.com/watch?v=" + foundId + "&list=RD" + foundId;
                                return plugin.ytDlp().resolveRadioMix(radioUrl, Collections.emptySet());
                            }
                        }
                        return CompletableFuture.completedFuture(Collections.<Track>emptyList());
                    })
                    .thenApply(tracks -> {
                        List<RecommendationCandidate> result = new ArrayList<>();
                        if (tracks != null) {
                            for (Track t : tracks) {
                                if (t != null && !PlayerTasteProfile.isSameSongOrRemix(t, seed)) {
                                    result.add(new RecommendationCandidate(
                                            t, 0.0, RecommendationCandidate.Source.YOUTUBE_RADIO, "YouTube RD Neural Cluster", plugin.cache().hasAudio(t.id())
                                    ));
                                }
                            }
                        }
                        return result;
                    })
                    .exceptionally(err -> Collections.emptyList());
        }

        String radioUrl = "https://www.youtube.com/watch?v=" + ytId + "&list=RD" + ytId;
        return plugin.ytDlp().resolveRadioMix(radioUrl, Collections.emptySet())
                .thenApply(tracks -> {
                    List<RecommendationCandidate> result = new ArrayList<>();
                    if (tracks != null) {
                        for (Track t : tracks) {
                            if (t != null && !PlayerTasteProfile.isSameSongOrRemix(t, seed)) {
                                result.add(new RecommendationCandidate(
                                        t, 0.0, RecommendationCandidate.Source.YOUTUBE_RADIO, "YouTube RD Neural Cluster", plugin.cache().hasAudio(t.id())
                                ));
                            }
                        }
                    }
                    return result;
                })
                .exceptionally(err -> Collections.emptyList());
    }

    /**
     * Stream D: Collaborative filtering from local server history.
     */
    private CompletableFuture<List<RecommendationCandidate>> fetchCollaborativeCandidates(Track seed) {
        if (plugin == null || plugin.cache() == null) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        return CompletableFuture.supplyAsync(() -> {
            List<RecommendationCandidate> list = new ArrayList<>();
            // "Players who played Artist X also played..."
            List<String> coPlayed = plugin.cache().getCoPlayedArtists(seed.artist(), 5);
            for (String artist : coPlayed) {
                List<Track> cachedTracks = plugin.cache().getTracksByArtist(artist, 2);
                for (Track t : cachedTracks) {
                    list.add(new RecommendationCandidate(
                            t, 0.0, RecommendationCandidate.Source.SERVER_COLLABORATIVE,
                            "Co-played with " + seed.artist(), plugin.cache().hasAudio(t.id())
                    ));
                }
            }
            return list;
        }, providerExecutor).exceptionally(err -> Collections.emptyList());
    }

    /**
     * Streams A & B: Seed artist top tracks & micro-genre clustering via Spotify.
     */
    private CompletableFuture<List<RecommendationCandidate>> fetchSpotifyCandidates(Track seed, PlayerTasteProfile profile) {
        List<RecommendationCandidate> list = new CopyOnWriteArrayList<>();

        // Stream A: Same artist catalog
        List<Track> sameArtistTracks = plugin.cache().getTracksByArtist(seed.artist(), 3);
        for (Track t : sameArtistTracks) {
            if (t != null && !PlayerTasteProfile.isSameSongOrRemix(t, seed)) {
                list.add(new RecommendationCandidate(
                        t, 0.0, RecommendationCandidate.Source.SPOTIFY_ARTIST_TOP,
                        "Same artist catalog: " + seed.artist(), plugin.cache().hasAudio(t.id())
                ));
            }
        }

        // Stream B: Spotify Micro-genre search if profile has top genres
        if (profile != null && !profile.getGenreDistribution().isEmpty()) {
            String topGenre = null;
            double highestProb = -1.0;
            for (Map.Entry<String, Double> entry : profile.getGenreDistribution().entrySet()) {
                if (entry != null && entry.getValue() != null && entry.getValue().doubleValue() > highestProb) {
                    highestProb = entry.getValue().doubleValue();
                    topGenre = entry.getKey();
                }
            }
            if (topGenre != null && !topGenre.isBlank()) {
                final String selectedGenre = topGenre;
                return plugin.spotify().searchGenreTracks(selectedGenre, 5)
                        .thenApply(genreTracks -> {
                            if (genreTracks != null) {
                                for (Track gt : genreTracks) {
                                    list.add(new RecommendationCandidate(
                                            gt, 0.0, RecommendationCandidate.Source.SPOTIFY_GENRE,
                                            "Spotify micro-genre: " + selectedGenre, plugin.cache().hasAudio(gt.id())
                                    ));
                                }
                            }
                            return list;
                        })
                        .exceptionally(err -> list);
            }
        }

        return CompletableFuture.completedFuture(list);
    }

    /**
     * Fallback: Surfaces trending / frequently played tracks across the server.
     */
    public CompletableFuture<Track> fallbackTrending(Track seed, PlayerTasteProfile profile) {
        if (plugin == null || plugin.cache() == null) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.supplyAsync(() -> {
            List<Track> trending = plugin.cache().getTrendingTracks(10);
            if (trending == null || trending.isEmpty()) {
                return null;
            }
            List<Track> snapshot = new ArrayList<>(trending);
            int cooldownTracks = plugin.getConfig() != null ? plugin.getConfig().getInt("recommendations.cooldown-tracks", 20) : 20;
            long cooldownMs = (plugin.getConfig() != null ? plugin.getConfig().getLong("recommendations.cooldown-minutes", 45) : 45) * 60_000L;

            // Tier 1: Strict filters (full cooldown window + strict seed/remix rejection)
            for (Track t : snapshot) {
                if (t == null) continue;
                if (seed != null && PlayerTasteProfile.isSameSongOrRemix(t, seed)) {
                    continue;
                }
                if (profile != null && profile.isFatigued(t, cooldownTracks, cooldownMs)) {
                    continue;
                }
                return t;
            }

            // Tier 2: Soft relaxation ladder (relax cooldown to last 5 tracks & 15m, STILL strictly reject seed/remixes)
            for (Track t : snapshot) {
                if (t == null) continue;
                if (seed != null && PlayerTasteProfile.isSameSongOrRemix(t, seed)) {
                    continue;
                }
                if (profile != null && profile.isFatigued(t, 5, 15 * 60_000L)) {
                    continue;
                }
                return t;
            }

            // Tier 3: Complete fatigue relaxation BUT NEVER repeat seed track or its remixes
            for (Track t : snapshot) {
                if (t == null) continue;
                if (seed != null && PlayerTasteProfile.isSameSongOrRemix(t, seed)) {
                    continue;
                }
                return t;
            }

            // If ALL tracks in trending are variants/remixes of the seed, return null rather than looping the seed
            return null;
        }, providerExecutor);
    }

    // =========================================================
    // MULTI-OBJECTIVE RANKING MATHEMATICS
    // =========================================================

    /**
     * Evaluates every candidate against the formula:
     * Score = 0.35 * GenreMatch + 0.30 * ArtistAffinity + 0.20 * Continuity + 0.15 * Discovery - Fatigue + CacheBonus
     */
    Track scoreAndPickBest(
            List<RecommendationCandidate> candidates,
            Track seed,
            PlayerTasteProfile profile
    ) {
        int cooldownTracks = plugin != null && plugin.getConfig() != null ? plugin.getConfig().getInt("recommendations.cooldown-tracks", 20) : 20;
        long cooldownMs = (plugin != null && plugin.getConfig() != null ? plugin.getConfig().getLong("recommendations.cooldown-minutes", 45) : 45) * 60_000L;
        long minDur = plugin != null && plugin.getConfig() != null ? plugin.getConfig().getLong("recommendations.min-duration-seconds", 60) : 60;
        long maxDur = plugin != null && plugin.getConfig() != null ? plugin.getConfig().getLong("recommendations.max-duration-seconds", 600) : 600;
        double explorationRatio = plugin != null && plugin.getConfig() != null ? plugin.getConfig().getDouble("recommendations.exploration-ratio", 0.20) : 0.20;
        double cacheBonusValue = plugin != null && plugin.getConfig() != null ? plugin.getConfig().getDouble("recommendations.cache-bonus", 0.05) : 0.05;

        boolean explore = RANDOM.nextDouble() < explorationRatio;

        List<RecommendationCandidate> scored = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();

        for (RecommendationCandidate c : candidates) {
            Track t = c.track();
            if (t == null || t.id() == null) continue;
            if (seenIds.contains(t.id())) continue;
            seenIds.add(t.id());

            // 0. STRICT SEED DUPLICATE FILTER: Never recommend the seed track or its remixes/slowed versions
            if (seed != null && PlayerTasteProfile.isSameSongOrRemix(t, seed)) {
                continue;
            }

            // 1. Duration guardrails
            if (t.durationSeconds() > 0) {
                if (t.durationSeconds() < minDur || t.durationSeconds() > maxDur) {
                    continue; // Discard meme clips or multi-hour mixes
                }
            }

            // 2. Strict fatigue check (never repeat tracks or duplicate songs heard in last 20 songs or 45 min)
            if (profile != null && profile.isFatigued(t, cooldownTracks, cooldownMs)) {
                continue;
            }

            // 3. Artist affinity score (0.0 to 1.0)
            double artistAffinity = profile != null ? profile.getArtistAffinity(t.artist()) : 0.2;

            // 4. Vibe continuity (keeps playback in a consistent Daily Mix cluster)
            double continuity = 0.5;
            if (seed != null) {
                if (ArtistNames.sameArtist(seed.artist(), t.artist())) {
                    continuity = 0.85;
                } else if (c.source() == RecommendationCandidate.Source.YOUTUBE_RADIO) {
                    continuity = 0.75; // YouTube RD algorithm already clustered it with seed
                }
            }

            // 5. Genre match prior (0.0 to 1.0)
            double genreMatch = (c.source() == RecommendationCandidate.Source.SPOTIFY_GENRE) ? 0.85 : 0.30;

            // 6. Discovery exploration bonus
            double discovery = (explore && artistAffinity < 0.2) ? 0.8 : 0.1;

            // 7. Base Multi-Objective Score
            double score = (0.35 * genreMatch)
                    + (0.30 * artistAffinity)
                    + (0.20 * continuity)
                    + (0.15 * discovery);

            // 8. Monopoly vs continuity: graded multiplier over occurrences in last 4 plays
            if (profile != null) {
                int occurrences = profile.getRecentArtistCount(t.artist());
                double monopolyMultiplier = getMonopolyMultiplier(occurrences);
                score *= monopolyMultiplier;
            }

            // 9. Cache bonus (already downloaded OGG saves download time)
            if (c.cachedLocally()) {
                score += cacheBonusValue;
            }

            scored.add(new RecommendationCandidate(t, score, c.source(), c.reason(), c.cachedLocally()));
        }

        if (scored.isEmpty()) {
            return null;
        }

        // Sort descending by score
        Collections.sort(scored);
        return scored.get(0).track();
    }

    private static String extractYouTubeId(Track track) {
        String id = track.id();
        if (id != null && id.matches("[A-Za-z0-9_-]{11}")) {
            return id;
        }
        String url = track.sourceUrl();
        if (url == null) return null;
        java.util.regex.Matcher m = Pattern.compile(
                "(?:v=|/v/|youtu\\.be/|/embed/)([A-Za-z0-9_-]{11})"
        ).matcher(url);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    public double getMonopolyMultiplier(int occurrences) {
        if (occurrences <= 0) return 1.00;
        java.util.List<Double> configured = (plugin != null && plugin.getConfig() != null)
                ? plugin.getConfig().getDoubleList("recommendations.monopoly.multipliers")
                : null;
        if (configured != null && !configured.isEmpty()) {
            if (occurrences >= configured.size()) {
                return configured.get(configured.size() - 1);
            }
            return configured.get(occurrences);
        }
        // Defaults: 0 -> 1.00, 1 -> 0.80, 2 -> 0.40, >= 3 -> 0.15
        if (occurrences == 1) return 0.80;
        if (occurrences == 2) return 0.40;
        return 0.15;
    }
}

package dev.vibesindev.vibesmusic.recommendation;

import dev.vibesindev.vibesmusic.VibesInDevPlugin;
import dev.vibesindev.vibesmusic.model.Track;
import dev.vibesindev.vibesmusic.spotify.SpotifyHttp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
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
    private final GenreResolver genreResolver;
    private final ExecutorService providerExecutor;

    public RecommendationEngine(VibesInDevPlugin plugin) {
        this(plugin, null);
    }

    public RecommendationEngine(VibesInDevPlugin plugin, GenreResolver genreResolver) {
        this.plugin = plugin;
        this.genreResolver = genreResolver != null ? genreResolver : new GenreResolver(plugin);
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

    public GenreResolver genreResolver() {
        return genreResolver;
    }

    public boolean isEngineV2Enabled(UUID playerUuid) {
        if (plugin == null || plugin.getConfig() == null) return false;
        if (plugin.getConfig().getBoolean("recommendations.engine-v2", false)) {
            return true;
        }
        int rollout = plugin.getConfig().getInt("recommendations.engine-v2-rollout-percent", 0);
        if (rollout <= 0) return false;
        if (rollout >= 100) return true;
        if (playerUuid == null) return false;
        int bucket = Math.floorMod(playerUuid.hashCode(), 100);
        return bucket < rollout;
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

        // Stream A & B: Local catalog + Spotify (if available) & Spotify micro-genre search
        if (seed != null) {
            futures.add(fetchSpotifyCandidates(seed, profile));
        }

        // Stream E: Familiar library tracks (playlists, likes, history, cached Spotify tracks)
        if (profile != null) {
            futures.add(fetchFamiliarCandidates(seed, profile));
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
     * Stream E: Familiar library tracks (playlists, likes, history, cached Spotify tracks).
     */
    CompletableFuture<List<RecommendationCandidate>> fetchFamiliarCandidates(Track seed, PlayerTasteProfile profile) {
        if (profile == null) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        return CompletableFuture.supplyAsync(() -> {
            List<RecommendationCandidate> candidates = new ArrayList<>();
            List<UUID> memberUuids = new ArrayList<>();
            if (profile.getSourceProfiles() != null && !profile.getSourceProfiles().isEmpty()) {
                for (PlayerTasteProfile p : profile.getSourceProfiles()) {
                    if (p != null && p.getPlayerUuid() != null) memberUuids.add(p.getPlayerUuid());
                }
            } else if (profile.getPlayerUuid() != null) {
                memberUuids.add(profile.getPlayerUuid());
            }

            int cooldownTracks = (plugin != null && plugin.getConfig() != null)
                    ? plugin.getConfig().getInt("recommendations.cooldown-tracks", 20) : 20;
            long cooldownMs = ((plugin != null && plugin.getConfig() != null)
                    ? plugin.getConfig().getLong("recommendations.cooldown-minutes", 45) : 45) * 60_000L;

            List<Track> pooled = new ArrayList<>();

            for (UUID uuid : memberUuids) {
                // 1. Player's custom playlists
                if (plugin != null && plugin.playlists() != null) {
                    Map<String, dev.vibesindev.vibesmusic.playlist.MusicPlaylistManager.CustomPlaylist> pls = plugin.playlists().getPlaylists(uuid);
                    if (pls != null) {
                        for (dev.vibesindev.vibesmusic.playlist.MusicPlaylistManager.CustomPlaylist cp : pls.values()) {
                            if (cp != null && cp.tracks() != null) {
                                pooled.addAll(cp.tracks());
                            }
                        }
                    }
                }

                // 2. Liked tracks from history
                if (plugin != null && plugin.cache() != null) {
                    List<Track> liked = plugin.cache().getLikedTracksForPlayer(uuid, 50);
                    if (liked != null) pooled.addAll(liked);
                }

                // 3. Top-played tracks in player_history (last 90 days)
                if (plugin != null && plugin.cache() != null) {
                    List<Track> topPlayed = plugin.cache().getTopPlayedTracksForPlayer(uuid, 50, 90L * 24 * 3600 * 1000L);
                    if (topPlayed != null) pooled.addAll(topPlayed);
                }

                // 4. Cached Spotify tracks (synced top/saved/recent)
                if (plugin != null && plugin.cache() != null) {
                    List<Track> spotifyTracks = plugin.cache().getPlayerSpotifyTracks(uuid, 100);
                    if (spotifyTracks != null) pooled.addAll(spotifyTracks);
                }
            }

            // Deduplicate across streams by canonical title + artist key & apply hard eligibility filters
            Set<String> seenKeys = new HashSet<>();
            List<Track> eligible = new ArrayList<>();

            for (Track t : pooled) {
                if (t == null) continue;
                if (seed != null && PlayerTasteProfile.isSameSongOrRemix(t, seed)) continue;
                if (profile.isFatigued(t, cooldownTracks, cooldownMs)) continue;
                if (t.id() != null && profile.isDisliked(t.id())) continue;

                String compKey = RecordingIdentity.compositionTitleKey(t.title(), t.artist());
                String normArtist = ArtistNames.normalize(t.artist());
                String dedupeKey = (!compKey.isEmpty() ? compKey : t.title().toLowerCase(Locale.ROOT)) + "::" + normArtist;

                if (seenKeys.add(dedupeKey)) {
                    eligible.add(t);
                }
            }

            // Shuffle and cap at ~15 candidates
            Collections.shuffle(eligible);
            int limit = Math.min(15, eligible.size());
            for (int i = 0; i < limit; i++) {
                Track t = eligible.get(i);
                candidates.add(new RecommendationCandidate(
                        t,
                        0.0,
                        RecommendationCandidate.Source.FAMILIAR,
                        "Familiar library track",
                        plugin != null && plugin.cache() != null && plugin.cache().hasAudio(t.id())
                ));
            }

            return candidates;
        }, providerExecutor).exceptionally(err -> Collections.emptyList());
    }

    /**
     * Stream A: Local catalog + Spotify (if available) & Stream B: Spotify micro-genre search.
     */
    CompletableFuture<List<RecommendationCandidate>> fetchSpotifyCandidates(Track seed, PlayerTasteProfile profile) {
        List<RecommendationCandidate> list = new CopyOnWriteArrayList<>();
        List<CompletableFuture<Void>> asyncFutures = new ArrayList<>();

        // Stream A: Local catalog same-artist tracks
        if (plugin != null && plugin.cache() != null && seed != null && seed.artist() != null) {
            List<Track> sameArtistTracks = plugin.cache().getTracksByArtist(seed.artist(), 3);
            if (sameArtistTracks != null) {
                for (Track t : sameArtistTracks) {
                    if (t != null && !PlayerTasteProfile.isSameSongOrRemix(t, seed)) {
                        list.add(new RecommendationCandidate(
                                t, 0.0, RecommendationCandidate.Source.SPOTIFY_ARTIST_TOP,
                                "local catalog + Spotify (if available): " + seed.artist(), plugin.cache().hasAudio(t.id())
                        ));
                    }
                }
            }
        }

        // Stream A: Spotify artist top tracks if seed artist Spotify ID is known and feature is available
        if (seed != null && seed.artist() != null && plugin != null && plugin.spotify() != null && plugin.spotify().isConfigured()) {
            String artistId = plugin.spotify().getKnownArtistId(seed.artist());
            if (artistId != null && plugin.spotify().isFeatureAvailable(SpotifyHttp.Feature.ARTIST_TOP_TRACKS)) {
                asyncFutures.add(plugin.spotify().getArtistTopTracks(artistId)
                        .thenAccept(topTracks -> {
                            if (topTracks != null) {
                                for (Track t : topTracks) {
                                    if (t != null && !PlayerTasteProfile.isSameSongOrRemix(t, seed)) {
                                        boolean exists = false;
                                        for (RecommendationCandidate c : list) {
                                            if (c.track() != null && RecordingIdentity.sameRecording(c.track(), t)) {
                                                exists = true;
                                                break;
                                            }
                                        }
                                        if (!exists) {
                                            list.add(new RecommendationCandidate(
                                                    t, 0.0, RecommendationCandidate.Source.SPOTIFY_ARTIST_TOP,
                                                    "local catalog + Spotify (if available): " + seed.artist(),
                                                    plugin.cache() != null && plugin.cache().hasAudio(t.id())
                                            ));
                                        }
                                    }
                                }
                            }
                        }).exceptionally(err -> null));
            }
        }

        // Stream B: Spotify Micro-genre search if profile has genres (Task 2.2: sample up to 3 genres without replacement)
        if (profile != null && !profile.getGenreDistribution().isEmpty()) {
            if (plugin != null && plugin.spotify() != null && plugin.spotify().isConfigured()
                    && plugin.spotify().isFeatureAvailable(SpotifyHttp.Feature.GENRE_SEARCH)) {
                double temp = (plugin.getConfig() != null)
                        ? plugin.getConfig().getDouble("recommendations.genre-sample-temperature", 1.0) : 1.0;
                int perGenreLimit = (plugin.getConfig() != null)
                        ? plugin.getConfig().getInt("recommendations.per-genre-limit", 12) : 12;

                List<String> sampledGenres = sampleGenres(profile.getGenreDistribution(), 3, temp, RANDOM);

                if (!sampledGenres.isEmpty()) {
                    for (String genre : sampledGenres) {
                        asyncFutures.add(plugin.spotify().searchGenreTracks(genre, perGenreLimit)
                                .thenAccept(genreTracks -> {
                                    if (genreTracks != null) {
                                        for (Track gt : genreTracks) {
                                            if (gt != null && !PlayerTasteProfile.isSameSongOrRemix(gt, seed)) {
                                                list.add(new RecommendationCandidate(
                                                        gt, 0.0, RecommendationCandidate.Source.SPOTIFY_GENRE,
                                                        "Spotify micro-genre: " + genre, plugin.cache() != null && plugin.cache().hasAudio(gt.id())
                                                ));
                                            }
                                        }
                                    }
                                }).exceptionally(err -> null));
                    }
                }
            }
        }

        if (!asyncFutures.isEmpty()) {
            return CompletableFuture.allOf(asyncFutures.toArray(new CompletableFuture[0]))
                    .thenApply(v -> list)
                    .exceptionally(err -> list);
        }

        return CompletableFuture.completedFuture(list);
    }

    /**
     * Samples up to maxCount genres without replacement, weighted by P(genre)^(1/T).
     * If temperature <= 0, falls back to argmax (top N by probability).
     */
    public static List<String> sampleGenres(Map<String, Double> distribution, int maxCount, double temperature, Random random) {
        if (distribution == null || distribution.isEmpty() || maxCount <= 0) {
            return Collections.emptyList();
        }
        Random rng = (random != null) ? random : RANDOM;

        if (temperature <= 0.0) {
            List<Map.Entry<String, Double>> sorted = new ArrayList<>(distribution.entrySet());
            sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
            List<String> result = new ArrayList<>();
            for (int i = 0; i < Math.min(maxCount, sorted.size()); i++) {
                result.add(sorted.get(i).getKey());
            }
            return result;
        }

        Map<String, Double> pool = new HashMap<>();
        for (Map.Entry<String, Double> e : distribution.entrySet()) {
            if (e.getKey() != null && !e.getKey().isBlank() && e.getValue() != null && e.getValue() > 0.0) {
                double transformed = Math.pow(e.getValue(), 1.0 / temperature);
                if (transformed > 0.0 && !Double.isNaN(transformed) && !Double.isInfinite(transformed)) {
                    pool.put(e.getKey(), transformed);
                }
            }
        }

        List<String> sampled = new ArrayList<>();
        while (sampled.size() < maxCount && !pool.isEmpty()) {
            double totalWeight = 0.0;
            for (double w : pool.values()) totalWeight += w;
            if (totalWeight <= 0.0) {
                String fallbackKey = pool.keySet().iterator().next();
                sampled.add(fallbackKey);
                pool.remove(fallbackKey);
                continue;
            }

            double r = rng.nextDouble() * totalWeight;
            double running = 0.0;
            String pickedKey = null;
            for (Map.Entry<String, Double> entry : pool.entrySet()) {
                running += entry.getValue();
                if (running >= r) {
                    pickedKey = entry.getKey();
                    break;
                }
            }
            if (pickedKey == null) {
                pickedKey = pool.keySet().iterator().next();
            }
            sampled.add(pickedKey);
            pool.remove(pickedKey);
        }
        return sampled;
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
        UUID uuid = profile != null ? profile.getPlayerUuid() : null;
        if (isEngineV2Enabled(uuid)) {
            return scoreAndPickBestV2(candidates, seed, profile);
        }
        return scoreAndPickBestLegacy(candidates, seed, profile);
    }

    public static RecommendationMode sampleMode(double familiarProb, double similarProb, double exploreProb, boolean allowExplore, Random random) {
        Random rng = (random != null) ? random : RANDOM;
        double f = Math.max(0.0, familiarProb);
        double s = Math.max(0.0, similarProb);
        double e = allowExplore ? Math.max(0.0, exploreProb) : 0.0;
        double total = f + s + e;
        if (total <= 0.0) {
            return RecommendationMode.SIMILAR;
        }
        double r = rng.nextDouble() * total;
        if (r < f) {
            return RecommendationMode.FAMILIAR;
        } else if (r < f + s) {
            return RecommendationMode.SIMILAR;
        } else {
            return RecommendationMode.EXPLORE;
        }
    }

    public static double[] getModeWeights(RecommendationMode mode, org.bukkit.configuration.file.FileConfiguration config) {
        if (config != null) {
            String prefix = "recommendations.mode-weights." + mode.name().toLowerCase(Locale.ROOT) + ".";
            double g = config.getDouble(prefix + "genre", -1.0);
            if (g >= 0.0) {
                return new double[] {
                        g,
                        config.getDouble(prefix + "artist", 0.20),
                        config.getDouble(prefix + "continuity", 0.25),
                        config.getDouble(prefix + "familiarity", 0.05),
                        config.getDouble(prefix + "quality", 0.10),
                        config.getDouble(prefix + "novelty", 0.10)
                };
            }
        }
        return switch (mode) {
            case FAMILIAR -> new double[] { 0.10, 0.25, 0.15, 0.40, 0.10, 0.00 };
            case SIMILAR -> new double[] { 0.30, 0.20, 0.25, 0.05, 0.10, 0.10 };
            case EXPLORE -> new double[] { 0.20, 0.05, 0.25, 0.00, 0.15, 0.35 };
        };
    }

    public static double calculateCandidateScore(
            double genreCosine, boolean hasGenre,
            double artistAffinity,
            double continuity,
            double familiarity,
            double quality,
            double novelty,
            double[] weights,
            double minFeatureCoverage,
            double monopolyMultiplier,
            boolean cachedLocally,
            double cacheBonus
    ) {
        if (weights == null || weights.length < 6) {
            weights = new double[] { 0.30, 0.20, 0.25, 0.05, 0.10, 0.10 };
        }

        double availableWeight = 0.0;
        double weightedSum = 0.0;
        int availableCount = 0;

        if (hasGenre && weights[0] > 0.0) {
            availableWeight += weights[0];
            weightedSum += weights[0] * Math.max(0.0, Math.min(1.0, genreCosine));
            availableCount++;
        }
        if (weights[1] > 0.0) {
            availableWeight += weights[1];
            weightedSum += weights[1] * Math.max(0.0, Math.min(1.0, artistAffinity));
            availableCount++;
        }
        if (weights[2] > 0.0) {
            availableWeight += weights[2];
            weightedSum += weights[2] * Math.max(0.0, Math.min(1.0, continuity));
            availableCount++;
        }
        if (weights[3] > 0.0) {
            availableWeight += weights[3];
            weightedSum += weights[3] * Math.max(0.0, Math.min(1.0, familiarity));
            availableCount++;
        }
        if (weights[4] > 0.0) {
            availableWeight += weights[4];
            weightedSum += weights[4] * Math.max(0.0, Math.min(1.0, quality));
            availableCount++;
        }
        if (weights[5] > 0.0) {
            availableWeight += weights[5];
            weightedSum += weights[5] * Math.max(0.0, Math.min(1.0, novelty));
            availableCount++;
        }

        double totalConfiguredWeight = weights[0] + weights[1] + weights[2] + weights[3] + weights[4] + weights[5];
        if (totalConfiguredWeight <= 0.0) totalConfiguredWeight = 1.0;
        double featureCoverage = availableWeight / totalConfiguredWeight;

        if (availableCount < 2 || featureCoverage < minFeatureCoverage) {
            return -1.0; // INSUFFICIENT_FEATURES
        }

        double baseScore = availableWeight > 0.0 ? (weightedSum / availableWeight) : 0.0;
        double confidence = 0.85 + (0.15 * featureCoverage);
        double score = baseScore * confidence;
        score *= monopolyMultiplier;
        if (cachedLocally) {
            score += cacheBonus;
        }
        return score;
    }

    private record CandidateEvaluation(
            RecommendationCandidate candidate,
            double genreCosine,
            boolean hasGenre,
            double artistAffinity,
            double continuity,
            double familiarity,
            double quality,
            double novelty,
            double monopolyMultiplier
    ) {}

    Track scoreAndPickBestV2(
            List<RecommendationCandidate> candidates,
            Track seed,
            PlayerTasteProfile profile
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }

        // Background warm candidate genres so future scoring passes hit cache
        if (genreResolver != null) {
            List<String> artists = new ArrayList<>();
            for (RecommendationCandidate c : candidates) {
                if (c.track() != null && c.track().artist() != null) {
                    artists.add(c.track().artist());
                }
            }
            genreResolver.warmCandidateGenres(artists);
        }

        int cooldownTracks = plugin != null && plugin.getConfig() != null ? plugin.getConfig().getInt("recommendations.cooldown-tracks", 20) : 20;
        long cooldownMs = (plugin != null && plugin.getConfig() != null ? plugin.getConfig().getLong("recommendations.cooldown-minutes", 45) : 45) * 60_000L;
        long minDur = plugin != null && plugin.getConfig() != null ? plugin.getConfig().getLong("recommendations.min-duration-seconds", 60) : 60;
        long maxDur = plugin != null && plugin.getConfig() != null ? plugin.getConfig().getLong("recommendations.max-duration-seconds", 600) : 600;
        double cacheBonusValue = plugin != null && plugin.getConfig() != null ? plugin.getConfig().getDouble("recommendations.cache-bonus", 0.02) : 0.02;
        double exploreMinContinuity = plugin != null && plugin.getConfig() != null ? plugin.getConfig().getDouble("recommendations.explore-min-continuity", 0.45) : 0.45;
        double minFeatureCoverage = plugin != null && plugin.getConfig() != null ? plugin.getConfig().getDouble("recommendations.min-feature-coverage", 0.35) : 0.35;

        // Sample mode
        double wFam = plugin != null && plugin.getConfig() != null ? plugin.getConfig().getDouble("recommendations.modes.familiar", 0.30) : 0.30;
        double wSim = plugin != null && plugin.getConfig() != null ? plugin.getConfig().getDouble("recommendations.modes.similar", 0.50) : 0.50;
        double wExp = plugin != null && plugin.getConfig() != null ? plugin.getConfig().getDouble("recommendations.modes.explore", 0.20) : 0.20;

        int sessionTracks = profile != null ? profile.getFatigueHistory().size() : 0;
        boolean allowExplore = sessionTracks >= 3;
        RecommendationMode requestedMode = sampleMode(wFam, wSim, wExp, allowExplore, RANDOM);
        RecommendationMode effectiveMode = requestedMode;

        List<CandidateEvaluation> eligible = new ArrayList<>();
        List<CandidateEvaluation> familiarPool = new ArrayList<>();
        List<CandidateEvaluation> similarPool = new ArrayList<>();
        List<CandidateEvaluation> explorePool = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();

        for (RecommendationCandidate c : candidates) {
            Track t = c.track();
            if (t == null || t.id() == null) continue;
            if (seenIds.contains(t.id())) continue;
            seenIds.add(t.id());

            // 0. STRICT SEED DUPLICATE FILTER
            if (seed != null && PlayerTasteProfile.isSameSongOrRemix(t, seed)) {
                continue;
            }

            // 1. Duration guardrails
            if (t.durationSeconds() > 0) {
                if (t.durationSeconds() < minDur || t.durationSeconds() > maxDur) {
                    continue;
                }
            }

            // 2. Strict fatigue check
            if (profile != null && profile.isFatigued(t, cooldownTracks, cooldownMs)) {
                continue;
            }

            // 3. Artist affinity
            double artistAffinity = profile != null ? profile.getArtistAffinity(t.artist()) : 0.2;

            // 4. Vibe continuity
            double continuity = 0.40;
            if (seed != null) {
                if (ArtistNames.sameArtist(seed.artist(), t.artist())) {
                    continuity = 0.85;
                } else if (c.source() == RecommendationCandidate.Source.YOUTUBE_RADIO) {
                    continuity = 0.75;
                } else if (c.source() == RecommendationCandidate.Source.SERVER_COLLABORATIVE) {
                    continuity = 0.70;
                }
            }

            // 5. Genre similarity
            List<String> candidateGenres = (genreResolver != null && t.artist() != null)
                    ? genreResolver.getCachedGenres(t.artist()) : Collections.emptyList();
            boolean hasGenre = !candidateGenres.isEmpty();
            double genreCosine = (profile != null && hasGenre)
                    ? profile.calculateGenreCosineSimilarity(candidateGenres) : -1.0;
            if (genreCosine < 0.0) hasGenre = false;

            // 6. Familiarity feature
            double familiarity = 0.0;
            if (c.source() == RecommendationCandidate.Source.FAMILIAR) {
                familiarity = 1.0;
            } else if (artistAffinity >= 0.5) {
                familiarity = 0.70;
            } else if (genreCosine >= 0.2) {
                familiarity = 0.40;
            }

            // 7. Quality
            double quality = 1.0;

            // 8. Novelty
            double novelty = Math.max(0.0, Math.min(1.0, 1.0 - Math.max(familiarity, artistAffinity)));

            // 9. Monopoly multiplier
            int occurrences = profile != null ? profile.getRecentArtistCount(t.artist()) : 0;
            double monopolyMultiplier = getMonopolyMultiplier(occurrences);

            CandidateEvaluation eval = new CandidateEvaluation(
                    c, genreCosine, hasGenre, artistAffinity, continuity, familiarity, quality, novelty, monopolyMultiplier
            );
            eligible.add(eval);

            if (c.source() == RecommendationCandidate.Source.FAMILIAR) {
                familiarPool.add(eval);
            }
            if (artistAffinity >= 0.2 || (hasGenre && genreCosine >= 0.4)
                    || c.source() == RecommendationCandidate.Source.SPOTIFY_GENRE
                    || c.source() == RecommendationCandidate.Source.YOUTUBE_RADIO
                    || c.source() == RecommendationCandidate.Source.SERVER_COLLABORATIVE) {
                similarPool.add(eval);
            }
            if (artistAffinity < 0.2 && continuity >= exploreMinContinuity
                    && (profile == null || !profile.isRecentArtist(t.artist()))) {
                explorePool.add(eval);
            }
        }

        if (eligible.isEmpty()) {
            return null;
        }

        // Select mode pool with fallbacks
        List<CandidateEvaluation> activePool;
        if (effectiveMode == RecommendationMode.FAMILIAR) {
            if (!familiarPool.isEmpty()) {
                activePool = familiarPool;
            } else {
                effectiveMode = RecommendationMode.SIMILAR;
                activePool = !similarPool.isEmpty() ? similarPool : eligible;
            }
        } else if (effectiveMode == RecommendationMode.EXPLORE) {
            if (!explorePool.isEmpty()) {
                activePool = explorePool;
            } else {
                effectiveMode = RecommendationMode.SIMILAR;
                activePool = !similarPool.isEmpty() ? similarPool : eligible;
            }
        } else {
            activePool = !similarPool.isEmpty() ? similarPool : eligible;
        }

        double[] weights = getModeWeights(effectiveMode, plugin != null ? plugin.getConfig() : null);

        List<RecommendationCandidate> scored = new ArrayList<>();
        for (CandidateEvaluation ev : activePool) {
            double score = calculateCandidateScore(
                    ev.genreCosine, ev.hasGenre,
                    ev.artistAffinity, ev.continuity, ev.familiarity, ev.quality, ev.novelty,
                    weights, minFeatureCoverage, ev.monopolyMultiplier, ev.candidate.cachedLocally(), cacheBonusValue
            );
            if (score >= 0.0) {
                scored.add(new RecommendationCandidate(
                        ev.candidate.track(), score, ev.candidate.source(),
                        ev.candidate.reason() + " [" + effectiveMode.name() + "]", ev.candidate.cachedLocally()
                ));
            }
        }

        if (scored.isEmpty()) {
            return null;
        }

        // Sort descending by score
        Collections.sort(scored);
        return scored.get(0).track();
    }

    Track scoreAndPickBestLegacy(
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

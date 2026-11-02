package dev.vibesindev.vibesmusic.recommendation;

import dev.vibesindev.vibesmusic.VibesInDevPlugin;
import dev.vibesindev.vibesmusic.cache.TrackCache;
import dev.vibesindev.vibesmusic.model.Track;
import dev.vibesindev.vibesmusic.spotify.SpotifyHttp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
        boolean shadow = (plugin != null && plugin.getConfig() != null)
                ? plugin.getConfig().getBoolean("recommendations.ranker.shadow", true) : true;
        double alpha = (plugin != null && plugin.getConfig() != null)
                ? plugin.getConfig().getDouble("recommendations.ranker.blend-alpha", 0.5) : 0.5;
        this.learnedRanker = new LearnedRanker(shadow, alpha);

        if (plugin != null && plugin.getConfig() != null) {
            String weightsPath = plugin.getConfig().getString("recommendations.ranker.weights-file", "");
            if (weightsPath != null && !weightsPath.isBlank()) {
                java.io.File wFile = new java.io.File(weightsPath);
                if (!wFile.isAbsolute() && plugin.getDataFolder() != null) {
                    wFile = new java.io.File(plugin.getDataFolder(), weightsPath);
                }
                this.learnedRanker.loadFromFile(wFile);
            }
        }
    }

    private final LearnedRanker learnedRanker;

    public LearnedRanker learnedRanker() {
        return learnedRanker;
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
    public CompletableFuture<List<RankedPick>> recommendNextRanked(Track seed, PlayerTasteProfile profile) {
        return recommendNextRanked(seed, profile, null);
    }

    public CompletableFuture<List<RankedPick>> recommendNextRanked(Track seed, PlayerTasteProfile profile, SessionContext sessionContext) {
        if (seed == null) {
            // Task 4.4: Cold start without seed — seed from server trending filtered through Stream C
            Track trendingSeed = null;
            if (plugin != null && plugin.cache() != null) {
                List<Track> trending = plugin.cache().getTrendingTracks(1);
                if (trending != null && !trending.isEmpty()) {
                    trendingSeed = trending.get(0);
                }
            }
            if (trendingSeed != null && plugin != null && plugin.ytDlp() != null) {
                return recommendNextRanked(trendingSeed, profile, sessionContext);
            }
            return fallbackTrendingRanked(seed, profile);
        }

        List<CompletableFuture<List<RecommendationCandidate>>> futures = new ArrayList<>();

        // Stream C: YouTube Algorithmic Radio Mix (fast, neural cluster) with seed rotation
        if (plugin != null && plugin.ytDlp() != null) {
            futures.add(fetchYouTubeRadioCandidates(seed, sessionContext));
        }

        // Task 4.4: Cold start enrichment (<5 plays, no Spotify link) — also seed trending through Stream C
        if (profile != null && profile.isColdStart() && plugin != null && plugin.cache() != null && plugin.ytDlp() != null) {
            List<Track> trending = plugin.cache().getTrendingTracks(1);
            if (trending != null && !trending.isEmpty()) {
                Track trendingSeed = trending.get(0);
                if (!trendingSeed.id().equals(seed.id())) {
                    futures.add(fetchYouTubeRadioCandidates(trendingSeed, sessionContext));
                }
            }
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

        return gatherAndPickRanked(futures, seed, profile, sessionContext);
    }

    public CompletableFuture<Track> recommendNext(Track seed, PlayerTasteProfile profile) {
        return recommendNextRanked(seed, profile)
                .thenApply(picks -> (picks != null && !picks.isEmpty()) ? picks.get(0).track() : null);
    }

    /**
     * Gathers candidates across parallel streams with 3 000 ms per-stream timeout and 6 000 ms total deadline.
     */
    public CompletableFuture<List<RankedPick>> gatherAndPickRanked(
            List<CompletableFuture<List<RecommendationCandidate>>> streamFutures,
            Track seed,
            PlayerTasteProfile profile
    ) {
        return gatherAndPickRanked(streamFutures, seed, profile, null);
    }

    public CompletableFuture<List<RankedPick>> gatherAndPickRanked(
            List<CompletableFuture<List<RecommendationCandidate>>> streamFutures,
            Track seed,
            PlayerTasteProfile profile,
            SessionContext sessionContext
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
                        return fallbackTrendingRanked(seed, profile);
                    }

                    // Score and rank candidates
                    List<RankedPick> picks = scoreAndRankCandidates(candidates, seed, profile, sessionContext);
                    if (picks != null && !picks.isEmpty()) {
                        return CompletableFuture.completedFuture(picks);
                    }
                    return fallbackTrendingRanked(seed, profile);
                })
                .exceptionallyCompose(err -> fallbackTrendingRanked(seed, profile));
    }

    public CompletableFuture<Track> gatherAndPick(
            List<CompletableFuture<List<RecommendationCandidate>>> streamFutures,
            Track seed,
            PlayerTasteProfile profile
    ) {
        return gatherAndPickRanked(streamFutures, seed, profile)
                .thenApply(picks -> (picks != null && !picks.isEmpty()) ? picks.get(0).track() : null);
    }

    // =========================================================
    // CANDIDATE GENERATION STREAMS
    // =========================================================

    /**
     * Stream C: Fetches YouTube Radio Mix candidates (RD{id}) via flat-playlist.
     */
    private CompletableFuture<List<RecommendationCandidate>> fetchYouTubeRadioCandidates(Track seed) {
        return fetchYouTubeRadioCandidates(seed, null);
    }

    private CompletableFuture<List<RecommendationCandidate>> fetchYouTubeRadioCandidates(Track seed, SessionContext sessionContext) {
        Track radioSeed = (sessionContext != null) ? sessionContext.getStreamCSeed(seed) : seed;
        if (radioSeed == null) radioSeed = seed;
        final Track effectiveSeed = radioSeed;
        String ytId = extractYouTubeId(effectiveSeed);
        if (ytId == null) {
            // Seed is a non-YouTube track (e.g. Spotify track).
            // Search YouTube to find the video ID of the seed track so we can query its real Radio Mix
            return plugin.ytDlp().search(effectiveSeed.artist() + " " + effectiveSeed.title())
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
                                if (t != null && !PlayerTasteProfile.isSameSongOrRemix(t, effectiveSeed)) {
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
                            if (t != null && !PlayerTasteProfile.isSameSongOrRemix(t, effectiveSeed)) {
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
     * Fallback: Surfaces trending / frequently played tracks across the server using the relaxation ladder.
     */
    public CompletableFuture<List<RankedPick>> fallbackTrendingRanked(Track seed, PlayerTasteProfile profile) {
        if (plugin == null || plugin.cache() == null) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        return CompletableFuture.supplyAsync(() -> {
            List<Track> trending = plugin.cache().getTrendingTracks(10);
            if (trending == null || trending.isEmpty()) {
                return Collections.emptyList();
            }

            int minEligible = plugin.getConfig() != null ? plugin.getConfig().getInt("recommendations.min-eligible", 3) : 3;
            int baseCooldownTracks = plugin.getConfig() != null ? plugin.getConfig().getInt("recommendations.cooldown-tracks", 20) : 20;
            long baseCooldownMs = (plugin.getConfig() != null ? plugin.getConfig().getLong("recommendations.cooldown-minutes", 45) : 45) * 60_000L;
            long baseMinDur = plugin.getConfig() != null ? plugin.getConfig().getLong("recommendations.min-duration-seconds", 60) : 60;
            if (baseMinDur <= 0) baseMinDur = 60;
            long baseMaxDur = plugin.getConfig() != null ? plugin.getConfig().getLong("recommendations.max-duration-seconds", 600) : 600;
            if (baseMaxDur <= 0) baseMaxDur = 600;

            List<Track> chosen = Collections.emptyList();
            int chosenLevel = 0;

            for (int level = 0; level <= 4; level++) {
                int curCooldownTracks = baseCooldownTracks;
                long curCooldownMs = baseCooldownMs;
                long curMinDur = baseMinDur;
                long curMaxDur = baseMaxDur;

                if (level == 1) {
                    curCooldownMs = baseCooldownMs / 2;
                } else if (level == 2) {
                    curCooldownTracks = Math.min(10, baseCooldownTracks);
                    curCooldownMs = 15 * 60_000L;
                } else if (level == 3) {
                    curCooldownTracks = Math.min(10, baseCooldownTracks);
                    curCooldownMs = 15 * 60_000L;
                } else if (level == 4) {
                    curCooldownTracks = Math.min(10, baseCooldownTracks);
                    curCooldownMs = 15 * 60_000L;
                    curMinDur = (long) (baseMinDur * 0.5);
                    curMaxDur = (long) (baseMaxDur * 1.5);
                }

                List<Track> levelTracks = new ArrayList<>();
                for (Track t : trending) {
                    if (t == null || t.id() == null) continue;

                    // HARD: Seed duplicate/remix
                    if (seed != null && PlayerTasteProfile.isSameSongOrRemix(t, seed)) continue;

                    // HARD: Disliked
                    if (profile != null && profile.isDisliked(t.id())) continue;

                    // HARD: Blacklist regex & duration guardrails
                    if (CandidateFilter.isHardRejected(t.title(), t.durationSeconds(), RecommendationCandidate.Source.SERVER_TRENDING, curMinDur, curMaxDur)) {
                        continue;
                    }

                    // SOFT: Fatigue cooldown
                    if (profile != null && profile.isFatigued(t, curCooldownTracks, curCooldownMs)) continue;

                    levelTracks.add(t);
                }

                chosen = levelTracks;
                chosenLevel = level;

                if (levelTracks.size() >= minEligible) {
                    break;
                }
            }

            if (chosen.isEmpty()) {
                return Collections.emptyList();
            }

            List<RankedPick> picks = new ArrayList<>();
            int count = Math.min(3, chosen.size());
            for (int i = 0; i < count; i++) {
                Track t = chosen.get(i);
                picks.add(new RankedPick(
                        t,
                        RecommendationMode.SIMILAR,
                        RecommendationCandidate.Source.SERVER_TRENDING,
                        "Trending server track [ladder L" + chosenLevel + "]",
                        0.50 - (i * 0.05)
                ));
            }
            return picks;
        }, providerExecutor);
    }

    public CompletableFuture<Track> fallbackTrending(Track seed, PlayerTasteProfile profile) {
        return fallbackTrendingRanked(seed, profile)
                .thenApply(picks -> (picks != null && !picks.isEmpty()) ? picks.get(0).track() : null);
    }

    // =========================================================
    // MULTI-OBJECTIVE RANKING MATHEMATICS
    // =========================================================

    /**
     * Evaluates every candidate against the formula:
     * Score = 0.35 * GenreMatch + 0.30 * ArtistAffinity + 0.20 * Continuity + 0.15 * Discovery - Fatigue + CacheBonus
     */
    public List<RankedPick> scoreAndRankCandidates(
            List<RecommendationCandidate> candidates,
            Track seed,
            PlayerTasteProfile profile
    ) {
        return scoreAndRankCandidates(candidates, seed, profile, null);
    }

    public List<RankedPick> scoreAndRankCandidates(
            List<RecommendationCandidate> candidates,
            Track seed,
            PlayerTasteProfile profile,
            SessionContext sessionContext
    ) {
        UUID uuid = profile != null ? profile.getPlayerUuid() : null;
        if (isEngineV2Enabled(uuid)) {
            return scoreAndRankCandidatesV2(candidates, seed, profile, sessionContext, RANDOM);
        }
        Track best = scoreAndPickBestLegacy(candidates, seed, profile);
        if (best != null) {
            return List.of(new RankedPick(best, RecommendationMode.SIMILAR, RecommendationCandidate.Source.DISCOVERY, "Legacy engine pick", 0.50));
        }
        return Collections.emptyList();
    }

    Track scoreAndPickBest(
            List<RecommendationCandidate> candidates,
            Track seed,
            PlayerTasteProfile profile
    ) {
        List<RankedPick> picks = scoreAndRankCandidates(candidates, seed, profile);
        return !picks.isEmpty() ? picks.get(0).track() : null;
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

    public static double calculateFamiliarity(
            RecommendationCandidate candidate,
            double artistAffinity,
            double genreCosine,
            boolean isLikedOrSaved,
            boolean isPreviouslyCompleted,
            boolean isSpotifyTopOrRecent
    ) {
        if (isLikedOrSaved) {
            return 1.0;
        }
        if (candidate != null && candidate.source() == RecommendationCandidate.Source.FAMILIAR) {
            return 1.0;
        }
        if (isPreviouslyCompleted) {
            return 0.9;
        }
        if (isSpotifyTopOrRecent) {
            return 0.8;
        }
        if (artistAffinity >= 0.5) {
            return 0.7;
        }
        if (genreCosine >= 0.20) {
            return 0.4;
        }
        return 0.0;
    }

    public static double calculateNovelty(double familiarity, double artistAffinity) {
        double maxKnown = Math.max(Math.max(0.0, familiarity), Math.max(0.0, artistAffinity));
        return Math.max(0.0, Math.min(1.0, 1.0 - maxKnown));
    }

    public static int extractReleaseYear(String text) {
        if (text == null || text.isBlank()) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b(19[5-9]\\d|20[0-2]\\d)\\b").matcher(text);
        int lastMatch = 0;
        while (m.find()) {
            try {
                int y = Integer.parseInt(m.group(1));
                if (y >= 1950 && y <= 2030) {
                    lastMatch = y;
                }
            } catch (NumberFormatException ignored) {}
        }
        return lastMatch;
    }

    public static double calculateRecentReleaseScore(int year, int referenceYear) {
        if (year < 1950 || year > referenceYear + 2) return 0.0;
        int age = Math.max(0, referenceYear - year);
        if (age <= 1) return 1.0;
        if (age <= 2) return 0.85;
        if (age <= 4) return 0.65;
        if (age <= 7) return 0.40;
        if (age <= 10) return 0.20;
        return 0.05;
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
        return calculateCandidateScore(
                genreCosine, hasGenre,
                artistAffinity,
                continuity,
                familiarity,
                quality,
                novelty,
                0.0, false,
                weights, minFeatureCoverage, monopolyMultiplier, cachedLocally, cacheBonus
        );
    }

    public static double calculateCandidateScore(
            double genreCosine, boolean hasGenre,
            double artistAffinity,
            double continuity,
            double familiarity,
            double quality,
            double novelty,
            double recentRelease, boolean hasRecentRelease,
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
        if (hasRecentRelease && weights.length > 6 && weights[6] > 0.0) {
            availableWeight += weights[6];
            weightedSum += weights[6] * Math.max(0.0, Math.min(1.0, recentRelease));
            availableCount++;
        }

        double totalConfiguredWeight = 0.0;
        for (double w : weights) totalConfiguredWeight += w;
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

    public static String buildExplainabilityReason(
            RecommendationMode mode,
            RecommendationCandidate.Source source,
            double genreCosine, boolean hasGenre,
            double artistAffinity,
            double continuity,
            double familiarity,
            double quality,
            double novelty,
            double[] weights
    ) {
        record Term(String name, double val) implements Comparable<Term> {
            @Override
            public int compareTo(Term o) {
                return Double.compare(o.val, this.val);
            }
        }

        List<Term> terms = new ArrayList<>(6);
        if (hasGenre && weights != null && weights.length > 0 && weights[0] > 0.0) {
            terms.add(new Term("genre match", weights[0] * Math.max(0.0, genreCosine)));
        }
        if (weights != null && weights.length > 1 && weights[1] > 0.0) {
            terms.add(new Term("artist affinity", weights[1] * Math.max(0.0, artistAffinity)));
        }
        if (weights != null && weights.length > 2 && weights[2] > 0.0) {
            terms.add(new Term("vibe continuity", weights[2] * Math.max(0.0, continuity)));
        }
        if (weights != null && weights.length > 3 && weights[3] > 0.0) {
            terms.add(new Term("familiarity", weights[3] * Math.max(0.0, familiarity)));
        }
        if (weights != null && weights.length > 4 && weights[4] > 0.0) {
            terms.add(new Term("audio quality", weights[4] * Math.max(0.0, quality)));
        }
        if (weights != null && weights.length > 5 && weights[5] > 0.0) {
            terms.add(new Term("novelty discovery", weights[5] * Math.max(0.0, novelty)));
        }

        Collections.sort(terms);

        String top1 = !terms.isEmpty() ? terms.get(0).name() : "relevance";
        String top2 = terms.size() > 1 ? terms.get(1).name() : null;

        String termsStr = (top2 != null) ? (top1 + " & " + top2) : top1;
        return mode.name() + " mode via " + source.name() + " (" + termsStr + ")";
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
        List<RankedPick> picks = scoreAndRankCandidatesV2(candidates, seed, profile, RANDOM);
        return !picks.isEmpty() ? picks.get(0).track() : null;
    }

    List<RankedPick> scoreAndRankCandidatesV2(
            List<RecommendationCandidate> candidates,
            Track seed,
            PlayerTasteProfile profile,
            Random random
    ) {
        return scoreAndRankCandidatesV2(candidates, seed, profile, null, random);
    }

    List<RankedPick> scoreAndRankCandidatesV2(
            List<RecommendationCandidate> candidates,
            Track seed,
            PlayerTasteProfile profile,
            SessionContext sessionContext,
            Random random
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }
        long startTime = System.currentTimeMillis();
        Random rng = (random != null) ? random : RANDOM;

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

        int minEligible = plugin != null && plugin.getConfig() != null ? plugin.getConfig().getInt("recommendations.min-eligible", 3) : 3;
        int baseCooldownTracks = plugin != null && plugin.getConfig() != null ? plugin.getConfig().getInt("recommendations.cooldown-tracks", 20) : 20;
        long baseCooldownMs = (plugin != null && plugin.getConfig() != null ? plugin.getConfig().getLong("recommendations.cooldown-minutes", 45) : 45) * 60_000L;
        long baseMinDur = plugin != null && plugin.getConfig() != null ? plugin.getConfig().getLong("recommendations.min-duration-seconds", 60) : 60;
        if (baseMinDur <= 0) baseMinDur = 60;
        long baseMaxDur = plugin != null && plugin.getConfig() != null ? plugin.getConfig().getLong("recommendations.max-duration-seconds", 600) : 600;
        if (baseMaxDur <= 0) baseMaxDur = 600;
        double cacheBonusValue = plugin != null && plugin.getConfig() != null ? plugin.getConfig().getDouble("recommendations.cache-bonus", 0.02) : 0.02;
        double exploreMinContinuity = plugin != null && plugin.getConfig() != null ? plugin.getConfig().getDouble("recommendations.explore-min-continuity", 0.45) : 0.45;
        double minFeatureCoverage = plugin != null && plugin.getConfig() != null ? plugin.getConfig().getDouble("recommendations.min-feature-coverage", 0.35) : 0.35;

        // Sample mode
        double wFam = plugin != null && plugin.getConfig() != null ? plugin.getConfig().getDouble("recommendations.modes.familiar", 0.30) : 0.30;
        double wSim = plugin != null && plugin.getConfig() != null ? plugin.getConfig().getDouble("recommendations.modes.similar", 0.50) : 0.50;
        double wExp = plugin != null && plugin.getConfig() != null ? plugin.getConfig().getDouble("recommendations.modes.explore", 0.20) : 0.20;

        boolean adaptiveExplore = plugin != null && plugin.getConfig() != null
                && plugin.getConfig().getBoolean("recommendations.exploration.adaptive", false);
        if (adaptiveExplore && profile != null) {
            double adaptedExp = profile.getExploreRatio();
            if (adaptedExp > 0.0) {
                wExp = adaptedExp;
                double remaining = Math.max(0.0, 1.0 - wExp);
                double baseRest = (wFam + wSim) > 0.0 ? (wFam + wSim) : 0.80;
                wFam = remaining * (wFam / baseRest);
                wSim = remaining * (wSim / baseRest);
            }
        }

        int sessionTracks = profile != null ? profile.getFatigueHistory().size() : 0;
        boolean allowExplore = sessionTracks >= 3;
        RecommendationMode requestedMode = sampleMode(wFam, wSim, wExp, allowExplore, rng);
        RecommendationMode effectiveMode = requestedMode;
        double modeProb = switch (requestedMode) {
            case FAMILIAR -> wFam;
            case EXPLORE -> allowExplore ? wExp : 0.0;
            default -> wSim;
        };

        Set<String> likedKeys = new HashSet<>();
        Set<String> spotifyKeys = new HashSet<>();
        Set<String> completedKeys = new HashSet<>();

        if (plugin != null && plugin.cache() != null && profile != null) {
            List<UUID> memberUuids = new ArrayList<>();
            if (profile.getSourceProfiles() != null && !profile.getSourceProfiles().isEmpty()) {
                for (PlayerTasteProfile p : profile.getSourceProfiles()) {
                    if (p != null && p.getPlayerUuid() != null) memberUuids.add(p.getPlayerUuid());
                }
            } else if (profile.getPlayerUuid() != null) {
                memberUuids.add(profile.getPlayerUuid());
            }

            for (UUID u : memberUuids) {
                List<Track> liked = plugin.cache().getLikedTracksForPlayer(u, 50);
                if (liked != null) {
                    for (Track lt : liked) {
                        if (lt != null) {
                            if (lt.id() != null) likedKeys.add(lt.id());
                            String k = RecordingIdentity.compositionTitleKey(lt.title(), lt.artist());
                            if (!k.isEmpty()) likedKeys.add(k);
                        }
                    }
                }
                List<Track> sp = plugin.cache().getPlayerSpotifyTracks(u, 50);
                if (sp != null) {
                    for (Track st : sp) {
                        if (st != null) {
                            if (st.id() != null) spotifyKeys.add(st.id());
                            String k = RecordingIdentity.compositionTitleKey(st.title(), st.artist());
                            if (!k.isEmpty()) spotifyKeys.add(k);
                        }
                    }
                }
            }
        }

        if (profile != null && profile.getFatigueHistory() != null) {
            for (PlayerTasteProfile.FatigueEntry fe : profile.getFatigueHistory()) {
                if (fe != null) {
                    if (fe.trackId() != null) completedKeys.add(fe.trackId());
                    String k = RecordingIdentity.compositionTitleKey(fe.title(), fe.artist());
                    if (!k.isEmpty()) completedKeys.add(k);
                }
            }
        }

        List<String> reuploads = (plugin != null && plugin.getConfig() != null)
                ? plugin.getConfig().getStringList("recommendations.reupload-channel-patterns") : null;

        List<CandidateEvaluation> chosenEligible = Collections.emptyList();
        int chosenLevel = 0;
        Map<String, Integer> rejectionCounts = new ConcurrentHashMap<>();

        for (int level = 0; level <= 4; level++) {
            int curCooldownTracks = baseCooldownTracks;
            long curCooldownMs = baseCooldownMs;
            boolean ignoreMonopoly = false;
            long curMinDur = baseMinDur;
            long curMaxDur = baseMaxDur;

            if (level == 1) {
                curCooldownMs = baseCooldownMs / 2;
            } else if (level == 2) {
                curCooldownTracks = Math.min(10, baseCooldownTracks);
                curCooldownMs = 15 * 60_000L;
            } else if (level == 3) {
                curCooldownTracks = Math.min(10, baseCooldownTracks);
                curCooldownMs = 15 * 60_000L;
                ignoreMonopoly = true;
            } else if (level == 4) {
                curCooldownTracks = Math.min(10, baseCooldownTracks);
                curCooldownMs = 15 * 60_000L;
                ignoreMonopoly = true;
                curMinDur = (long) (baseMinDur * 0.5);
                curMaxDur = (long) (baseMaxDur * 1.5);
            }

            List<CandidateEvaluation> levelEligible = new ArrayList<>();
            Set<String> seenIds = new HashSet<>();

            for (RecommendationCandidate c : candidates) {
                Track t = c.track();
                if (t == null || t.id() == null) continue;
                if (!seenIds.add(t.id())) continue;

                // 0. HARD CONSTRAINT: Active dislike ban
                if (profile != null && profile.isDisliked(t.id())) {
                    if (level == 0) rejectionCounts.merge("dislike", 1, Integer::sum);
                    continue;
                }

                // 1. HARD CONSTRAINT: Seed duplicate or remix/slowed/live
                if (seed != null && PlayerTasteProfile.isSameSongOrRemix(t, seed)) {
                    if (level == 0) rejectionCounts.merge("seed_duplicate", 1, Integer::sum);
                    continue;
                }

                // 2. HARD CONSTRAINT: CandidateFilter blacklist & duration guardrails (duration relaxed in level 4)
                if (CandidateFilter.isHardRejected(t.title(), t.durationSeconds(), c.source(), curMinDur, curMaxDur)) {
                    if (level == 0) rejectionCounts.merge("filter_rejected", 1, Integer::sum);
                    continue;
                }

                // 3. SOFT CONSTRAINT: Fatigue check
                if (profile != null && profile.isFatigued(t, curCooldownTracks, curCooldownMs)) {
                    if (level == 0) rejectionCounts.merge("fatigue", 1, Integer::sum);
                    continue;
                }

                // Artist affinity
                double artistAffinity = profile != null ? profile.getArtistAffinity(t.artist()) : 0.2;

                // Genre similarity
                List<String> candidateGenres = (genreResolver != null && t.artist() != null)
                        ? genreResolver.getCachedGenres(t.artist()) : Collections.emptyList();
                boolean hasGenre = !candidateGenres.isEmpty();
                double genreCosine = (profile != null && hasGenre)
                        ? profile.calculateGenreCosineSimilarity(candidateGenres) : -1.0;
                if (genreCosine < 0.0) hasGenre = false;

                // Continuity
                double continuity;
                int candidateYear = extractReleaseYear(t.title());
                if (sessionContext != null) {
                    boolean fromRadio = (c.source() == RecommendationCandidate.Source.YOUTUBE_RADIO);
                    continuity = sessionContext.calculateContinuity(t, candidateGenres, candidateYear, seed, profile, fromRadio);
                } else {
                    continuity = 0.40;
                    if (seed != null) {
                        if (ArtistNames.sameArtist(seed.artist(), t.artist())) {
                            continuity = 0.85;
                        } else if (c.source() == RecommendationCandidate.Source.YOUTUBE_RADIO) {
                            continuity = 0.75;
                        } else if (c.source() == RecommendationCandidate.Source.SERVER_COLLABORATIVE) {
                            continuity = 0.70;
                        }
                    }
                }

                // Familiarity
                String compKey = RecordingIdentity.compositionTitleKey(t.title(), t.artist());
                boolean isLiked = (t.id() != null && likedKeys.contains(t.id())) || (!compKey.isEmpty() && likedKeys.contains(compKey));
                boolean isSpotify = (t.id() != null && spotifyKeys.contains(t.id())) || (!compKey.isEmpty() && spotifyKeys.contains(compKey));
                boolean isCompleted = (t.id() != null && completedKeys.contains(t.id())) || (!compKey.isEmpty() && completedKeys.contains(compKey));
                double familiarity = calculateFamiliarity(c, artistAffinity, genreCosine, isLiked, isCompleted, isSpotify);

                // Quality
                double quality = CandidateFilter.evaluateQuality(
                        t.title(),
                        seed != null ? seed.title() : null,
                        t.artist(),
                        reuploads
                );

                // Novelty
                double novelty = calculateNovelty(familiarity, artistAffinity);

                // Monopoly multiplier (ignored at level >= 3)
                double monopolyMultiplier = 1.0;
                if (!ignoreMonopoly && profile != null) {
                    int occurrences = profile.getRecentArtistCount(t.artist());
                    monopolyMultiplier = getMonopolyMultiplier(occurrences);
                }

                CandidateEvaluation eval = new CandidateEvaluation(
                        c, genreCosine, hasGenre, artistAffinity, continuity, familiarity, quality, novelty, monopolyMultiplier
                );
                levelEligible.add(eval);
            }

            chosenEligible = levelEligible;
            chosenLevel = level;

            if (levelEligible.size() >= minEligible) {
                break;
            }
        }

        if (chosenEligible.isEmpty()) {
            return Collections.emptyList();
        }

        List<CandidateEvaluation> familiarPool = new ArrayList<>();
        List<CandidateEvaluation> similarPool = new ArrayList<>();
        List<CandidateEvaluation> explorePool = new ArrayList<>();

        for (CandidateEvaluation eval : chosenEligible) {
            RecommendationCandidate c = eval.candidate;
            Track t = c.track();
            if (c.source() == RecommendationCandidate.Source.FAMILIAR) {
                familiarPool.add(eval);
            }
            if (eval.artistAffinity >= 0.2 || (eval.hasGenre && eval.genreCosine >= 0.4)
                    || c.source() == RecommendationCandidate.Source.SPOTIFY_GENRE
                    || c.source() == RecommendationCandidate.Source.YOUTUBE_RADIO
                    || c.source() == RecommendationCandidate.Source.SERVER_COLLABORATIVE) {
                similarPool.add(eval);
            }
            if (eval.artistAffinity < 0.2 && eval.continuity >= exploreMinContinuity
                    && (profile == null || !profile.isRecentArtist(t.artist()))) {
                explorePool.add(eval);
            }
        }

        Map<String, String> fallbacks = new LinkedHashMap<>();
        fallbacks.put("FAMILIAR", !familiarPool.isEmpty() ? "FAMILIAR" : "SIMILAR");
        fallbacks.put("SIMILAR", "SIMILAR");
        fallbacks.put("EXPLORE", (allowExplore && !explorePool.isEmpty()) ? "EXPLORE" : "SIMILAR");
        String modeFallbacksJson = new com.google.gson.Gson().toJson(fallbacks);

        List<CandidateEvaluation> activePool;
        if (effectiveMode == RecommendationMode.FAMILIAR) {
            if (!familiarPool.isEmpty()) {
                activePool = familiarPool;
            } else {
                effectiveMode = RecommendationMode.SIMILAR;
                activePool = !similarPool.isEmpty() ? similarPool : chosenEligible;
            }
        } else if (effectiveMode == RecommendationMode.EXPLORE) {
            if (!explorePool.isEmpty()) {
                activePool = explorePool;
            } else {
                effectiveMode = RecommendationMode.SIMILAR;
                activePool = !similarPool.isEmpty() ? similarPool : chosenEligible;
            }
        } else {
            activePool = !similarPool.isEmpty() ? similarPool : chosenEligible;
        }

        // Aggregate configured probability mass of all requested modes that route to effectiveMode
        double effectiveModeProbability = 0.0;
        for (Map.Entry<String, String> entry : fallbacks.entrySet()) {
            if (entry.getValue().equals(effectiveMode.name())) {
                if ("FAMILIAR".equals(entry.getKey())) effectiveModeProbability += wFam;
                else if ("SIMILAR".equals(entry.getKey())) effectiveModeProbability += wSim;
                else if ("EXPLORE".equals(entry.getKey())) effectiveModeProbability += (allowExplore ? wExp : 0.0);
            }
        }
        double totalModeMass = wFam + wSim + (allowExplore ? wExp : 0.0);
        if (totalModeMass > 0.0) {
            effectiveModeProbability /= totalModeMass;
        } else {
            effectiveModeProbability = 1.0;
        }

        double[] weights = getModeWeights(effectiveMode, plugin != null ? plugin.getConfig() : null);

        List<RecommendationCandidate> scored = new ArrayList<>();
        Map<String, Map<String, Object>> candidateFeaturesMap = new HashMap<>();
        Map<String, Double> candidateLearnedScoresMap = new HashMap<>();
        Map<String, Double> candidateHeuristicScoresMap = new HashMap<>();

        for (CandidateEvaluation ev : activePool) {
            double effectiveNovelty = ev.novelty;
            int candYear = extractReleaseYear(ev.candidate.track().title());
            boolean hasRecent = candYear >= 1950;
            double recentScore = hasRecent ? calculateRecentReleaseScore(candYear, 2026) : 0.0;
            if (hasRecent && effectiveMode == RecommendationMode.EXPLORE) {
                // Boost novelty in EXPLORE mode with recent release feature (Task 4.3)
                effectiveNovelty = (ev.novelty * 0.80) + (recentScore * 0.20);
            }
            double heuristicScore = calculateCandidateScore(
                    ev.genreCosine, ev.hasGenre,
                    ev.artistAffinity, ev.continuity, ev.familiarity, ev.quality, effectiveNovelty,
                    weights, minFeatureCoverage, ev.monopolyMultiplier, ev.candidate.cachedLocally(), cacheBonusValue
            );
            if (heuristicScore >= 0.0) {
                Map<String, Object> feat = new LinkedHashMap<>();
                feat.put("genreCosine", ev.hasGenre ? ev.genreCosine : null);
                feat.put("artistAffinity", ev.artistAffinity);
                feat.put("continuity", ev.continuity);
                feat.put("familiarity", ev.familiarity);
                feat.put("quality", ev.quality);
                feat.put("novelty", effectiveNovelty);
                feat.put("monopolyMultiplier", ev.monopolyMultiplier);
                feat.put("recentRelease", hasRecent ? recentScore : null);
                candidateFeaturesMap.put(ev.candidate.track().id(), feat);

                Map<String, Double> normFeats = new HashMap<>();
                normFeats.put("genre_match", ev.hasGenre ? ev.genreCosine : 0.5);
                normFeats.put("artist_affinity", ev.artistAffinity);
                normFeats.put("continuity", ev.continuity);
                normFeats.put("familiarity", ev.familiarity);
                normFeats.put("quality", ev.quality);
                normFeats.put("novelty", effectiveNovelty);

                Double learnedScore = null;
                double finalScore = heuristicScore;
                if (learnedRanker != null && learnedRanker.isLoaded()) {
                    learnedScore = learnedRanker.computeLearnedScore(normFeats);
                    finalScore = learnedRanker.blend(heuristicScore, learnedScore);
                }

                candidateLearnedScoresMap.put(ev.candidate.track().id(), learnedScore);
                candidateHeuristicScoresMap.put(ev.candidate.track().id(), heuristicScore);

                String reason = buildExplainabilityReason(
                        effectiveMode, ev.candidate.source(),
                        ev.genreCosine, ev.hasGenre,
                        ev.artistAffinity, ev.continuity, ev.familiarity, ev.quality, effectiveNovelty,
                        weights
                );
                scored.add(new RecommendationCandidate(
                        ev.candidate.track(), finalScore, ev.candidate.source(),
                        reason, ev.candidate.cachedLocally()
                ));
            } else {
                rejectionCounts.merge("insufficient_features", 1, Integer::sum);
            }
        }

        if (scored.isEmpty()) {
            return Collections.emptyList();
        }

        // MMR Diversity and Softmax Selection (Task 2.8)
        List<Track> lastFive = new ArrayList<>();
        if (profile != null && profile.getFatigueHistory() != null) {
            List<PlayerTasteProfile.FatigueEntry> fList = new ArrayList<>(profile.getFatigueHistory());
            int start = Math.max(0, fList.size() - 5);
            for (int i = start; i < fList.size(); i++) {
                PlayerTasteProfile.FatigueEntry fe = fList.get(i);
                lastFive.add(new Track(fe.trackId(), fe.title(), fe.artist(), 0, ""));
            }
        }

        Map<String, List<String>> artistGenreMap = new HashMap<>();
        if (genreResolver != null) {
            for (RecommendationCandidate c : scored) {
                if (c.track() != null && c.track().artist() != null) {
                    String normA = ArtistNames.normalize(c.track().artist());
                    artistGenreMap.putIfAbsent(normA, genreResolver.getCachedGenres(c.track().artist()));
                }
            }
        }

        double lambda = plugin != null && plugin.getConfig() != null
                ? plugin.getConfig().getDouble("recommendations.mmr-lambda", 0.70) : 0.70;
        double softmaxTemp = plugin != null && plugin.getConfig() != null
                ? plugin.getConfig().getDouble("recommendations.softmax-temperature", 0.08) : 0.0;

        Collections.sort(scored);
        List<RecommendationCandidate> top15 = scored.subList(0, Math.min(15, scored.size()));

        List<CandidateSelector.MmrCandidate> mmrList = CandidateSelector.greedyMmr(
                top15, lastFive, artistGenreMap, lambda, 8
        );

        RecommendationCandidate chosen = CandidateSelector.softmaxSample(mmrList, softmaxTemp, rng);
        if (chosen == null && !mmrList.isEmpty()) {
            chosen = mmrList.get(0).candidate();
        } else if (chosen == null) {
            chosen = scored.get(0);
        }

        // Calculate actual marginal selection propensities P(c selected)
        double[] softmaxProbs = CandidateSelector.computeSoftmaxProbabilities(mmrList, softmaxTemp);
        Map<String, Double> mmrSoftmaxProbMap = new HashMap<>();
        for (int i = 0; i < mmrList.size(); i++) {
            RecommendationCandidate cand = mmrList.get(i).candidate();
            if (cand != null && cand.track() != null) {
                mmrSoftmaxProbMap.put(cand.track().id(), softmaxProbs[i]);
            }
        }

        String impressionId = UUID.randomUUID().toString();

        List<RankedPick> picks = new ArrayList<>();
        picks.add(new RankedPick(
                chosen.track(),
                effectiveMode,
                chosen.source(),
                chosen.reason(),
                chosen.score(),
                impressionId
        ));

        for (CandidateSelector.MmrCandidate mmr : mmrList) {
            if (picks.size() >= 3) break;
            RecommendationCandidate cand = mmr.candidate();
            if (cand == null || cand.track() == null) continue;
            boolean already = false;
            for (RankedPick p : picks) {
                if (p.track() != null && p.track().id() != null && p.track().id().equals(cand.track().id())) {
                    already = true;
                    break;
                }
            }
            if (!already) {
                picks.add(new RankedPick(
                        cand.track(),
                        effectiveMode,
                        cand.source(),
                        cand.reason(),
                        cand.score(),
                        impressionId
                ));
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        if (plugin != null && plugin.cache() != null) {
            List<TrackCache.CandidateTelemetry> telemList = new ArrayList<>();
            Set<String> loggedIds = new HashSet<>();
            int topLimit = Math.min(20, scored.size());
            for (int i = 0; i < topLimit; i++) {
                RecommendationCandidate sc = scored.get(i);
                String tid = sc.track().id();
                loggedIds.add(tid);
                double probInMode = mmrSoftmaxProbMap.getOrDefault(tid, 0.0);
                double selectionProbability = effectiveModeProbability * probInMode;
                boolean isSelected = chosen.track().id().equals(tid);
                String featsJson = new com.google.gson.Gson().toJson(candidateFeaturesMap.getOrDefault(tid, Collections.emptyMap()));
                double hScore = candidateHeuristicScoresMap.getOrDefault(tid, sc.score());
                Double lScore = candidateLearnedScoresMap.get(tid);
                telemList.add(new TrackCache.CandidateTelemetry(
                        tid,
                        i + 1,
                        sc.source().name(),
                        sc.score(),
                        hScore,
                        lScore,
                        selectionProbability,
                        isSelected,
                        featsJson
                ));
            }
            if (!loggedIds.contains(chosen.track().id())) {
                double probInMode = mmrSoftmaxProbMap.getOrDefault(chosen.track().id(), 0.0);
                double selectionProbability = effectiveModeProbability * probInMode;
                String featsJson = new com.google.gson.Gson().toJson(candidateFeaturesMap.getOrDefault(chosen.track().id(), Collections.emptyMap()));
                double hScore = candidateHeuristicScoresMap.getOrDefault(chosen.track().id(), chosen.score());
                Double lScore = candidateLearnedScoresMap.get(chosen.track().id());
                telemList.add(new TrackCache.CandidateTelemetry(
                        chosen.track().id(),
                        topLimit + 1,
                        chosen.source().name(),
                        chosen.score(),
                        hScore,
                        lScore,
                        selectionProbability,
                        true,
                        featsJson
                ));
            }

            String modelVersion = "heuristic";
            if (learnedRanker != null && learnedRanker.isLoaded()) {
                modelVersion = (learnedRanker.isShadowMode() ? "shadow:" : "learned:") + learnedRanker.getVersion();
            }

            plugin.cache().logRecImpression(
                    impressionId,
                    System.currentTimeMillis(),
                    profile != null ? profile.getPlayerUuid() : null,
                    null,
                    seed != null ? seed.id() : null,
                    "v2",
                    modelVersion,
                    requestedMode.name(),
                    effectiveMode.name(),
                    effectiveModeProbability,
                    modeFallbacksJson,
                    chosenLevel,
                    candidates.size(),
                    chosenEligible.size(),
                    new com.google.gson.Gson().toJson(rejectionCounts),
                    "{}",
                    elapsed,
                    chosen.track().id(),
                    telemList
            );
        }

        return picks;
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

        // MMR Diversity and Softmax Selection (Task 2.8)
        List<Track> lastFive = new ArrayList<>();
        if (profile != null && profile.getFatigueHistory() != null) {
            List<PlayerTasteProfile.FatigueEntry> fList = new ArrayList<>(profile.getFatigueHistory());
            int start = Math.max(0, fList.size() - 5);
            for (int i = start; i < fList.size(); i++) {
                PlayerTasteProfile.FatigueEntry fe = fList.get(i);
                lastFive.add(new Track(fe.trackId(), fe.title(), fe.artist(), 0, ""));
            }
        }

        Map<String, List<String>> artistGenreMap = new HashMap<>();
        if (genreResolver != null) {
            for (RecommendationCandidate c : scored) {
                if (c.track() != null && c.track().artist() != null) {
                    String normA = ArtistNames.normalize(c.track().artist());
                    artistGenreMap.putIfAbsent(normA, genreResolver.getCachedGenres(c.track().artist()));
                }
            }
        }

        double lambda = plugin != null && plugin.getConfig() != null
                ? plugin.getConfig().getDouble("recommendations.mmr-lambda", 0.70) : 0.70;
        double softmaxTemp = plugin != null && plugin.getConfig() != null
                ? plugin.getConfig().getDouble("recommendations.softmax-temperature", 0.08) : 0.0;

        List<CandidateSelector.MmrCandidate> mmrList = CandidateSelector.greedyMmr(
                scored, lastFive, artistGenreMap, lambda, 8
        );

        RecommendationCandidate chosen = CandidateSelector.softmaxSample(mmrList, softmaxTemp, RANDOM);
        return chosen != null ? chosen.track() : scored.get(0).track();
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

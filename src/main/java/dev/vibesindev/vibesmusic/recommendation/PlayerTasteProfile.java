package dev.vibesindev.vibesmusic.recommendation;

import dev.vibesindev.vibesmusic.model.Track;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Multi-dimensional taste profile modeling a player's implicit and explicit musical tastes.
 *
 * <p>Integrates:
 * <ul>
 *   <li>Real Spotify top artists (short-term 4-week + medium-term 6-month)</li>
 *   <li>Real Spotify micro-genre distributions</li>
 *   <li>In-game play frequency and recency from SQLite history</li>
 *   <li>Custom in-game playlist additions ($2.5\times$ weight)</li>
 *   <li>Fatigue blacklist (hard 20-track / 45-minute cooldown)</li>
 * </ul>
 */
@SuppressWarnings("null")
public final class PlayerTasteProfile {

    /** Timestamped record of a played track to enforce time-based and count-based cooldowns. */
    public record FatigueEntry(String trackId, String artist, String title, long playedAt) {}

    private final UUID playerUuid;

    /** Normalized artist affinities: artist name (lowercase) -> affinity weight (0.0 to 1.0). */
    private final Map<String, Double> artistAffinities = new ConcurrentHashMap<>();

    /** Timestamps of last affinity updates per artist (artist name lowercase -> timestamp ms). */
    private final Map<String, Long> artistAffinityTimestamps = new ConcurrentHashMap<>();

    /** Raw, unnormalized in-game play genre mass: genre tag (lowercase) -> accumulated mass. */
    private final Map<String, Double> playGenreMass = new ConcurrentHashMap<>();

    /** Timestamps of last play genre updates (genre tag lowercase -> timestamp ms). */
    private final Map<String, Long> playGenreMassTimestamps = new ConcurrentHashMap<>();

    /** Raw, unnormalized Spotify top artist genre mass: genre tag (lowercase) -> mass (replaced on sync). */
    private final Map<String, Double> spotifyGenreMass = new ConcurrentHashMap<>();

    /** Configurable clock for time injection in tests and deterministic decay. */
    private java.time.Clock clock = java.time.Clock.systemUTC();

    /** Affinity half life in days (default 30 days, configurable via recommendations.affinity-half-life-days). */
    private double halfLifeDays = 30.0;

    /** Cached read-time normalized probability distribution (P(genre), sum = 1.0). */
    private volatile Map<String, Double> cachedGenreDistribution = null;

    /** Rolling fatigue history: newest entries at the end. */
    private final Deque<FatigueEntry> fatigueHistory = new ArrayDeque<>();

    /** Rolling recent artists (last 2 songs) to avoid artist monopolization. */
    private final Deque<String> recentArtists = new ArrayDeque<>();

    /** Disliked tracks under 7-day soft cooldown (trackId -> expireTimestamp). */
    private final Map<String, Long> dislikedTracks = new ConcurrentHashMap<>();

    /** Inverted token index and lookup cache for artist affinity (fixes F7). */
    private final Map<String, Double> affinityLookupCache = new ConcurrentHashMap<>();
    private volatile Map<String, List<String>> tokenIndex;

    public void invalidateAffinityCache() {
        affinityLookupCache.clear();
        tokenIndex = null;
    }

    /** Custom track cooldowns for skipped tracks (trackId / compositionKey -> expiry timestamp). */
    private final Map<String, Long> customTrackCooldowns = new ConcurrentHashMap<>();

    /** Tracks timestamp of last early skip per artist to penalize only on 2nd+ skip in 24h. */
    private final Map<String, Long> recentArtistEarlySkips = new ConcurrentHashMap<>();

    /** Per-profile dynamic lineage learned from Spotify related artists or user sessions. */
    private final Map<String, Set<String>> dynamicLineage = new ConcurrentHashMap<>();

    /**
     * Built-in knowledge graph mapping bands to solo members, spin-offs, and closely associated artists.
     * Normalized lowercase -> set of connected artist names.
     */
    private static final Map<String, Set<String>> STATIC_LINEAGE_GRAPH = new ConcurrentHashMap<>();
    // =========================================================
    // CANONICAL TITLE MATCHING & REMIX/SLOWED DEDUPLICATION
    // =========================================================

    @Deprecated
    public static String canonicalizeTitle(String title, String artist) {
        return RecordingIdentity.compositionTitleKey(title, artist);
    }

    public static boolean isSameSongOrRemix(Track a, Track b) {
        if (a == null || b == null) return false;
        if (RecordingIdentity.sameRecording(a, b)) return true;

        String compA = RecordingIdentity.compositionTitleKey(a.title(), a.artist());
        String compB = RecordingIdentity.compositionTitleKey(b.title(), b.artist());
        if (!compA.isEmpty() && compA.equals(compB)) {
            if (ArtistNames.sameArtist(a.artist(), b.artist()) || compA.length() >= 5) {
                return true;
            }
        }
        return RecordingIdentity.relatedRecording(a, b);
    }


    static {
        registerStaticLineage("one direction", "harry styles", "zayn", "niall horan", "louis tomlinson", "liam payne");
        registerStaticLineage("the beatles", "john lennon", "paul mccartney", "george harrison", "ringo starr", "wings");
        registerStaticLineage("destiny's child", "beyonce", "beyoncé", "kelly rowland", "michelle williams");
        registerStaticLineage("daft punk", "thomas bangalter", "guy-manuel de homem-christo");
        registerStaticLineage("nirvana", "foo fighters", "kurt cobain", "dave grohl");
        registerStaticLineage("foo fighters", "dave grohl", "nirvana");
        registerStaticLineage("wham!", "george michael");
        registerStaticLineage("wham", "george michael");
        registerStaticLineage("fleetwood mac", "stevie nicks", "lindsey buckingham");
        registerStaticLineage("genesis", "phil collins", "peter gabriel");
        registerStaticLineage("black sabbath", "ozzy osbourne");
        registerStaticLineage("oasis", "noel gallagher", "noel gallagher's high flying birds", "liam gallagher", "beady eye");
        registerStaticLineage("the police", "sting");
        registerStaticLineage("the jackson 5", "michael jackson", "janet jackson");
        registerStaticLineage("jackson 5", "michael jackson", "janet jackson");
        registerStaticLineage("the fugees", "ms. lauryn hill", "lauryn hill", "wyclef jean");
        registerStaticLineage("fugees", "ms. lauryn hill", "lauryn hill", "wyclef jean");
        registerStaticLineage("audioslave", "soundgarden", "rage against the machine", "chris cornell");
        registerStaticLineage("soundgarden", "chris cornell", "audioslave");
        registerStaticLineage("joy division", "new order");
        registerStaticLineage("the velvet underground", "lou reed");
        registerStaticLineage("velvet underground", "lou reed");
        registerStaticLineage("blink-182", "angels & airwaves", "+44", "box car racer", "travis barker");
        registerStaticLineage("the white stripes", "jack white", "the raconteurs", "the dead weather");
        registerStaticLineage("white stripes", "jack white", "the raconteurs", "the dead weather");
        registerStaticLineage("fifth harmony", "camila cabello", "normani");
        registerStaticLineage("simon & garfunkel", "paul simon", "art garfunkel");
        registerStaticLineage("guns n' roses", "slash", "velvet revolver");
        registerStaticLineage("creed", "alter bridge");
        registerStaticLineage("n.w.a", "dr. dre", "ice cube", "eazy-e");
        registerStaticLineage("*nsync", "justin timberlake");
        registerStaticLineage("nsync", "justin timberlake");
        registerStaticLineage("queen", "freddie mercury", "brian may");
    }

    /**
     * Registers a global bidirectional connection between two artists (e.g. from config.yml).
     */
    public static void registerGlobalLineage(String artistA, String artistB) {
        if (artistA == null || artistB == null || artistA.isBlank() || artistB.isBlank()) return;
        String aNorm = artistA.toLowerCase(Locale.ROOT).trim();
        String bNorm = artistB.toLowerCase(Locale.ROOT).trim();
        if (aNorm.equals(bNorm)) return;

        STATIC_LINEAGE_GRAPH.computeIfAbsent(aNorm, k -> ConcurrentHashMap.newKeySet()).add(bNorm);
        STATIC_LINEAGE_GRAPH.computeIfAbsent(bNorm, k -> ConcurrentHashMap.newKeySet()).add(aNorm);
    }

    private static void registerStaticLineage(String hub, String... members) {
        String hubNorm = hub.toLowerCase(Locale.ROOT).trim();
        Set<String> hubSet = STATIC_LINEAGE_GRAPH.computeIfAbsent(hubNorm, k -> ConcurrentHashMap.newKeySet());
        for (String m : members) {
            String mNorm = m.toLowerCase(Locale.ROOT).trim();
            hubSet.add(mNorm);
            STATIC_LINEAGE_GRAPH.computeIfAbsent(mNorm, k -> ConcurrentHashMap.newKeySet()).add(hubNorm);
            for (String sibling : members) {
                String sNorm = sibling.toLowerCase(Locale.ROOT).trim();
                if (!sNorm.equals(mNorm)) {
                    STATIC_LINEAGE_GRAPH.computeIfAbsent(mNorm, k -> ConcurrentHashMap.newKeySet()).add(sNorm);
                }
            }
        }
    }

    private volatile long lastSyncTimestamp = 0;

    private final List<PlayerTasteProfile> sourceProfiles;

    public PlayerTasteProfile(UUID playerUuid) {
        this(playerUuid, Collections.emptyList(), java.time.Clock.systemUTC());
    }

    public PlayerTasteProfile(UUID playerUuid, java.time.Clock clock) {
        this(playerUuid, Collections.emptyList(), clock);
    }

    public PlayerTasteProfile(UUID playerUuid, List<PlayerTasteProfile> sourceProfiles) {
        this(playerUuid, sourceProfiles, java.time.Clock.systemUTC());
    }

    public PlayerTasteProfile(UUID playerUuid, List<PlayerTasteProfile> sourceProfiles, java.time.Clock clock) {
        this.playerUuid = playerUuid;
        this.sourceProfiles = (sourceProfiles != null) ? List.copyOf(sourceProfiles) : Collections.emptyList();
        this.clock = (clock != null) ? clock : java.time.Clock.systemUTC();
    }

    public void setClock(java.time.Clock clock) {
        this.clock = (clock != null) ? clock : java.time.Clock.systemUTC();
        invalidateAffinityCache();
        this.cachedGenreDistribution = null;
    }

    public java.time.Clock getClock() {
        return this.clock;
    }

    private long currentTimeMillis() {
        return clock != null ? clock.millis() : System.currentTimeMillis();
    }

    public void setHalfLifeDays(double days) {
        this.halfLifeDays = (days > 0.0) ? days : 30.0;
        invalidateAffinityCache();
        this.cachedGenreDistribution = null;
    }

    public double getHalfLifeDays() {
        return this.halfLifeDays;
    }

    public synchronized Map<String, Long> getArtistAffinityTimestamps() {
        return Collections.unmodifiableMap(artistAffinityTimestamps);
    }

    public synchronized String exportAffinityTimestampsJson() {
        return new com.google.gson.Gson().toJson(artistAffinityTimestamps);
    }

    public double getEffectiveArtistAffinity(String artistNorm) {
        if (artistNorm == null || artistNorm.isBlank()) return 0.0;
        Double raw = artistAffinities.get(artistNorm);
        if (raw == null) return 0.0;
        return computeDecayedAffinity(artistNorm, raw.doubleValue());
    }

    private double computeDecayedAffinity(String artistNorm, double rawValue) {
        if (rawValue <= 0.0) return 0.0;
        long now = currentTimeMillis();
        Long ts = artistAffinityTimestamps.get(artistNorm);
        long updatedAt = (ts != null) ? ts.longValue() : ((lastSyncTimestamp > 0) ? lastSyncTimestamp : now);
        long deltaMs = Math.max(0L, now - updatedAt);
        double halfLifeMs = halfLifeDays * 86_400_000.0;
        if (halfLifeMs <= 0) halfLifeMs = 30.0 * 86_400_000.0;

        double decayFactor = Math.pow(2.0, -((double) deltaMs / halfLifeMs));
        double decayed = rawValue * decayFactor;
        return Math.max(0.02, decayed);
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public List<PlayerTasteProfile> getSourceProfiles() {
        return sourceProfiles != null ? sourceProfiles : Collections.emptyList();
    }

    public long getLastSyncTimestamp() {
        return lastSyncTimestamp;
    }

    public void setLastSyncTimestamp(long timestamp) {
        this.lastSyncTimestamp = timestamp;
    }

    // =========================================================
    // IN-GAME SIGNAL INGESTION
    // =========================================================

    /**
     * Records stream start: adds exactly one fatigue entry and updates recentArtists (window of 4).
     * No affinity changes here (fixes F11).
     */
    public synchronized void recordStart(Track track) {
        if (track == null) return;
        long now = System.currentTimeMillis();

        // 1. Fatigue tracking: exactly one entry per played track
        fatigueHistory.addLast(new FatigueEntry(track.id(), track.artist(), track.title(), now));
        while (fatigueHistory.size() > 50) {
            fatigueHistory.removeFirst();
        }

        // 2. Recent artists tracking (window is 4)
        String artist = ArtistNames.normalize(track.artist());
        if (!artist.isEmpty() && !artist.equalsIgnoreCase("unknown")) {
            recentArtists.addLast(artist);
            while (recentArtists.size() > 4) {
                recentArtists.removeFirst();
            }
        }
    }

    @Deprecated
    public synchronized void recordPlay(Track track) {
        recordStart(track);
    }

    /**
     * Records playback outcome and updates artist affinities with graded signals and diminishing returns.
     */
    public synchronized void recordOutcome(
            Track track,
            long playedMs,
            long durationMs,
            RecommendationManager.Outcome outcome,
            org.bukkit.configuration.ConfigurationSection config
    ) {
        if (track == null) return;
        double completionGain = config != null ? config.getDouble("recommendations.signals.completion-gain", 0.08) : 0.08;
        double artistSkipPenalty = config != null ? config.getDouble("recommendations.signals.artist-skip-penalty", 0.10) : 0.10;

        // Neutral non-listening outcomes
        if (outcome == RecommendationManager.Outcome.AFK_OR_UNKNOWN
                || outcome == RecommendationManager.Outcome.DISCONNECTED
                || outcome == RecommendationManager.Outcome.MANUAL_STOP) {
            return;
        }

        // Repeat / Requeue: double completion boost
        if (outcome == RecommendationManager.Outcome.REPEATED || outcome == RecommendationManager.Outcome.REQUEUED) {
            boostArtistDiminishing(ArtistNames.normalize(track.artist()), 2.0 * completionGain);
            return;
        }

        double ratio = (durationMs > 0) ? (double) playedMs / durationMs : 0.0;
        long now = currentTimeMillis();

        if (outcome == RecommendationManager.Outcome.COMPLETED || ratio >= 0.80) {
            // Completion >= 80%: positive gain with diminishing returns
            boostArtistDiminishing(ArtistNames.normalize(track.artist()), completionGain);
        } else if (ratio >= 0.50) {
            // 50-80%: neutral
        } else if (ratio >= 0.10 && ratio < 0.50) {
            // 10-50% played then skipped: mild negative, 6h track cooldown, no artist change
            if (track.id() != null) customTrackCooldowns.put(track.id(), now + 6 * 3600_000L);
            String compKey = RecordingIdentity.compositionTitleKey(track.title(), track.artist());
            if (!compKey.isEmpty()) customTrackCooldowns.put(compKey, now + 6 * 3600_000L);
        } else {
            // < 10% or < 30s: strong negative on track (24h cooldown)
            if (track.id() != null) customTrackCooldowns.put(track.id(), now + 24 * 3600_000L);
            String compKey = RecordingIdentity.compositionTitleKey(track.title(), track.artist());
            if (!compKey.isEmpty()) customTrackCooldowns.put(compKey, now + 24 * 3600_000L);

            // Artist penalized only if 2nd+ early skip in 24h
            String normArtist = ArtistNames.normalize(track.artist());
            if (!normArtist.isEmpty()) {
                Long lastSkip = recentArtistEarlySkips.get(normArtist);
                if (lastSkip != null && (now - lastSkip) < 24 * 3600_000L) {
                    downweightArtist(normArtist, artistSkipPenalty);
                }
                recentArtistEarlySkips.put(normArtist, now);
            }
        }
    }

    @Deprecated
    public synchronized void recordSkip(Track track) {
        recordOutcome(track, 0, 0, RecommendationManager.Outcome.SKIPPED_EARLY, null);
    }

    /**
     * Ingests a track added to a custom playlist (playlistAddGain with diminishing returns).
     */
    public synchronized void recordPlaylistAdd(Track track) {
        if (track == null) return;
        String artist = ArtistNames.normalize(track.artist());
        boostArtistDiminishing(artist, 0.20);
    }

    /**
     * Ingests an explicit thumbs-up like (likeGain with diminishing returns, clears dislike).
     */
    public synchronized void recordLike(Track track) {
        if (track == null) return;
        if (track.id() != null) {
            dislikedTracks.remove(track.id());
            customTrackCooldowns.remove(track.id());
        }
        String artist = ArtistNames.normalize(track.artist());
        boostArtistDiminishing(artist, 0.25);
    }

    /**
     * Ingests an explicit thumbs-down dislike: applies a 7-day soft ban and downweights artist by 15%.
     */
    public synchronized void recordDislike(Track track) {
        if (track == null) return;
        long sevenDays = 7L * 24 * 60 * 60 * 1000L;
        long now = currentTimeMillis();
        if (track.id() != null) dislikedTracks.put(track.id(), now + sevenDays);
        String compKey = RecordingIdentity.compositionTitleKey(track.title(), track.artist());
        if (!compKey.isEmpty()) customTrackCooldowns.put(compKey, now + sevenDays);

        String artist = ArtistNames.normalize(track.artist());
        downweightArtist(artist, 0.15);
    }

    private void boostArtistDiminishing(String artist, double gain) {
        if (artist == null || artist.isBlank() || artist.equalsIgnoreCase("unknown")) return;
        long now = currentTimeMillis();
        artistAffinities.compute(artist, (k, oldVal) -> {
            double current = (oldVal != null) ? oldVal.doubleValue() : 0.20;
            double updated = current + gain * (1.0 - current);
            return Math.min(1.0, Math.max(0.01, updated));
        });
        artistAffinityTimestamps.put(artist, now);
        invalidateAffinityCache();
    }

    private void downweightArtist(String artist, double factor) {
        if (artist == null || artist.isBlank() || artist.equalsIgnoreCase("unknown")) return;
        long now = currentTimeMillis();
        artistAffinities.computeIfPresent(artist, (k, oldVal) -> {
            double ov = oldVal.doubleValue();
            return Math.max(0.01, ov * (1.0 - factor));
        });
        artistAffinityTimestamps.put(artist, now);
        invalidateAffinityCache();
    }

    // =========================================================
    // SPOTIFY CLOUD DATA INGESTION
    // =========================================================

    /**
     * Ingests top artists from Spotify API.
     *
     * @param shortTermArtists  past 4 weeks (heaviest weight: 1.0)
     * @param mediumTermArtists core 6 months favorites (weight: 0.7)
     */
    public synchronized void ingestSpotifyArtists(List<String> shortTermArtists, List<String> mediumTermArtists) {
        long now = currentTimeMillis();
        if (shortTermArtists != null) {
            int rank = 1;
            int total = Math.max(1, shortTermArtists.size());
            for (String artist : shortTermArtists) {
                if (artist == null || artist.isBlank()) continue;
                String key = artist.toLowerCase(Locale.ROOT).trim();
                double weight = 1.0 * (1.0 - ((double) (rank - 1) / total) * 0.5); // 1.0 down to 0.5
                artistAffinities.merge(key, Double.valueOf(weight), (Double oldVal, Double w) -> {
                    double ov = (oldVal != null) ? oldVal.doubleValue() : 0.0;
                    double nw = (w != null) ? w.doubleValue() : 0.0;
                    return Double.valueOf(Math.max(ov, nw));
                });
                artistAffinityTimestamps.put(key, now);
                rank++;
            }
        }

        if (mediumTermArtists != null) {
            int rank = 1;
            int total = Math.max(1, mediumTermArtists.size());
            for (String artist : mediumTermArtists) {
                if (artist == null || artist.isBlank()) continue;
                String key = artist.toLowerCase(Locale.ROOT).trim();
                double weight = 0.7 * (1.0 - ((double) (rank - 1) / total) * 0.5); // 0.7 down to 0.35
                artistAffinities.merge(key, Double.valueOf(weight), (Double oldVal, Double w) -> {
                    double ov = (oldVal != null) ? oldVal.doubleValue() : 0.0;
                    double nw = (w != null) ? w.doubleValue() : 0.0;
                    return Double.valueOf(Math.max(ov, nw));
                });
                artistAffinityTimestamps.put(key, now);
                rank++;
            }
        }

        normalizeAffinities();
        this.lastSyncTimestamp = now;
    }

    /**
     * Ingests micro-genres for an artist and updates the genre distribution.
     *
     * @deprecated Use {@link #ingestArtistGenres(List, double)} with explicit weight.
     */
    @Deprecated
    public synchronized void ingestArtistGenres(List<String> genres) {
        ingestArtistGenres(genres, 1.0);
    }

    /**
     * Ingests micro-genres with a given weight, distributing {@code weight / cleanGenres.size()}
     * into {@code playGenreMass} so artists with many tags do not dominate.
     */
    public synchronized void ingestArtistGenres(List<String> genres, double weight) {
        if (genres == null || genres.isEmpty() || weight <= 0.0) return;
        List<String> clean = new ArrayList<>();
        for (String g : genres) {
            if (g != null && !g.isBlank()) {
                clean.add(g.toLowerCase(Locale.ROOT).trim());
            }
        }
        if (clean.isEmpty()) return;
        double perTag = weight / clean.size();
        long now = currentTimeMillis();
        for (String key : clean) {
            playGenreMass.merge(key, Double.valueOf(perTag), (Double a, Double b) -> {
                double va = (a != null) ? a.doubleValue() : 0.0;
                double vb = (b != null) ? b.doubleValue() : 0.0;
                return Double.valueOf(va + vb);
            });
            playGenreMassTimestamps.put(key, now);
        }
        cachedGenreDistribution = null;
    }

    /**
     * Clears all Spotify genre mass in preparation for an idempotent full sync.
     */
    public synchronized void clearSpotifyGenreMass() {
        spotifyGenreMass.clear();
        cachedGenreDistribution = null;
    }

    /**
     * Ingests micro-genres from Spotify top artists, distributing {@code weight / cleanGenres.size()}
     * into {@code spotifyGenreMass}.
     */
    public synchronized void ingestSpotifyArtistGenres(List<String> genres, double weight) {
        if (genres == null || genres.isEmpty() || weight <= 0.0) return;
        List<String> clean = new ArrayList<>();
        for (String g : genres) {
            if (g != null && !g.isBlank()) {
                clean.add(g.toLowerCase(Locale.ROOT).trim());
            }
        }
        if (clean.isEmpty()) return;
        double perTag = weight / clean.size();
        for (String key : clean) {
            spotifyGenreMass.merge(key, Double.valueOf(perTag), (Double a, Double b) -> {
                double va = (a != null) ? a.doubleValue() : 0.0;
                double vb = (b != null) ? b.doubleValue() : 0.0;
                return Double.valueOf(va + vb);
            });
        }
        cachedGenreDistribution = null;
    }

    private synchronized void normalizeAffinities() {
        if (artistAffinities.isEmpty()) return;
        double max = 0.0;
        for (double val : artistAffinities.values()) {
            if (val > max) max = val;
        }
        if (max > 1.0) {
            final double m = max;
            artistAffinities.replaceAll((k, v) -> v / m);
        }
        invalidateAffinityCache();
    }

    // =========================================================
    // SCORING QUERIES
    // =========================================================

    /**
     * Returns the read-time normalized genre distribution summing to 1.0 (or empty map).
     * Cached until the next profile genre mutation.
     */
    public Map<String, Double> getGenreDistribution() {
        Map<String, Double> cached = cachedGenreDistribution;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (cachedGenreDistribution != null) {
                return cachedGenreDistribution;
            }
            Map<String, Double> combined = new HashMap<>();
            double totalSum = 0.0;
            long now = currentTimeMillis();
            double halfLifeMs = halfLifeDays * 86_400_000.0;
            if (halfLifeMs <= 0) halfLifeMs = 30.0 * 86_400_000.0;

            // Decayed playGenreMass
            for (Map.Entry<String, Double> e : playGenreMass.entrySet()) {
                Double v = e.getValue();
                if (v != null && v.doubleValue() > 0.0) {
                    Long ts = playGenreMassTimestamps.get(e.getKey());
                    long updatedAt = (ts != null) ? ts.longValue() : ((lastSyncTimestamp > 0) ? lastSyncTimestamp : now);
                    long deltaMs = Math.max(0L, now - updatedAt);
                    double decay = Math.pow(2.0, -((double) deltaMs / halfLifeMs));
                    double decayedVal = v.doubleValue() * decay;
                    if (decayedVal > 0.0) {
                        combined.merge(e.getKey(), Double.valueOf(decayedVal), (Double a, Double b) -> Double.valueOf((a != null ? a.doubleValue() : 0.0) + (b != null ? b.doubleValue() : 0.0)));
                        totalSum += decayedVal;
                    }
                }
            }

            // Decayed spotifyGenreMass: decays between syncs
            long spotifyUpdatedAt = (lastSyncTimestamp > 0) ? lastSyncTimestamp : now;
            long spotifyDeltaMs = Math.max(0L, now - spotifyUpdatedAt);
            double spotifyDecay = Math.pow(2.0, -((double) spotifyDeltaMs / halfLifeMs));

            for (Map.Entry<String, Double> e : spotifyGenreMass.entrySet()) {
                Double v = e.getValue();
                if (v != null && v.doubleValue() > 0.0) {
                    double decayedVal = v.doubleValue() * spotifyDecay;
                    if (decayedVal > 0.0) {
                        combined.merge(e.getKey(), Double.valueOf(decayedVal), (Double a, Double b) -> Double.valueOf((a != null ? a.doubleValue() : 0.0) + (b != null ? b.doubleValue() : 0.0)));
                        totalSum += decayedVal;
                    }
                }
            }

            if (totalSum <= 0.0 || combined.isEmpty()) {
                cachedGenreDistribution = Collections.emptyMap();
                return cachedGenreDistribution;
            }
            final double s = totalSum;
            Map<String, Double> normalized = new HashMap<>();
            for (Map.Entry<String, Double> e : combined.entrySet()) {
                normalized.put(e.getKey(), Double.valueOf(e.getValue().doubleValue() / s));
            }
            cachedGenreDistribution = Collections.unmodifiableMap(normalized);
            return cachedGenreDistribution;
        }
    }

        /**
     * Registers a dynamic bidirectional connection between two artists (e.g. from Spotify Related Artists).
     */
    public void registerLineage(String artistA, String artistB) {
        if (artistA == null || artistB == null || artistA.isBlank() || artistB.isBlank()) return;
        String aNorm = artistA.toLowerCase(Locale.ROOT).trim();
        String bNorm = artistB.toLowerCase(Locale.ROOT).trim();
        if (aNorm.equals(bNorm)) return;

        dynamicLineage.computeIfAbsent(aNorm, k -> ConcurrentHashMap.newKeySet()).add(bNorm);
        dynamicLineage.computeIfAbsent(bNorm, k -> ConcurrentHashMap.newKeySet()).add(aNorm);
        invalidateAffinityCache();
    }

    /**
     * Retrieves all associated band members, spin-offs, and related artists for a given artist.
     */
    public Set<String> getConnectedArtists(String artist) {
        if (artist == null || artist.isBlank()) return Collections.emptySet();
        String norm = ArtistNames.normalize(artist);

        Set<String> result = new HashSet<>();
        Set<String> staticMatches = STATIC_LINEAGE_GRAPH.get(norm);
        if (staticMatches != null) {
            result.addAll(staticMatches);
        }
        Set<String> dynamicMatches = dynamicLineage.get(norm);
        if (dynamicMatches != null) {
            result.addAll(dynamicMatches);
        }
        if (sourceProfiles != null && !sourceProfiles.isEmpty()) {
            for (PlayerTasteProfile sp : sourceProfiles) {
                result.addAll(sp.getConnectedArtists(artist));
            }
        }
        return result;
    }

    /**
     * Returns the affinity score for a given artist (0.0 to 1.0).
     *
     * Evaluates in 3 tiers:
     * 1. Exact artist match (1.0x weight)
     * 2. Substring / featuring match (0.8x weight)
     * 3. Band <-> solo lineage / related artist association (0.75x weight)
     */
    public double getArtistAffinity(String artist) {
        if (artist == null || artist.isBlank()) return 0.0;
        String key = artist.toLowerCase(Locale.ROOT).trim();
        String normKey = ArtistNames.normalize(key);

        Double cached = affinityLookupCache.get(normKey);
        if (cached != null) return cached;

        double calculated = calculateArtistAffinity(key, normKey);
        affinityLookupCache.put(normKey, calculated);
        return calculated;
    }

    private double calculateArtistAffinity(String key, String normKey) {
        // 1. Exact match
        Double val = artistAffinities.get(normKey);
        if (val == null) {
            val = artistAffinities.get(key);
        }
        if (val != null) return computeDecayedAffinity(normKey, val.doubleValue());

        // 2. Token index lookup (fixes F7: O(1) candidate lookup instead of linear scan across all affinities)
        double highestSub = 0.0;
        Map<String, List<String>> idx = getOrCreateTokenIndex();
        List<String> queryTokens = ArtistNames.splitTokens(normKey);
        Set<String> candidateArtists = new HashSet<>();
        for (String qToken : queryTokens) {
            if (qToken.length() >= 2) {
                List<String> matches = idx.get(qToken);
                if (matches != null) {
                    candidateArtists.addAll(matches);
                }
            }
        }

        for (String cand : candidateArtists) {
            Double aff = artistAffinities.get(cand);
            if (aff != null) {
                double matchFactor = ArtistNames.exactOrCollab(normKey, cand);
                if (matchFactor > 0.0) {
                    double decayedAff = computeDecayedAffinity(cand, aff.doubleValue());
                    double score = decayedAff * matchFactor;
                    if (score > highestSub) highestSub = score;
                }
            }
        }
        if (highestSub > 0.0) {
            return highestSub;
        }

        // 3. Lineage / Band Member / Related Artist match
        double highestLineage = 0.0;
        Set<String> connected = getConnectedArtists(key);
        if (!connected.isEmpty()) {
            for (String rel : connected) {
                Double relAffinity = artistAffinities.get(rel);
                if (relAffinity != null && relAffinity.doubleValue() > 0.0) {
                    double decayedLineageAff = computeDecayedAffinity(rel, relAffinity.doubleValue());
                    double lineageScore = decayedLineageAff * 0.75;
                    if (lineageScore > highestLineage) {
                        highestLineage = lineageScore;
                    }
                }
            }
        }

        return highestLineage;
    }

    private Map<String, List<String>> getOrCreateTokenIndex() {
        Map<String, List<String>> idx = tokenIndex;
        if (idx == null) {
            Map<String, List<String>> newIdx = new HashMap<>();
            for (String affArtist : artistAffinities.keySet()) {
                for (String token : ArtistNames.splitTokens(affArtist)) {
                    if (token.length() >= 2) {
                        newIdx.computeIfAbsent(token, k -> new ArrayList<>()).add(affArtist);
                    }
                }
            }
            tokenIndex = newIdx;
            return newIdx;
        }
        return idx;
    }

    /**
     * Calculates cosine similarity between candidate micro-genres and this profile's normalized genre distribution.
     * Returns a score between 0.0 and 1.0, or -1.0 if the candidate has no genres or profile has no genres (missing feature).
     */
    public double calculateGenreCosineSimilarity(List<String> candidateGenres) {
        if (candidateGenres == null || candidateGenres.isEmpty()) {
            return -1.0; // missing feature
        }
        Map<String, Double> dist = getGenreDistribution();
        if (dist.isEmpty()) {
            return -1.0; // missing feature
        }

        Set<String> cleanCandidate = new HashSet<>();
        for (String g : candidateGenres) {
            if (g != null && !g.isBlank()) {
                cleanCandidate.add(g.toLowerCase(Locale.ROOT).trim());
            }
        }
        if (cleanCandidate.isEmpty()) {
            return -1.0;
        }

        double dotProduct = 0.0;
        for (String g : cleanCandidate) {
            Double p = dist.get(g);
            if (p != null) {
                dotProduct += p;
            }
        }

        double profileNormSq = 0.0;
        for (double p : dist.values()) {
            profileNormSq += p * p;
        }

        if (profileNormSq <= 0.0 || dotProduct <= 0.0) {
            return 0.0;
        }

        double candidateNorm = Math.sqrt(cleanCandidate.size());
        double profileNorm = Math.sqrt(profileNormSq);

        double cosine = dotProduct / (candidateNorm * profileNorm);
        return Math.max(0.0, Math.min(1.0, cosine));
    }

    /**
     * Calculates cosine similarity between candidate micro-genres and this profile's genre distribution.
     * If candidate has no genre tags, returns 0.20 as backward-compatible neutral prior.
     */
    public double calculateGenreMatch(List<String> candidateGenres) {
        double sim = calculateGenreCosineSimilarity(candidateGenres);
        if (sim < 0.0) {
            return 0.2; // neutral prior when missing
        }
        return sim;
    }

    /**
     * Checks if a track is in the fatigue cooldown (last 20 tracks, played within last 45 minutes, or disliked within 7 days).
     */
    public synchronized boolean isFatigued(Track track, int cooldownTracks, long cooldownMs) {
        if (track == null) return false;
        return isFatigued(track.id(), track.title(), track.artist(), cooldownTracks, cooldownMs);
    }

    public synchronized boolean isFatigued(String trackId, String title, String artist, int cooldownTracks, long cooldownMs) {
        if (trackId == null && title == null) return false;

        // Check 7-day dislike soft ban
        if (trackId != null) {
            Long dislikeExpiry = dislikedTracks.get(trackId);
            if (dislikeExpiry != null) {
                if (System.currentTimeMillis() < dislikeExpiry) {
                    return true;
                } else {
                    dislikedTracks.remove(trackId);
                }
            }
        }

        // Check custom track cooldowns (24h early skip, 6h late skip)
        if (trackId != null) {
            Long customExpiry = customTrackCooldowns.get(trackId);
            if (customExpiry != null) {
                if (System.currentTimeMillis() < customExpiry) {
                    return true;
                } else {
                    customTrackCooldowns.remove(trackId);
                }
            }
        }
        if (title != null) {
            String compKey = RecordingIdentity.compositionTitleKey(title, artist);
            if (!compKey.isEmpty()) {
                Long compExpiry = customTrackCooldowns.get(compKey);
                if (compExpiry != null) {
                    if (System.currentTimeMillis() < compExpiry) {
                        return true;
                    } else {
                        customTrackCooldowns.remove(compKey);
                    }
                }
            }
        }

        String canTitle = RecordingIdentity.compositionTitleKey(title, artist);
        long cutoff = System.currentTimeMillis() - cooldownMs;

        int indexFromEnd = 0;
        Iterator<FatigueEntry> it = fatigueHistory.descendingIterator();
        while (it.hasNext()) {
            FatigueEntry entry = it.next();
            boolean idMatch = trackId != null && trackId.equalsIgnoreCase(entry.trackId());
            boolean titleMatch = false;
            if (!canTitle.isEmpty()) {
                String entryCan = RecordingIdentity.compositionTitleKey(entry.title(), entry.artist());
                if (canTitle.equals(entryCan) && (artist == null || entry.artist() == null || ArtistNames.sameArtist(artist, entry.artist()))) {
                    titleMatch = true;
                }
            }

            if (idMatch || titleMatch) {
                if (indexFromEnd < cooldownTracks) {
                    return true; // within last N tracks
                }
                if (entry.playedAt() >= cutoff) {
                    return true; // within last 45 minutes
                }
            }
            indexFromEnd++;
            if (indexFromEnd > 50) break;
        }

        return false;
    }

    public synchronized boolean isFatigued(String trackId, int cooldownTracks, long cooldownMs) {
        return isFatigued(trackId, null, null, cooldownTracks, cooldownMs);
    }

    /**
     * Returns the number of occurrences of this artist in the recent plays window (window of 4).
     */
    public synchronized int getRecentArtistCount(String artist) {
        if (artist == null || artist.isBlank()) return 0;
        int count = 0;
        for (String recent : recentArtists) {
            if (ArtistNames.sameArtist(artist, recent)) {
                count++;
            }
        }
        return count;
    }

    /**
     * @deprecated Use {@link #getRecentArtistCount(String)} instead.
     */
    @Deprecated
    public synchronized boolean isRecentArtist(String artist) {
        return getRecentArtistCount(artist) > 0;
    }

    public synchronized boolean isDisliked(String trackId) {
        if (trackId == null) return false;
        Long expiry = dislikedTracks.get(trackId);
        if (expiry != null) {
            if (System.currentTimeMillis() < expiry) {
                return true;
            }
            dislikedTracks.remove(trackId);
        }
        return false;
    }

    public Set<String> getRecentTrackIds(int maxCount) {
        Set<String> set = new HashSet<>();
        synchronized (this) {
            Iterator<FatigueEntry> it = fatigueHistory.descendingIterator();
            while (it.hasNext() && set.size() < maxCount) {
                set.add(it.next().trackId());
            }
        }
        return set;
    }

    // =========================================================
    // SPOTIFY BLEND (PARTY CENTROID)
    // =========================================================

    /**
     * Computes the centroid taste vector combining multiple players' profiles into a unified party blend.
     */
    public static PlayerTasteProfile blend(List<PlayerTasteProfile> profiles) {
        return blend(UUID.randomUUID(), profiles);
    }

    /**
     * Computes the centroid taste vector for a specific party ID.
     */
    public static PlayerTasteProfile blend(UUID partyUuid, List<PlayerTasteProfile> profiles) {
        if (profiles == null || profiles.isEmpty()) {
            return new PlayerTasteProfile(partyUuid != null ? partyUuid : UUID.randomUUID());
        }
        PlayerTasteProfile blend = new PlayerTasteProfile(partyUuid != null ? partyUuid : UUID.randomUUID(), profiles);

        int n = profiles.size();
        long blendNow = System.currentTimeMillis();

        // 1. Blend artist affinities
        for (PlayerTasteProfile p : profiles) {
            for (Map.Entry<String, Double> e : p.artistAffinities.entrySet()) {
                double effective = p.getEffectiveArtistAffinity(e.getKey());
                blend.artistAffinities.merge(e.getKey(), Double.valueOf(effective / n), (Double a, Double b) -> {
                    double va = (a != null) ? a.doubleValue() : 0.0;
                    double vb = (b != null) ? b.doubleValue() : 0.0;
                    return Double.valueOf(va + vb);
                });
                blend.artistAffinityTimestamps.put(e.getKey(), blendNow);
            }
        }

        // 2. Blend unnormalized genre masses across profiles
        for (PlayerTasteProfile p : profiles) {
            synchronized (p) {
                for (Map.Entry<String, Double> e : p.playGenreMass.entrySet()) {
                    blend.playGenreMass.merge(e.getKey(), Double.valueOf(e.getValue().doubleValue() / n), (Double a, Double b) -> {
                        double va = (a != null) ? a.doubleValue() : 0.0;
                        double vb = (b != null) ? b.doubleValue() : 0.0;
                        return Double.valueOf(va + vb);
                    });
                }
                for (Map.Entry<String, Double> e : p.spotifyGenreMass.entrySet()) {
                    blend.spotifyGenreMass.merge(e.getKey(), Double.valueOf(e.getValue().doubleValue() / n), (Double a, Double b) -> {
                        double va = (a != null) ? a.doubleValue() : 0.0;
                        double vb = (b != null) ? b.doubleValue() : 0.0;
                        return Double.valueOf(va + vb);
                    });
                }
            }
        }

        // 3. Merge fatigue histories: deduplicate by composition key and sort by playedAt ascending
        Map<String, FatigueEntry> latestFatigueByComp = new java.util.HashMap<>();
        for (PlayerTasteProfile p : profiles) {
            synchronized (p) {
                for (FatigueEntry fe : p.fatigueHistory) {
                    if (fe == null) continue;
                    String compKey = RecordingIdentity.compositionTitleKey(fe.title(), fe.artist());
                    if (compKey.isEmpty()) {
                        compKey = (fe.trackId() != null) ? fe.trackId() : (fe.artist() + ":" + fe.title());
                    }
                    FatigueEntry existing = latestFatigueByComp.get(compKey);
                    if (existing == null || fe.playedAt() > existing.playedAt()) {
                        latestFatigueByComp.put(compKey, fe);
                    }
                }
            }
        }

        List<FatigueEntry> sortedFatigue = new ArrayList<>(latestFatigueByComp.values());
        sortedFatigue.sort(java.util.Comparator.comparingLong(FatigueEntry::playedAt));

        // Keep last 50
        if (sortedFatigue.size() > 50) {
            sortedFatigue = sortedFatigue.subList(sortedFatigue.size() - 50, sortedFatigue.size());
        }
        for (FatigueEntry fe : sortedFatigue) {
            blend.fatigueHistory.add(fe);
        }

        // Rebuild recentArtists from the last 4 plays across the merged list
        int recentStart = Math.max(0, sortedFatigue.size() - 4);
        for (int i = recentStart; i < sortedFatigue.size(); i++) {
            FatigueEntry fe = sortedFatigue.get(i);
            String norm = ArtistNames.normalize(fe.artist());
            if (!norm.isEmpty() && !norm.equalsIgnoreCase("unknown")) {
                blend.recentArtists.add(norm);
            }
        }

        // 4. Merge dislike soft bans across all party members
        for (PlayerTasteProfile p : profiles) {
            for (Map.Entry<String, Long> entry : p.dislikedTracks.entrySet()) {
                if (System.currentTimeMillis() < entry.getValue()) {
                    blend.dislikedTracks.put(entry.getKey(), entry.getValue());
                }
            }
        }

        // 5. Merge custom track cooldowns across all party members
        for (PlayerTasteProfile p : profiles) {
            for (Map.Entry<String, Long> entry : p.customTrackCooldowns.entrySet()) {
                if (System.currentTimeMillis() < entry.getValue()) {
                    blend.customTrackCooldowns.merge(entry.getKey(), entry.getValue(), Math::max);
                }
            }
        }

        // 6. Merge dynamic lineage across party members
        for (PlayerTasteProfile p : profiles) {
            for (Map.Entry<String, Set<String>> entry : p.dynamicLineage.entrySet()) {
                blend.dynamicLineage.computeIfAbsent(entry.getKey(), k -> ConcurrentHashMap.newKeySet()).addAll(entry.getValue());
            }
        }

        blend.normalizeAffinities();
        blend.cachedGenreDistribution = null;
        return blend;
    }

    // =========================================================
    // PERSISTENCE & SERIALIZATION
    // =========================================================

    public Map<String, Double> getArtistAffinities() {
        Map<String, Double> effective = new HashMap<>();
        for (Map.Entry<String, Double> e : artistAffinities.entrySet()) {
            effective.put(e.getKey(), computeDecayedAffinity(e.getKey(), e.getValue().doubleValue()));
        }
        return Collections.unmodifiableMap(effective);
    }

    public Map<String, Long> getDislikedTracks() {
        return Collections.unmodifiableMap(dislikedTracks);
    }

    public Map<String, Set<String>> getDynamicLineage() {
        return Collections.unmodifiableMap(dynamicLineage);
    }

    public Map<String, Double> getPlayGenreMass() {
        return Collections.unmodifiableMap(playGenreMass);
    }

    public Map<String, Double> getSpotifyGenreMass() {
        return Collections.unmodifiableMap(spotifyGenreMass);
    }

    public synchronized String exportAffinitiesJson() {
        return new com.google.gson.Gson().toJson(artistAffinities);
    }

    public synchronized String exportGenresJson() {
        return new com.google.gson.Gson().toJson(getGenreDistribution());
    }

    public synchronized String exportGenreMassJson() {
        Map<String, Map<String, Double>> root = new HashMap<>();
        root.put("play", new HashMap<>(playGenreMass));
        root.put("spotify", new HashMap<>(spotifyGenreMass));
        return new com.google.gson.Gson().toJson(root);
    }

    public synchronized String exportDislikesJson() {
        return new com.google.gson.Gson().toJson(dislikedTracks);
    }

    public synchronized String exportLineageJson() {
        return new com.google.gson.Gson().toJson(dynamicLineage);
    }

    @Deprecated
    public synchronized void restoreFromSnapshot(
            Map<String, Double> affinities,
            Map<String, Double> genres,
            Map<String, Long> dislikes,
            Map<String, Set<String>> lineage,
            long lastSync
    ) {
        restoreFromSnapshot(affinities, genres, null, null, dislikes, lineage, lastSync, null);
    }

    @Deprecated
    public synchronized void restoreFromSnapshot(
            Map<String, Double> affinities,
            Map<String, Double> legacyGenres,
            Map<String, Double> playMass,
            Map<String, Double> spotifyMass,
            Map<String, Long> dislikes,
            Map<String, Set<String>> lineage,
            long lastSync
    ) {
        restoreFromSnapshot(affinities, legacyGenres, playMass, spotifyMass, dislikes, lineage, lastSync, null);
    }

    public synchronized void restoreFromSnapshot(
            Map<String, Double> affinities,
            Map<String, Double> legacyGenres,
            Map<String, Double> playMass,
            Map<String, Double> spotifyMass,
            Map<String, Long> dislikes,
            Map<String, Set<String>> lineage,
            long lastSync,
            Map<String, Long> affinityTimestamps
    ) {
        long now = currentTimeMillis();
        long defaultTs = (lastSync > 0) ? lastSync : now;
        if (affinities != null && !affinities.isEmpty()) {
            this.artistAffinities.putAll(affinities);
            for (String artist : affinities.keySet()) {
                Long ts = (affinityTimestamps != null) ? affinityTimestamps.get(artist) : null;
                this.artistAffinityTimestamps.put(artist, (ts != null) ? ts : defaultTs);
            }
        }
        if ((playMass != null && !playMass.isEmpty()) || (spotifyMass != null && !spotifyMass.isEmpty())) {
            if (playMass != null) {
                this.playGenreMass.putAll(playMass);
                for (String g : playMass.keySet()) {
                    this.playGenreMassTimestamps.put(g, defaultTs);
                }
            }
            if (spotifyMass != null) this.spotifyGenreMass.putAll(spotifyMass);
        } else if (legacyGenres != null && !legacyGenres.isEmpty()) {
            this.playGenreMass.putAll(legacyGenres);
            for (String g : legacyGenres.keySet()) {
                this.playGenreMassTimestamps.put(g, defaultTs);
            }
        }
        if (dislikes != null) {
            for (Map.Entry<String, Long> e : dislikes.entrySet()) {
                if (e.getValue() != null && e.getValue().longValue() > now) {
                    this.dislikedTracks.put(e.getKey(), e.getValue());
                }
            }
        }
        if (lineage != null) {
            for (Map.Entry<String, Set<String>> e : lineage.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    this.dynamicLineage.computeIfAbsent(e.getKey(), k -> ConcurrentHashMap.newKeySet()).addAll(e.getValue());
                }
            }
        }
        if (lastSync > 0) {
            this.lastSyncTimestamp = lastSync;
        }
        this.cachedGenreDistribution = null;
        invalidateAffinityCache();
    }

    public synchronized List<FatigueEntry> getFatigueHistory() {
        return new ArrayList<>(this.fatigueHistory);
    }

    public synchronized void restoreFatigue(List<FatigueEntry> recentFatigue) {
        if (recentFatigue == null || recentFatigue.isEmpty()) return;
        this.fatigueHistory.clear();
        this.recentArtists.clear();
        for (FatigueEntry fe : recentFatigue) {
            if (fe != null) {
                this.fatigueHistory.add(fe);
                while (this.fatigueHistory.size() > 50) {
                    this.fatigueHistory.removeFirst();
                }
                if (fe.artist() != null && !fe.artist().isBlank()) {
                    String norm = ArtistNames.normalize(fe.artist());
                    if (!norm.isEmpty() && !norm.equalsIgnoreCase("unknown")) {
                        this.recentArtists.add(norm);
                        while (this.recentArtists.size() > 4) {
                            this.recentArtists.removeFirst();
                        }
                    }
                }
            }
        }
    }
}
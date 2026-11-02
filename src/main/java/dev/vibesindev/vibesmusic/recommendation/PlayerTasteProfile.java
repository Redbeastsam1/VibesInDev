package dev.vibesindev.vibesmusic.recommendation;

import dev.vibesindev.vibesmusic.model.Track;

import java.util.ArrayDeque;
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

    /** Normalized micro-genre distribution: genre tag (lowercase) -> probability P(genre). */
    private final Map<String, Double> genreDistribution = new ConcurrentHashMap<>();

    /** Rolling fatigue history: newest entries at the end. */
    private final Deque<FatigueEntry> fatigueHistory = new ArrayDeque<>();

    /** Rolling recent artists (last 2 songs) to avoid artist monopolization. */
    private final Deque<String> recentArtists = new ArrayDeque<>();

    /** Disliked tracks under 7-day soft cooldown (trackId -> expireTimestamp). */
    private final Map<String, Long> dislikedTracks = new ConcurrentHashMap<>();

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

    public static String canonicalizeTitle(String title, String artist) {
        if (title == null) return "";
        String s = title.toLowerCase(Locale.ROOT).trim();
        if (artist != null && !artist.isBlank()) {
            String a = artist.toLowerCase(Locale.ROOT).trim();
            if (s.startsWith(a + " - ") || s.startsWith(a + " – ") || s.startsWith(a + ": ")) {
                s = s.substring(a.length() + 3).trim();
            }
        }
        // Remove parenthesized or bracketed metadata: (Slowed), [Official Video], (feat. ...), (Single 2025)
        s = s.replaceAll("\\s*\\([^)]*\\)", "").replaceAll("\\s*\\[[^]]*\\]", "");
        // Remove common version keywords outside brackets
        s = s.replaceAll("(?i)\\b(slowed\\s*\\+?\\s*reverb|slowed|reverb|sped\\s*up|speed\\s*up|nightcore|remix|official\\s*video|official\\s*audio|lyric\\s*video|visualizer|single\\s*\\d{4}|feat\\.?.*|ft\\.?.*)\\b", "");
        // Remove non-alphanumeric characters
        s = s.replaceAll("[^a-z0-9\\s]", " ");
        // Collapse spaces
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    public static boolean isSameSongOrRemix(Track a, Track b) {
        if (a == null || b == null) return false;
        if (a.id() != null && b.id() != null && a.id().equalsIgnoreCase(b.id())) {
            return true;
        }
        String canA = canonicalizeTitle(a.title(), a.artist());
        String canB = canonicalizeTitle(b.title(), b.artist());
        if (!canA.isEmpty() && canA.equals(canB)) {
            return true;
        }
        if (!canA.isEmpty() && !canB.isEmpty() && (canA.contains(canB) || canB.contains(canA))) {
            if (ArtistNames.sameArtist(a.artist(), b.artist())) {
                return true;
            }
        }
        return false;
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

    public PlayerTasteProfile(UUID playerUuid) {
        this.playerUuid = playerUuid;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
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
     * Ingests a track play event from Minecraft.
     */
    public synchronized void recordPlay(Track track) {
        if (track == null) return;

        long now = System.currentTimeMillis();
        String trackId = track.id();
        String artist = track.artist().toLowerCase(Locale.ROOT).trim();

        // 1. Fatigue tracking (with canonical title!)
        fatigueHistory.addLast(new FatigueEntry(trackId, track.artist(), track.title(), now));
        while (fatigueHistory.size() > 50) {
            fatigueHistory.removeFirst();
        }

        // 2. Recent artist tracking (keep last 2)
        recentArtists.addLast(artist);
        while (recentArtists.size() > 2) {
            recentArtists.removeFirst();
        }

        // 3. Artist affinity boost
        boostArtist(artist, 0.5);
    }

    /**
     * Ingests a track added to a custom playlist ($2.5\times$ explicit preference boost).
     */
    public synchronized void recordPlaylistAdd(Track track) {
        if (track == null) return;
        String artist = track.artist().toLowerCase(Locale.ROOT).trim();
        boostArtist(artist, 1.25); // $2.5x of base play boost (0.5 * 2.5 = 1.25)
    }

    /**
     * Ingests an explicit thumbs-up like (+0.5 artist affinity boost).
     */
    public synchronized void recordLike(Track track) {
        if (track == null) return;
        dislikedTracks.remove(track.id());
        String artist = track.artist().toLowerCase(Locale.ROOT).trim();
        boostArtist(artist, 2.5); // +0.5 effective affinity boost
    }

    /**
     * Ingests an explicit thumbs-down dislike: applies a 7-day soft ban and downweights artist by 50%.
     */
    public synchronized void recordDislike(Track track) {
        if (track == null) return;
        long sevenDays = 7L * 24 * 60 * 60 * 1000L;
        dislikedTracks.put(track.id(), System.currentTimeMillis() + sevenDays);
        fatigueHistory.addLast(new FatigueEntry(track.id(), track.artist(), track.title(), System.currentTimeMillis()));
        while (fatigueHistory.size() > 50) {
            fatigueHistory.removeFirst();
        }
        String artist = track.artist().toLowerCase(Locale.ROOT).trim();
        downweightArtist(artist, 0.50);
    }

    /**
     * Ingests an early skip (< 30s): downweights artist affinity by 20%.
     */
    public synchronized void recordSkip(Track track) {
        if (track == null) return;
        // SKIPPED TRACKS MUST ENTER FATIGUE COOLDOWN SO THEY ARE NOT RECOMMENDED AGAIN
        long now = System.currentTimeMillis();
        fatigueHistory.addLast(new FatigueEntry(track.id(), track.artist(), track.title(), now));
        while (fatigueHistory.size() > 50) {
            fatigueHistory.removeFirst();
        }
        String artist = track.artist().toLowerCase(Locale.ROOT).trim();
        downweightArtist(artist, 0.20);
    }

    private void boostArtist(String artist, double boost) {
        if (artist == null || artist.isBlank() || artist.equalsIgnoreCase("unknown")) return;
        artistAffinities.compute(artist, (k, oldVal) -> {
            if (oldVal == null) {
                return Double.valueOf(Math.min(1.0, Math.max(0.0, boost * 0.2)));
            }
            return Double.valueOf(Math.min(1.0, Math.max(0.0, oldVal.doubleValue() + boost * 0.2)));
        });
    }

    private void downweightArtist(String artist, double factor) {
        if (artist == null || artist.isBlank() || artist.equalsIgnoreCase("unknown")) return;
        artistAffinities.computeIfPresent(artist, (k, oldVal) -> {
            double ov = (oldVal != null) ? oldVal.doubleValue() : 0.0;
            return Double.valueOf(Math.max(0.01, ov * (1.0 - factor)));
        });
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
                rank++;
            }
        }

        normalizeAffinities();
        this.lastSyncTimestamp = System.currentTimeMillis();
    }

    /**
     * Ingests micro-genres for an artist and updates the normalized genre distribution.
     */
    public synchronized void ingestArtistGenres(List<String> genres) {
        if (genres == null || genres.isEmpty()) return;

        for (String g : genres) {
            if (g == null || g.isBlank()) continue;
            String key = g.toLowerCase(Locale.ROOT).trim();
            genreDistribution.merge(key, Double.valueOf(1.0), (Double a, Double b) -> {
                double va = (a != null) ? a.doubleValue() : 0.0;
                double vb = (b != null) ? b.doubleValue() : 0.0;
                return Double.valueOf(va + vb);
            });
        }

        normalizeGenres();
    }

    private void normalizeAffinities() {
        if (artistAffinities.isEmpty()) return;
        double max = 0.0;
        for (double val : artistAffinities.values()) {
            if (val > max) max = val;
        }
        if (max > 1.0) {
            for (Map.Entry<String, Double> e : artistAffinities.entrySet()) {
                e.setValue(e.getValue() / max);
            }
        }
    }

    private void normalizeGenres() {
        if (genreDistribution.isEmpty()) return;
        double sum = 0.0;
        for (double val : genreDistribution.values()) {
            sum += val;
        }
        if (sum > 0) {
            for (Map.Entry<String, Double> e : genreDistribution.entrySet()) {
                e.setValue(e.getValue() / sum);
            }
        }
    }

    // =========================================================
    // SCORING QUERIES
    // =========================================================

    /**
     * Returns the affinity score for a given artist (0.0 to 1.0).
     */
    public Map<String, Double> getGenreDistribution() {
        return Collections.unmodifiableMap(genreDistribution);
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
        // 1. Exact match
        Double val = artistAffinities.get(normKey);
        if (val == null) {
            val = artistAffinities.get(key);
        }
        if (val != null) return val;

        // 2. Token / featuring match (fixes F1 short name false positives)
        double highestSub = 0.0;
        for (Map.Entry<String, Double> e : artistAffinities.entrySet()) {
            double matchFactor = ArtistNames.exactOrCollab(key, e.getKey());
            if (matchFactor > 0.0) {
                double score = e.getValue() * matchFactor;
                if (score > highestSub) highestSub = score;
            }
        }
        if (highestSub > 0.0) {
            return highestSub;
        }

        // 3. Lineage / Band Member / Related Artist match
        // E.g. Player likes "one direction" (1.0 affinity) -> "harry styles" inherits 0.75 affinity!
        double highestLineage = 0.0;
        Set<String> connected = getConnectedArtists(key);
        if (!connected.isEmpty()) {
            for (String rel : connected) {
                Double relAffinity = artistAffinities.get(rel);
                if (relAffinity != null && relAffinity > 0.0) {
                    double lineageScore = relAffinity * 0.75;
                    if (lineageScore > highestLineage) {
                        highestLineage = lineageScore;
                    }
                }
            }
        }

        return highestLineage;
    }

    /**
     * Calculates cosine similarity between candidate micro-genres and this profile's genre distribution.
     * Returns 0.0 to 1.0.
     */
    public double calculateGenreMatch(List<String> candidateGenres) {
        if (candidateGenres == null || candidateGenres.isEmpty() || genreDistribution.isEmpty()) {
            return 0.2; // neutral prior when no genre tags exist
        }

        double matchScore = 0.0;
        int count = 0;
        for (String g : candidateGenres) {
            if (g == null || g.isBlank()) continue;
            String key = g.toLowerCase(Locale.ROOT).trim();
            Double prob = genreDistribution.get(key);
            if (prob != null) {
                matchScore += prob * 10.0; // scale normalized prob
            }
            count++;
        }

        if (count == 0) return 0.2;
        return Math.min(1.0, matchScore / count);
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

        String canTitle = canonicalizeTitle(title, artist);
        String normArtist = artist != null ? artist.toLowerCase(Locale.ROOT).trim() : "";
        long cutoff = System.currentTimeMillis() - cooldownMs;

        int indexFromEnd = 0;
        Iterator<FatigueEntry> it = fatigueHistory.descendingIterator();
        while (it.hasNext()) {
            FatigueEntry entry = it.next();
            boolean idMatch = trackId != null && trackId.equalsIgnoreCase(entry.trackId());
            boolean titleMatch = false;
            if (!canTitle.isEmpty()) {
                String entryCan = canonicalizeTitle(entry.title(), entry.artist());
                if (canTitle.equals(entryCan)) {
                    titleMatch = true;
                } else if ((canTitle.contains(entryCan) || entryCan.contains(canTitle)) && !normArtist.isEmpty()) {
                    if (ArtistNames.sameArtist(artist, entry.artist())) {
                        titleMatch = true;
                    }
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
     * Returns true if the artist was played in the last 2 tracks.
     */
    public synchronized boolean isRecentArtist(String artist) {
        if (artist == null || artist.isBlank()) return false;
        for (String recent : recentArtists) {
            if (ArtistNames.sameArtist(artist, recent)) {
                return true;
            }
        }
        return false;
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
        PlayerTasteProfile blend = new PlayerTasteProfile(UUID.randomUUID());
        if (profiles == null || profiles.isEmpty()) return blend;

        int n = profiles.size();

        // 1. Blend artist affinities
        for (PlayerTasteProfile p : profiles) {
            for (Map.Entry<String, Double> e : p.artistAffinities.entrySet()) {
                blend.artistAffinities.merge(e.getKey(), Double.valueOf(e.getValue().doubleValue() / n), (Double a, Double b) -> {
                    double va = (a != null) ? a.doubleValue() : 0.0;
                    double vb = (b != null) ? b.doubleValue() : 0.0;
                    return Double.valueOf(va + vb);
                });
            }
        }

        // 2. Blend genre distributions
        for (PlayerTasteProfile p : profiles) {
            for (Map.Entry<String, Double> e : p.genreDistribution.entrySet()) {
                blend.genreDistribution.merge(e.getKey(), Double.valueOf(e.getValue().doubleValue() / n), (Double a, Double b) -> {
                    double va = (a != null) ? a.doubleValue() : 0.0;
                    double vb = (b != null) ? b.doubleValue() : 0.0;
                    return Double.valueOf(va + vb);
                });
            }
        }

        // 3. Merge fatigue histories so nobody hears their recent songs
        for (PlayerTasteProfile p : profiles) {
            synchronized (p) {
                for (FatigueEntry fe : p.fatigueHistory) {
                    blend.fatigueHistory.add(fe);
                }
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

        // 5. Merge dynamic lineage across party members
        for (PlayerTasteProfile p : profiles) {
            for (Map.Entry<String, Set<String>> entry : p.dynamicLineage.entrySet()) {
                blend.dynamicLineage.computeIfAbsent(entry.getKey(), k -> ConcurrentHashMap.newKeySet()).addAll(entry.getValue());
            }
        }

        blend.normalizeAffinities();
        blend.normalizeGenres();
        return blend;
    }

    // =========================================================
    // PERSISTENCE & SERIALIZATION
    // =========================================================

    public Map<String, Double> getArtistAffinities() {
        return Collections.unmodifiableMap(artistAffinities);
    }

    public Map<String, Long> getDislikedTracks() {
        return Collections.unmodifiableMap(dislikedTracks);
    }

    public Map<String, Set<String>> getDynamicLineage() {
        return Collections.unmodifiableMap(dynamicLineage);
    }

    public synchronized String exportAffinitiesJson() {
        return new com.google.gson.Gson().toJson(artistAffinities);
    }

    public synchronized String exportGenresJson() {
        return new com.google.gson.Gson().toJson(genreDistribution);
    }

    public synchronized String exportDislikesJson() {
        return new com.google.gson.Gson().toJson(dislikedTracks);
    }

    public synchronized String exportLineageJson() {
        return new com.google.gson.Gson().toJson(dynamicLineage);
    }

    public synchronized void restoreFromSnapshot(
            Map<String, Double> affinities,
            Map<String, Double> genres,
            Map<String, Long> dislikes,
            Map<String, Set<String>> lineage,
            long lastSync
    ) {
        if (affinities != null && !affinities.isEmpty()) {
            this.artistAffinities.putAll(affinities);
        }
        if (genres != null && !genres.isEmpty()) {
            this.genreDistribution.putAll(genres);
        }
        if (dislikes != null) {
            long now = System.currentTimeMillis();
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
    }

    public synchronized void restoreFatigue(List<FatigueEntry> recentFatigue) {
        if (recentFatigue == null || recentFatigue.isEmpty()) return;
        this.fatigueHistory.clear();
        this.recentArtists.clear();
        for (FatigueEntry fe : recentFatigue) {
            if (fe != null) {
                this.fatigueHistory.add(fe);
                if (fe.artist() != null && !fe.artist().isBlank()) {
                    this.recentArtists.add(fe.artist().toLowerCase(Locale.ROOT).trim());
                    while (this.recentArtists.size() > 2) {
                        this.recentArtists.removeFirst();
                    }
                }
            }
        }
    }
}
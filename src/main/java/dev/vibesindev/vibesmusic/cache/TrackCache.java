package dev.vibesindev.vibesmusic.cache;

import java.util.concurrent.CompletableFuture;
import java.util.Locale;

import dev.vibesindev.vibesmusic.VibesInDevPlugin;
import dev.vibesindev.vibesmusic.model.Track;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class TrackCache {

    public record TasteProfileSnapshot(
            Map<String, Double> affinities,
            Map<String, Double> genres,
            Map<String, Long> dislikes,
            Map<String, Set<String>> lineage,
            long lastSync,
            Map<String, Double> playGenreMass,
            Map<String, Double> spotifyGenreMass,
            Map<String, Long> affinityTimestamps
    ) {
        public TasteProfileSnapshot(
                Map<String, Double> affinities,
                Map<String, Double> genres,
                Map<String, Long> dislikes,
                Map<String, Set<String>> lineage,
                long lastSync,
                Map<String, Double> playGenreMass,
                Map<String, Double> spotifyGenreMass
        ) {
            this(affinities, genres, dislikes, lineage, lastSync, playGenreMass, spotifyGenreMass, null);
        }

        public TasteProfileSnapshot(
                Map<String, Double> affinities,
                Map<String, Double> genres,
                Map<String, Long> dislikes,
                Map<String, Set<String>> lineage,
                long lastSync
        ) {
            this(affinities, genres, dislikes, lineage, lastSync, null, null, null);
        }
    }

    private final Path root;
    private final Path audio;
    private final Path raw;
    private final VibesInDevPlugin plugin;
    private final String dbUrl;
    private static final long CLEANUP_COOLDOWN_MS = 5 * 60 * 1000L;
    private volatile long lastCleanupCheck = 0;

    public TrackCache(VibesInDevPlugin plugin) {
        this.plugin = plugin;

        root = plugin.getDataFolder()
                .toPath()
                .resolve(
                        plugin.getConfig()
                                .getString(
                                        "cache.directory",
                                        "cache"
                                )
                );

        audio = root.resolve("audio");

        raw = root.resolve(
                plugin.getConfig()
                        .getString(
                                "cache.raw-directory",
                                "raw"
                        )
        );

        try {
            Files.createDirectories(audio);
            Files.createDirectories(raw);
        } catch (IOException e) {
            throw new RuntimeException(
                    "Could not create cache directories",
                    e
            );
        }

        dbUrl = "jdbc:sqlite:" + root.resolve("metadata.db").toAbsolutePath().toString();
        initDatabase();
        validateCache();
    }

    private void initDatabase() {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            plugin.getLogger().warning("SQLite JDBC driver not found! Metadata cache might fail.");
        }

        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode = WAL;");
            stmt.execute("PRAGMA synchronous = NORMAL;");
            stmt.execute("CREATE TABLE IF NOT EXISTS tracks (" +
                    "id TEXT PRIMARY KEY, " +
                    "title TEXT, " +
                    "artist TEXT, " +
                    "duration INTEGER, " +
                    "source TEXT, " +
                    "last_used INTEGER" +
                    ")");
            stmt.execute("CREATE TABLE IF NOT EXISTS player_history (" +
                    "row_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "player_uuid TEXT NOT NULL, " +
                    "track_id TEXT NOT NULL, " +
                    "played_at INTEGER NOT NULL, " +
                    "liked INTEGER DEFAULT 0, " +
                    "skipped INTEGER DEFAULT 0, " +
                    "FOREIGN KEY (track_id) REFERENCES tracks(id) ON DELETE CASCADE" +
                    ")");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_player_history_uuid ON player_history(player_uuid, played_at DESC)");
            stmt.execute("CREATE TABLE IF NOT EXISTS player_taste_profiles (" +
                    "player_uuid TEXT PRIMARY KEY, " +
                    "affinities_json TEXT, " +
                    "genres_json TEXT, " +
                    "dislikes_json TEXT, " +
                    "lineage_json TEXT, " +
                    "last_sync INTEGER, " +
                    "genre_mass_json TEXT, " +
                    "affinity_ts_json TEXT" +
                    ")");
            try {
                stmt.execute("ALTER TABLE player_taste_profiles ADD COLUMN genre_mass_json TEXT");
            } catch (SQLException ignored) {
                // Column already exists
            }
            try {
                stmt.execute("ALTER TABLE player_taste_profiles ADD COLUMN affinity_ts_json TEXT");
            } catch (SQLException ignored) {
                // Column already exists
            }
            stmt.execute("CREATE TABLE IF NOT EXISTS artist_genres (" +
                    "artist_norm TEXT PRIMARY KEY, " +
                    "genres_json TEXT NOT NULL, " +
                    "source TEXT NOT NULL, " +
                    "fetched_at INTEGER NOT NULL" +
                    ")");
            stmt.execute("CREATE TABLE IF NOT EXISTS rec_impressions (" +
                    "impression_id TEXT PRIMARY KEY, " +
                    "ts INTEGER NOT NULL, " +
                    "player_uuid TEXT NOT NULL, " +
                    "party_uuid TEXT, " +
                    "seed_track_id TEXT, " +
                    "engine_version TEXT NOT NULL, " +
                    "model_version TEXT, " +
                    "requested_mode TEXT, " +
                    "effective_mode TEXT, " +
                    "mode_probability REAL, " +
                    "mode_fallbacks_json TEXT, " +
                    "relaxation_level INTEGER DEFAULT 0, " +
                    "raw_candidates INTEGER, " +
                    "eligible_candidates INTEGER, " +
                    "rejection_counts_json TEXT, " +
                    "provider_status_json TEXT, " +
                    "latency_ms INTEGER, " +
                    "selected_track_id TEXT, " +
                    "completion REAL, " +
                    "outcome TEXT" +
                    ")");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_rec_impr_player_ts ON rec_impressions(player_uuid, ts);");
            stmt.execute("CREATE TABLE IF NOT EXISTS rec_candidates (" +
                    "impression_id TEXT NOT NULL, " +
                    "track_id TEXT NOT NULL, " +
                    "rank INTEGER, " +
                    "source TEXT, " +
                    "score REAL, " +
                    "heuristic_score REAL, " +
                    "learned_score REAL, " +
                    "selection_probability REAL, " +
                    "selected INTEGER NOT NULL DEFAULT 0, " +
                    "features_json TEXT, " +
                    "PRIMARY KEY (impression_id, track_id)" +
                    ")");
            stmt.execute("CREATE TABLE IF NOT EXISTS player_spotify_tracks (" +
                    "player_uuid TEXT NOT NULL, " +
                    "track_id TEXT NOT NULL, " +
                    "source TEXT NOT NULL, " +
                    "title TEXT NOT NULL, " +
                    "artist TEXT NOT NULL, " +
                    "duration_s INTEGER NOT NULL, " +
                    "added_at INTEGER, " +
                    "synced_at INTEGER NOT NULL, " +
                    "PRIMARY KEY (player_uuid, track_id, source)" +
                    ")");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_pst_player_uuid ON player_spotify_tracks(player_uuid);");
            stmt.execute("CREATE TABLE IF NOT EXISTS artist_relations (" +
                    "a_norm TEXT NOT NULL, " +
                    "b_norm TEXT NOT NULL, " +
                    "kind TEXT NOT NULL, " +
                    "weight REAL NOT NULL, " +
                    "source TEXT NOT NULL, " +
                    "fetched_at INTEGER NOT NULL, " +
                    "PRIMARY KEY (a_norm, b_norm)" +
                    ")");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_artist_relations_a ON artist_relations(a_norm);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_artist_relations_b ON artist_relations(b_norm);");

            // Schema migrations for pre-existing tables
            try {
                stmt.execute("ALTER TABLE player_history ADD COLUMN liked INTEGER DEFAULT 0;");
            } catch (SQLException ignored) {}
            try {
                stmt.execute("ALTER TABLE player_history ADD COLUMN skipped INTEGER DEFAULT 0;");
            } catch (SQLException ignored) {}
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not initialize TrackCache database: " + e.getMessage());
        }
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(dbUrl);
    }

    private void validateCache() {
        System.out.println("[VibesInDev] Validating cache database...");
        int cleaned = 0;

        // 1. Clear raw directory (failed/partial downloads)
        try {
            if (Files.isDirectory(raw)) {
                try (java.util.stream.Stream<Path> rawFiles = Files.list(raw)) {
                    for (Path rawFile : rawFiles.toList()) {
                        if (Files.isRegularFile(rawFile)) {
                            Files.deleteIfExists(rawFile);
                            cleaned++;
                        }
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("[VibesInDev] Failed to clear raw directory: " + e.getMessage());
        }

        // 2. Validate audio and DB consistency
        try {
            List<String> validIds = new ArrayList<>();
            
            if (Files.isDirectory(audio)) {
                try (java.util.stream.Stream<Path> audioFiles = Files.list(audio)) {
                    for (Path audioFile : audioFiles.toList()) {
                        if (!Files.isRegularFile(audioFile)) continue;

                        String fileName = audioFile.getFileName().toString();
                        if (!fileName.endsWith(".ogg")) continue;

                        String id = fileName.substring(0, fileName.length() - 4);

                        // Corrupt file detection
                        if (Files.size(audioFile) == 0) {
                            Files.deleteIfExists(audioFile);
                            cleaned++;
                            continue;
                        }
                        
                        validIds.add(id);
                    }
                }
            }

            // Sync database
            try (Connection conn = getConnection()) {
                // Delete tracks from DB that have no corresponding audio file
                List<String> dbIds = new ArrayList<>();
                try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT id FROM tracks")) {
                    while (rs.next()) {
                        dbIds.add(rs.getString("id"));
                    }
                }

                try (PreparedStatement deleteStmt = conn.prepareStatement("DELETE FROM tracks WHERE id = ?")) {
                    for (String dbId : dbIds) {
                        if (!validIds.contains(dbId)) {
                            deleteStmt.setString(1, dbId);
                            deleteStmt.addBatch();
                            cleaned++;
                        }
                    }
                    deleteStmt.executeBatch();
                }

                // Delete audio files that have no DB entry
                for (String validId : validIds) {
                    if (!dbIds.contains(validId)) {
                        Files.deleteIfExists(audio(validId));
                        cleaned++;
                    }
                }

                // Purge corrupted tracks where title = artist from older Spotify regex bugs
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT id FROM tracks WHERE LOWER(TRIM(title)) = LOWER(TRIM(artist)) AND (source LIKE '%spotify%' OR source LIKE 'spotify%')")) {
                    List<String> corruptIds = new ArrayList<>();
                    while (rs.next()) {
                        corruptIds.add(rs.getString("id"));
                    }
                    for (String cId : corruptIds) {
                        Files.deleteIfExists(audio(cId));
                        try (PreparedStatement del = conn.prepareStatement("DELETE FROM tracks WHERE id = ?")) {
                            del.setString(1, cId);
                            del.executeUpdate();
                            cleaned++;
                        }
                    }
                    if (!corruptIds.isEmpty()) {
                        System.out.println("[VibesInDev] Purged " + corruptIds.size() + " corrupted/stale Spotify cache entries.");
                    }
                }
            }
            
            // Clean up old .properties files if migrating
            if (Files.isDirectory(root)) {
                try (java.util.stream.Stream<Path> oldFiles = Files.list(root)) {
                    for (Path oldFile : oldFiles.toList()) {
                        if (Files.isRegularFile(oldFile) && oldFile.getFileName().toString().endsWith(".properties")) {
                            Files.deleteIfExists(oldFile);
                            cleaned++;
                        }
                    }
                }
            }

        } catch (IOException | SQLException e) {
            System.err.println("[VibesInDev] Failed to validate audio cache: " + e.getMessage());
        }

        if (cleaned > 0) {
            System.out.println("[VibesInDev] Cache validation complete. Cleaned " + cleaned + " invalid/partial files.");
        }

        cleanupOldTracks(null);
    }

    public Path audioDirectory() {
        return audio;
    }

    public Path rawDirectory() {
        return raw;
    }

    public Path audio(String id) {
        return audio.resolve(id + ".ogg");
    }

    public boolean hasAudio(String id) {
        if (!plugin.getConfig().getBoolean("cache.enabled", true)) {
            return false;
        }

        Path file = audio(id);

        if (!Files.isRegularFile(file)) {
            return false;
        }

        try {
            if (Files.size(file) == 0) {
                Files.deleteIfExists(file);
                try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement("DELETE FROM tracks WHERE id = ?")) {
                    ps.setString(1, id);
                    ps.executeUpdate();
                } catch (SQLException ignored) {}
                return false;
            }
        } catch (IOException e) {
            return false;
        }

        // Touch the file and DB whenever it is used
        long now = System.currentTimeMillis();
        try {
            Files.setLastModifiedTime(file, FileTime.fromMillis(now));
        } catch (IOException ignored) {}

        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement("UPDATE tracks SET last_used = ? WHERE id = ?")) {
            ps.setLong(1, now);
            ps.setString(2, id);
            ps.executeUpdate();
        } catch (SQLException ignored) {}

        return true;
    }

    public void writeMetadata(Track track) throws IOException {
        long now = System.currentTimeMillis();
        
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO tracks (id, title, artist, duration, source, last_used) VALUES (?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(id) DO UPDATE SET title=excluded.title, artist=excluded.artist, duration=excluded.duration, source=excluded.source, last_used=excluded.last_used")) {
            
            ps.setString(1, track.id());
            ps.setString(2, track.title());
            ps.setString(3, track.artist());
            ps.setLong(4, track.durationSeconds());
            ps.setString(5, track.sourceUrl());
            ps.setLong(6, now);
            ps.executeUpdate();
            
        } catch (SQLException e) {
            throw new IOException("Failed to write metadata to database", e);
        }

        Path audioFile = audio(track.id());
        if (Files.isRegularFile(audioFile)) {
            try {
                Files.setLastModifiedTime(audioFile, FileTime.fromMillis(now));
            } catch (IOException ignored) {}
        }

        java.util.concurrent.CompletableFuture.runAsync(() -> cleanupOldTracks(track.id()));
    }

    public void cleanupOldTracks(String protectedId) {
        if (!plugin.getConfig().getBoolean("cache.cleanup-enabled", true)) {
            return;
        }

        long now = System.currentTimeMillis();
        if (protectedId != null && (now - lastCleanupCheck < CLEANUP_COOLDOWN_MS)) {
            return;
        }
        lastCleanupCheck = now;

        try {
            if (!Files.isDirectory(audio)) {
                return;
            }

            long totalSizeBytes = 0;
            try (java.util.stream.Stream<Path> audioFiles = Files.list(audio)) {
                for (Path audioFile : audioFiles.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().endsWith(".ogg")).toList()) {
                    totalSizeBytes += Files.size(audioFile);
                }
            }

            double maxGb = plugin.getConfig().getDouble("cache.max-size-gb", 20.0);
            long maxBytes = (long) (maxGb * 1024 * 1024 * 1024);

            if (totalSizeBytes <= maxBytes) {
                return;
            }

            try (Connection conn = getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT id FROM tracks ORDER BY last_used ASC")) {
                
                while (rs.next() && totalSizeBytes > maxBytes) {
                    String id = rs.getString("id");

                    if (id.equals(protectedId)) {
                        continue;
                    }

                    Path audioFile = audio(id);
                    if (Files.isRegularFile(audioFile)) {
                        long size = Files.size(audioFile);
                        Files.deleteIfExists(audioFile);
                        totalSizeBytes -= size;
                        System.out.println("[VibesInDev] Removed old cached track to save space: " + id);
                    }
                    
                    try (PreparedStatement deletePs = conn.prepareStatement("DELETE FROM tracks WHERE id = ?")) {
                        deletePs.setString(1, id);
                        deletePs.executeUpdate();
                    }
                }
            }

        } catch (IOException | SQLException e) {
            System.err.println("[VibesInDev] Could not clean music cache: " + e.getMessage());
        }
    }

    public void deleteRawFile(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            System.err.println(
                    "[VibesInDev] Could not delete temporary raw audio: " + file.getFileName().toString() + " — " + e.getMessage()
            );
        }
    }


    // =======================================================
    // PLAYER HISTORY (persisted)
    // =======================================================

    /**
     * Persists one history entry.  The in-memory deque is still
     * the authority for the current session; this just survives restarts.
     */
    public void persistHistory(java.util.UUID playerUuid, Track track) {
        if (track == null || playerUuid == null) return;

        // Ensure the track exists in the tracks table first
        try {
            Track existing = readMetadata(track.id());
            if (existing == null) {
                writeMetadataSilent(track);
            }
        } catch (java.io.IOException ignored) {}

        int historyLimit = plugin.getConfig().getInt("playback.history-limit", 50);

        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Insert the new row
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO player_history (player_uuid, track_id, played_at) VALUES (?, ?, ?)")) {
                    ps.setString(1, playerUuid.toString());
                    ps.setString(2, track.id());
                    ps.setLong(3, System.currentTimeMillis());
                    ps.executeUpdate();
                }

                // Trim to historyLimit (delete oldest beyond the cap)
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM player_history WHERE player_uuid = ? AND row_id NOT IN (" +
                        "  SELECT row_id FROM player_history WHERE player_uuid = ?" +
                        "  ORDER BY played_at DESC LIMIT ?" +
                        ")")) {
                    ps.setString(1, playerUuid.toString());
                    ps.setString(2, playerUuid.toString());
                    ps.setInt(3, historyLimit);
                    ps.executeUpdate();
                }

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[VibesInDev] Could not persist history: " + e.getMessage());
        }
    }

    /**
     * Loads the persisted history for a player, newest first.
     */

    /**
     * Records or updates whether a player liked a track in their listening history.
     */
    public void recordTrackLiked(UUID playerUuid, String trackId, boolean liked) {
        if (playerUuid == null || trackId == null) return;
        String sql = "UPDATE player_history SET liked = ? WHERE row_id = (" +
                "  SELECT row_id FROM player_history WHERE player_uuid = ? AND track_id = ? ORDER BY played_at DESC LIMIT 1" +
                ")";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, liked ? 1 : 0);
            ps.setString(2, playerUuid.toString());
            ps.setString(3, trackId);
            int rows = ps.executeUpdate();
            if (rows == 0 && liked) {
                // Not found in recent history - insert fresh record
                try (PreparedStatement insertPs = conn.prepareStatement(
                        "INSERT INTO player_history (player_uuid, track_id, played_at, liked, skipped) VALUES (?, ?, ?, 1, 0)")) {
                    insertPs.setString(1, playerUuid.toString());
                    insertPs.setString(2, trackId);
                    insertPs.setLong(3, System.currentTimeMillis());
                    insertPs.executeUpdate();
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[VibesInDev] Could not record track like: " + e.getMessage());
        }
    }

    /**
     * Records that a track was skipped early by a player.
     */
    public void recordTrackSkipped(UUID playerUuid, String trackId) {
        if (playerUuid == null || trackId == null) return;
        String sql = "UPDATE player_history SET skipped = 1 WHERE row_id = (" +
                "  SELECT row_id FROM player_history WHERE player_uuid = ? AND track_id = ? ORDER BY played_at DESC LIMIT 1" +
                ")";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            ps.setString(2, trackId);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("[VibesInDev] Could not record track skip: " + e.getMessage());
        }
    }

    /**
     * Checks if a player has marked this track as liked.
     */
    public boolean isTrackLiked(UUID playerUuid, String trackId) {
        if (playerUuid == null || trackId == null) return false;
        String sql = "SELECT liked FROM player_history WHERE player_uuid = ? AND track_id = ? AND liked = 1 LIMIT 1";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            ps.setString(2, trackId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    public java.util.List<Track> loadHistory(java.util.UUID playerUuid) {
        java.util.List<Track> result = new java.util.ArrayList<>();
        String sql =
                "SELECT t.id, t.title, t.artist, t.duration, t.source " +
                "FROM player_history ph JOIN tracks t ON ph.track_id = t.id " +
                "WHERE ph.player_uuid = ? ORDER BY ph.played_at DESC";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new Track(
                            rs.getString("id"),
                            rs.getString("title"),
                            rs.getString("artist"),
                            rs.getLong("duration"),
                            rs.getString("source")
                    ));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[VibesInDev] Could not load history: " + e.getMessage());
        }
        return result;
    }

    /**
     * Clears all persisted history for a player.
     */
    public void clearPlayerHistory(java.util.UUID playerUuid) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM player_history WHERE player_uuid = ?")) {
            ps.setString(1, playerUuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("[VibesInDev] Could not clear history: " + e.getMessage());
        }
    }

    /** Write metadata without throwing - used when ensuring history track exists. */
    private void writeMetadataSilent(Track track) {
        try { writeMetadata(track); } catch (java.io.IOException ignored) {}
    }
    public void invalidate(String id) {
        if (id == null || id.isBlank()) return;
        try {
            Files.deleteIfExists(audio(id));
            try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement("DELETE FROM tracks WHERE id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
        } catch (Exception ignored) {
        }
    }

    public Track readMetadataQuietly(String id) {
        if (id == null || id.isBlank()) return null;
        try {
            return readMetadata(id);
        } catch (Exception ignored) {
            return null;
        }
    }

    public Track readMetadata(String id) throws IOException {
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement("SELECT * FROM tracks WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new Track(
                            rs.getString("id"),
                            rs.getString("title"),
                            rs.getString("artist"),
                            rs.getLong("duration"),
                            rs.getString("source")
                    );
                }
            }
        } catch (SQLException e) {
            throw new IOException("Failed to read metadata from database", e);
        }
        return null;
    }

    // =======================================================
    // RECOMMENDATION & COLLABORATIVE FILTERING QUERIES
    // =======================================================

    /**
     * Returns the player's most listened artists with play counts from SQLite history.
     */
    public java.util.Map<String, Integer> getTopArtistsForPlayer(java.util.UUID playerUuid, int limit) {
        java.util.Map<String, Integer> result = new java.util.LinkedHashMap<>();
        String sql = "SELECT t.artist, COUNT(*) as play_count " +
                     "FROM player_history ph JOIN tracks t ON ph.track_id = t.id " +
                     "WHERE ph.player_uuid = ? " +
                     "GROUP BY t.artist " +
                     "ORDER BY play_count DESC " +
                     "LIMIT ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String artist = rs.getString("artist");
                    if (artist != null && !artist.isBlank() && !artist.equalsIgnoreCase("unknown")) {
                        result.put(artist, rs.getInt("play_count"));
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[VibesInDev] Error querying top artists: " + e.getMessage());
        }
        return result;
    }

    /**
     * Collaborative filtering query: "Players who played Artist X also played Artist Y".
     */
    public java.util.List<String> getCoPlayedArtists(String seedArtist, int limit) {
        java.util.List<String> result = new java.util.ArrayList<>();
        if (seedArtist == null || seedArtist.isBlank()) return result;

        String sql = "SELECT t2.artist, COUNT(*) as coplay_count " +
                     "FROM player_history ph1 " +
                     "JOIN player_history ph2 ON ph1.player_uuid = ph2.player_uuid " +
                     "JOIN tracks t1 ON ph1.track_id = t1.id " +
                     "JOIN tracks t2 ON ph2.track_id = t2.id " +
                     "WHERE LOWER(TRIM(t1.artist)) = LOWER(TRIM(?)) AND LOWER(TRIM(t2.artist)) != LOWER(TRIM(?)) " +
                     "GROUP BY t2.artist " +
                     "ORDER BY coplay_count DESC " +
                     "LIMIT ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, seedArtist);
            ps.setString(2, seedArtist);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String artist = rs.getString("artist");
                    if (artist != null && !artist.isBlank() && !artist.equalsIgnoreCase("unknown")) {
                        result.add(artist);
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[VibesInDev] Error querying co-played artists: " + e.getMessage());
        }
        return result;
    }

    /**
     * Surfaces cached tracks by a given artist.
     */
    public java.util.List<Track> getTracksByArtist(String artist, int limit) {
        java.util.List<Track> result = new java.util.ArrayList<>();
        if (artist == null || artist.isBlank()) return result;

        String sql = "SELECT id, title, artist, duration, source " +
                     "FROM tracks " +
                     "WHERE LOWER(TRIM(artist)) = LOWER(TRIM(?)) " +
                     "ORDER BY last_used DESC " +
                     "LIMIT ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, artist);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new Track(
                            rs.getString("id"),
                            rs.getString("title"),
                            rs.getString("artist"),
                            rs.getLong("duration"),
                            rs.getString("source")
                    ));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[VibesInDev] Error querying tracks by artist: " + e.getMessage());
        }
        return result;
    }

    /**
     * Surfaces the most popular tracks across the entire server.
     */
    public java.util.List<Track> getTrendingTracks(int limit) {
        java.util.List<Track> result = new java.util.ArrayList<>();
        String sql = "SELECT t.id, t.title, t.artist, t.duration, t.source, COUNT(ph.row_id) as total_plays " +
                     "FROM tracks t " +
                     "LEFT JOIN player_history ph ON t.id = ph.track_id " +
                     "GROUP BY t.id " +
                     "ORDER BY total_plays DESC, t.last_used DESC " +
                     "LIMIT ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new Track(
                            rs.getString("id"),
                            rs.getString("title"),
                            rs.getString("artist"),
                            rs.getLong("duration"),
                            rs.getString("source")
                    ));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[VibesInDev] Error querying trending tracks: " + e.getMessage());
        }
        return result;
    }

    // =======================================================
    // TASTE PROFILE PERSISTENCE
    // =======================================================

    public void saveTasteProfile(
            UUID playerUuid,
            String affinitiesJson,
            String genresJson,
            String dislikesJson,
            String lineageJson,
            long lastSync,
            String genreMassJson,
            String affinityTsJson
    ) {
        if (playerUuid == null) return;
        String sql = "INSERT INTO player_taste_profiles (player_uuid, affinities_json, genres_json, dislikes_json, lineage_json, last_sync, genre_mass_json, affinity_ts_json) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
                     "ON CONFLICT(player_uuid) DO UPDATE SET " +
                     "affinities_json = excluded.affinities_json, " +
                     "genres_json = excluded.genres_json, " +
                     "dislikes_json = excluded.dislikes_json, " +
                     "lineage_json = excluded.lineage_json, " +
                     "last_sync = excluded.last_sync, " +
                     "genre_mass_json = excluded.genre_mass_json, " +
                     "affinity_ts_json = excluded.affinity_ts_json;";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            ps.setString(2, affinitiesJson != null ? affinitiesJson : "{}");
            ps.setString(3, genresJson != null ? genresJson : "{}");
            ps.setString(4, dislikesJson != null ? dislikesJson : "{}");
            ps.setString(5, lineageJson != null ? lineageJson : "{}");
            ps.setLong(6, lastSync);
            ps.setString(7, genreMassJson != null ? genreMassJson : "{}");
            ps.setString(8, affinityTsJson != null ? affinityTsJson : "{}");
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("[VibesInDev] Error saving player taste profile to SQLite: " + e.getMessage());
        }
    }

    public void saveTasteProfile(
            UUID playerUuid,
            String affinitiesJson,
            String genresJson,
            String dislikesJson,
            String lineageJson,
            long lastSync,
            String genreMassJson
    ) {
        saveTasteProfile(playerUuid, affinitiesJson, genresJson, dislikesJson, lineageJson, lastSync, genreMassJson, "{}");
    }

    @Deprecated
    public void saveTasteProfile(
            UUID playerUuid,
            String affinitiesJson,
            String genresJson,
            String dislikesJson,
            String lineageJson,
            long lastSync
    ) {
        saveTasteProfile(playerUuid, affinitiesJson, genresJson, dislikesJson, lineageJson, lastSync, "{}", "{}");
    }

    public TasteProfileSnapshot loadTasteProfile(UUID playerUuid) {
        if (playerUuid == null) return null;
        String sql = "SELECT affinities_json, genres_json, dislikes_json, lineage_json, last_sync, genre_mass_json, affinity_ts_json FROM player_taste_profiles WHERE player_uuid = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String affJson = rs.getString("affinities_json");
                    String genJson = rs.getString("genres_json");
                    String disJson = rs.getString("dislikes_json");
                    String linJson = rs.getString("lineage_json");
                    long lastSync = rs.getLong("last_sync");
                    String massJson = null;
                    try {
                        massJson = rs.getString("genre_mass_json");
                    } catch (SQLException ignored) {}
                    String tsJson = null;
                    try {
                        tsJson = rs.getString("affinity_ts_json");
                    } catch (SQLException ignored) {}

                    Gson gson = new Gson();
                    java.lang.reflect.Type mapDouble = new TypeToken<Map<String, Double>>(){}.getType();
                    java.lang.reflect.Type mapLong = new TypeToken<Map<String, Long>>(){}.getType();
                    java.lang.reflect.Type mapSet = new TypeToken<Map<String, Set<String>>>(){}.getType();
                    java.lang.reflect.Type mapMapDouble = new TypeToken<Map<String, Map<String, Double>>>(){}.getType();

                    Map<String, Double> affinities = affJson != null && !affJson.isBlank() ? gson.fromJson(affJson, mapDouble) : Collections.emptyMap();
                    Map<String, Double> genres = genJson != null && !genJson.isBlank() ? gson.fromJson(genJson, mapDouble) : Collections.emptyMap();
                    Map<String, Long> dislikes = disJson != null && !disJson.isBlank() ? gson.fromJson(disJson, mapLong) : Collections.emptyMap();
                    Map<String, Set<String>> lineage = linJson != null && !linJson.isBlank() ? gson.fromJson(linJson, mapSet) : Collections.emptyMap();
                    Map<String, Long> affinityTimestamps = tsJson != null && !tsJson.isBlank() ? gson.fromJson(tsJson, mapLong) : Collections.emptyMap();

                    Map<String, Double> playMass = null;
                    Map<String, Double> spotifyMass = null;

                    if (massJson != null && !massJson.isBlank() && !massJson.equals("{}")) {
                        try {
                            Map<String, Map<String, Double>> parsedMasses = gson.fromJson(massJson, mapMapDouble);
                            if (parsedMasses != null) {
                                playMass = parsedMasses.get("play");
                                spotifyMass = parsedMasses.get("spotify");
                            }
                        } catch (Exception e) {
                            try {
                                playMass = gson.fromJson(massJson, mapDouble);
                            } catch (Exception ignored) {}
                        }
                    }

                    return new TasteProfileSnapshot(
                            affinities != null ? affinities : Collections.emptyMap(),
                            genres != null ? genres : Collections.emptyMap(),
                            dislikes != null ? dislikes : Collections.emptyMap(),
                            lineage != null ? lineage : Collections.emptyMap(),
                            lastSync,
                            playMass,
                            spotifyMass,
                            affinityTimestamps != null ? affinityTimestamps : Collections.emptyMap()
                    );
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[VibesInDev] Error loading player taste profile from SQLite: " + e.getMessage());
        }
        return null;
    }

    public List<dev.vibesindev.vibesmusic.recommendation.PlayerTasteProfile.FatigueEntry> getRecentFatigueHistory(UUID playerUuid, int limit, long maxAgeMs) {
        List<dev.vibesindev.vibesmusic.recommendation.PlayerTasteProfile.FatigueEntry> list = new ArrayList<>();
        if (playerUuid == null) return list;
        long cutoff = System.currentTimeMillis() - maxAgeMs;
        String sql = "SELECT ph.track_id, t.artist, t.title, ph.played_at " +
                     "FROM player_history ph JOIN tracks t ON ph.track_id = t.id " +
                     "WHERE ph.player_uuid = ? AND ph.played_at > ? " +
                     "ORDER BY ph.played_at ASC LIMIT ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            ps.setLong(2, cutoff);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String trackId = rs.getString("track_id");
                    String artist = rs.getString("artist");
                    String title = rs.getString("title");
                    long playedAt = rs.getLong("played_at");
                    list.add(new dev.vibesindev.vibesmusic.recommendation.PlayerTasteProfile.FatigueEntry(
                            trackId != null ? trackId : "",
                            artist != null ? artist : "unknown",
                            title != null ? title : "",
                            playedAt
                    ));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[VibesInDev] Error loading fatigue history: " + e.getMessage());
        }
        return list;
    }

    // =======================================================
    // ARTIST GENRE CACHE (Phase 2.1)
    // =======================================================

    public record CachedArtistGenres(List<String> genres, String source, long fetchedAt) {}

    public CachedArtistGenres getCachedArtistGenres(String artistNorm) {
        if (artistNorm == null || artistNorm.isBlank()) return null;
        String sql = "SELECT genres_json, source, fetched_at FROM artist_genres WHERE artist_norm = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, artistNorm.toLowerCase(Locale.ROOT).trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String json = rs.getString("genres_json");
                    String src = rs.getString("source");
                    long ts = rs.getLong("fetched_at");
                    Gson gson = new Gson();
                    java.lang.reflect.Type type = new TypeToken<List<String>>(){}.getType();
                    List<String> list = (json != null && !json.isBlank()) ? gson.fromJson(json, type) : Collections.emptyList();
                    return new CachedArtistGenres(list != null ? list : Collections.emptyList(), src, ts);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[VibesInDev] Error reading artist_genres: " + e.getMessage());
        }
        return null;
    }

    public void saveArtistGenres(String artistNorm, List<String> genres, String source) {
        if (artistNorm == null || artistNorm.isBlank()) return;
        String sql = "INSERT INTO artist_genres (artist_norm, genres_json, source, fetched_at) VALUES (?, ?, ?, ?) " +
                     "ON CONFLICT(artist_norm) DO UPDATE SET genres_json = excluded.genres_json, source = excluded.source, fetched_at = excluded.fetched_at";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, artistNorm.toLowerCase(Locale.ROOT).trim());
            ps.setString(2, new Gson().toJson(genres != null ? genres : Collections.emptyList()));
            ps.setString(3, source != null ? source : "UNKNOWN");
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("[VibesInDev] Error saving artist_genres: " + e.getMessage());
        }
    }

    // =======================================================
    // ARTIST RELATIONS / DATA-DRIVEN LINEAGE (Phase 4.1)
    // =======================================================

    public record ArtistRelation(
            String aNorm,
            String bNorm,
            String kind,
            double weight,
            String source,
            long fetchedAt
    ) {}

    public void saveArtistRelation(String aNorm, String bNorm, String kind, double weight, String source) {
        if (aNorm == null || bNorm == null || aNorm.isBlank() || bNorm.isBlank()) return;
        String sql = "INSERT INTO artist_relations (a_norm, b_norm, kind, weight, source, fetched_at) VALUES (?, ?, ?, ?, ?, ?) " +
                     "ON CONFLICT(a_norm, b_norm) DO UPDATE SET kind = excluded.kind, weight = excluded.weight, source = excluded.source, fetched_at = excluded.fetched_at";
        long now = System.currentTimeMillis();
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, aNorm.toLowerCase(Locale.ROOT).trim());
            ps.setString(2, bNorm.toLowerCase(Locale.ROOT).trim());
            ps.setString(3, kind != null ? kind : "similar");
            ps.setDouble(4, Math.max(0.0, Math.min(1.0, weight)));
            ps.setString(5, source != null ? source : "UNKNOWN");
            ps.setLong(6, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("[VibesInDev] Error saving artist_relation: " + e.getMessage());
        }
    }

    public void saveArtistRelations(List<ArtistRelation> relations) {
        if (relations == null || relations.isEmpty()) return;
        String sql = "INSERT INTO artist_relations (a_norm, b_norm, kind, weight, source, fetched_at) VALUES (?, ?, ?, ?, ?, ?) " +
                     "ON CONFLICT(a_norm, b_norm) DO UPDATE SET kind = excluded.kind, weight = excluded.weight, source = excluded.source, fetched_at = excluded.fetched_at";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            for (ArtistRelation rel : relations) {
                if (rel == null || rel.aNorm() == null || rel.bNorm() == null) continue;
                ps.setString(1, rel.aNorm().toLowerCase(Locale.ROOT).trim());
                ps.setString(2, rel.bNorm().toLowerCase(Locale.ROOT).trim());
                ps.setString(3, rel.kind() != null ? rel.kind() : "similar");
                ps.setDouble(4, Math.max(0.0, Math.min(1.0, rel.weight())));
                ps.setString(5, rel.source() != null ? rel.source() : "UNKNOWN");
                ps.setLong(6, rel.fetchedAt() > 0 ? rel.fetchedAt() : System.currentTimeMillis());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            plugin.getLogger().warning("[VibesInDev] Error batch saving artist_relations: " + e.getMessage());
        }
    }

    public Map<String, Double> getArtistRelations(String artistNorm) {
        if (artistNorm == null || artistNorm.isBlank()) return Collections.emptyMap();
        String sql = "SELECT b_norm, weight FROM artist_relations WHERE a_norm = ?";
        Map<String, Double> map = new HashMap<>();
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, artistNorm.toLowerCase(Locale.ROOT).trim());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    map.put(rs.getString("b_norm"), rs.getDouble("weight"));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[VibesInDev] Error reading artist_relations: " + e.getMessage());
        }
        return map;
    }

    public List<ArtistRelation> getAllArtistRelations() {
        String sql = "SELECT a_norm, b_norm, kind, weight, source, fetched_at FROM artist_relations";
        List<ArtistRelation> list = new ArrayList<>();
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(new ArtistRelation(
                        rs.getString("a_norm"),
                        rs.getString("b_norm"),
                        rs.getString("kind"),
                        rs.getDouble("weight"),
                        rs.getString("source"),
                        rs.getLong("fetched_at")
                ));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[VibesInDev] Error reading all artist_relations: " + e.getMessage());
        }
        return list;
    }

    // =======================================================
    // RECOMMENDATION TELEMETRY (Phase 0 / Phase 5.1)
    // =======================================================

    public record CandidateTelemetry(
            String trackId,
            int rank,
            String source,
            double score,
            double heuristicScore,
            Double learnedScore,
            double selectionProbability,
            boolean selected,
            String featuresJson
    ) {}

    public void logRecImpression(
            String impressionId,
            long ts,
            UUID playerUuid,
            UUID partyUuid,
            String seedTrackId,
            String engineVersion,
            String modelVersion,
            String requestedMode,
            String effectiveMode,
            double modeProbability,
            String modeFallbacksJson,
            int relaxationLevel,
            int rawCandidates,
            int eligibleCandidates,
            String rejectionCountsJson,
            String providerStatusJson,
            long latencyMs,
            String selectedTrackId,
            List<CandidateTelemetry> candidates
    ) {
        CompletableFuture.runAsync(() -> {
            String insertImpression = "INSERT INTO rec_impressions (" +
                    "impression_id, ts, player_uuid, party_uuid, seed_track_id, engine_version, model_version, " +
                    "requested_mode, effective_mode, mode_probability, mode_fallbacks_json, relaxation_level, " +
                    "raw_candidates, eligible_candidates, rejection_counts_json, provider_status_json, latency_ms, " +
                    "selected_track_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            String insertCandidate = "INSERT INTO rec_candidates (" +
                    "impression_id, track_id, rank, source, score, heuristic_score, learned_score, " +
                    "selection_probability, selected, features_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

            try (Connection conn = getConnection()) {
                conn.setAutoCommit(false);
                try (PreparedStatement ps = conn.prepareStatement(insertImpression)) {
                    ps.setString(1, impressionId);
                    ps.setLong(2, ts);
                    ps.setString(3, playerUuid != null ? playerUuid.toString() : "");
                    ps.setString(4, partyUuid != null ? partyUuid.toString() : null);
                    ps.setString(5, seedTrackId);
                    ps.setString(6, engineVersion != null ? engineVersion : "v1");
                    ps.setString(7, modelVersion);
                    ps.setString(8, requestedMode);
                    ps.setString(9, effectiveMode);
                    ps.setDouble(10, modeProbability);
                    ps.setString(11, modeFallbacksJson);
                    ps.setInt(12, relaxationLevel);
                    ps.setInt(13, rawCandidates);
                    ps.setInt(14, eligibleCandidates);
                    ps.setString(15, rejectionCountsJson);
                    ps.setString(16, providerStatusJson);
                    ps.setLong(17, latencyMs);
                    ps.setString(18, selectedTrackId);
                    ps.executeUpdate();
                }

                if (candidates != null && !candidates.isEmpty()) {
                    try (PreparedStatement ps = conn.prepareStatement(insertCandidate)) {
                        for (CandidateTelemetry c : candidates) {
                            ps.setString(1, impressionId);
                            ps.setString(2, c.trackId());
                            ps.setInt(3, c.rank());
                            ps.setString(4, c.source());
                            ps.setDouble(5, c.score());
                            ps.setDouble(6, c.heuristicScore());
                            if (c.learnedScore() != null) {
                                ps.setDouble(7, c.learnedScore());
                            } else {
                                ps.setNull(7, java.sql.Types.REAL);
                            }
                            ps.setDouble(8, c.selectionProbability());
                            ps.setInt(9, c.selected() ? 1 : 0);
                            ps.setString(10, c.featuresJson());
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                }
                conn.commit();
            } catch (SQLException e) {
                if (plugin != null && plugin.getLogger() != null) {
                    plugin.getLogger().warning("[VibesInDev] Error logging recommendation telemetry: " + e.getMessage());
                }
            }
        });
    }

    public void updateRecImpressionOutcome(String impressionId, double completion, String outcome) {
        if (impressionId == null || impressionId.isBlank()) return;
        CompletableFuture.runAsync(() -> {
            String sql = "UPDATE rec_impressions SET completion = ?, outcome = ? WHERE impression_id = ?";
            try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setDouble(1, completion);
                ps.setString(2, outcome);
                ps.setString(3, impressionId);
                ps.executeUpdate();
            } catch (SQLException e) {
                if (plugin != null && plugin.getLogger() != null) {
                    plugin.getLogger().warning("[VibesInDev] Error updating recommendation outcome: " + e.getMessage());
                }
            }
        });
    }

    public void pruneRecTelemetry(int retentionDays) {
        if (retentionDays <= 0) return;
        CompletableFuture.runAsync(() -> {
            long cutoff = System.currentTimeMillis() - (retentionDays * 86_400_000L);
            String deleteImpr = "DELETE FROM rec_impressions WHERE ts < ?";
            String deleteCand = "DELETE FROM rec_candidates WHERE impression_id NOT IN (SELECT impression_id FROM rec_impressions)";
            try (Connection conn = getConnection()) {
                try (PreparedStatement ps = conn.prepareStatement(deleteImpr)) {
                    ps.setLong(1, cutoff);
                    ps.executeUpdate();
                }
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(deleteCand);
                }
            } catch (SQLException e) {
                if (plugin != null && plugin.getLogger() != null) {
                    plugin.getLogger().warning("[VibesInDev] Error pruning recommendation telemetry: " + e.getMessage());
                }
            }
        });
    }

    public void savePlayerSpotifyTracks(UUID playerUuid, String source, List<Track> tracks) {
        if (playerUuid == null || source == null || tracks == null || tracks.isEmpty()) return;
        long now = System.currentTimeMillis();
        String sql = "INSERT OR REPLACE INTO player_spotify_tracks (player_uuid, track_id, source, title, artist, duration_s, added_at, synced_at) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Track t : tracks) {
                if (t == null || t.id() == null || t.id().isBlank()) continue;
                ps.setString(1, playerUuid.toString());
                ps.setString(2, t.id());
                ps.setString(3, source);
                ps.setString(4, t.title() != null ? t.title() : "");
                ps.setString(5, t.artist() != null ? t.artist() : "");
                ps.setLong(6, t.durationSeconds());
                ps.setLong(7, now);
                ps.setLong(8, now);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            if (plugin != null && plugin.getLogger() != null) {
                plugin.getLogger().warning("[VibesInDev] Failed to save player Spotify tracks: " + e.getMessage());
            }
        }
    }

    public List<Track> getPlayerSpotifyTracks(UUID playerUuid, int limit) {
        List<Track> list = new ArrayList<>();
        if (playerUuid == null) return list;
        String sql = "SELECT track_id, title, artist, duration_s, source FROM player_spotify_tracks " +
                     "WHERE player_uuid = ? ORDER BY synced_at DESC LIMIT ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String trackId = rs.getString("track_id");
                    String title = rs.getString("title");
                    String artist = rs.getString("artist");
                    long dur = rs.getLong("duration_s");
                    String lazyUrl = "spotify-lazy:" + artist + " - " + title + "::" + dur;
                    list.add(new Track(trackId, title, artist, dur, lazyUrl));
                }
            }
        } catch (SQLException e) {
            if (plugin != null && plugin.getLogger() != null) {
                plugin.getLogger().warning("[VibesInDev] Failed to get player Spotify tracks: " + e.getMessage());
            }
        }
        return list;
    }

    public List<Track> getLikedTracksForPlayer(UUID playerUuid, int limit) {
        List<Track> list = new ArrayList<>();
        if (playerUuid == null) return list;
        String sql = "SELECT t.id, t.title, t.artist, t.duration, t.source " +
                     "FROM player_history ph JOIN tracks t ON ph.track_id = t.id " +
                     "WHERE ph.player_uuid = ? AND ph.liked = 1 " +
                     "GROUP BY t.id " +
                     "ORDER BY MAX(ph.played_at) DESC LIMIT ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new Track(
                            rs.getString("id"),
                            rs.getString("title"),
                            rs.getString("artist"),
                            rs.getLong("duration"),
                            rs.getString("source")
                    ));
                }
            }
        } catch (SQLException e) {
            if (plugin != null && plugin.getLogger() != null) {
                plugin.getLogger().warning("[VibesInDev] Failed to get liked tracks for player: " + e.getMessage());
            }
        }
        return list;
    }

    public List<Track> getTopPlayedTracksForPlayer(UUID playerUuid, int limit, long maxAgeMs) {
        List<Track> list = new ArrayList<>();
        if (playerUuid == null) return list;
        long cutoff = System.currentTimeMillis() - maxAgeMs;
        String sql = "SELECT t.id, t.title, t.artist, t.duration, t.source, COUNT(*) as play_count " +
                     "FROM player_history ph JOIN tracks t ON ph.track_id = t.id " +
                     "WHERE ph.player_uuid = ? AND ph.played_at > ? " +
                     "GROUP BY t.id " +
                     "ORDER BY play_count DESC, MAX(ph.played_at) DESC LIMIT ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            ps.setLong(2, cutoff);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new Track(
                            rs.getString("id"),
                            rs.getString("title"),
                            rs.getString("artist"),
                            rs.getLong("duration"),
                            rs.getString("source")
                    ));
                }
            }
        } catch (SQLException e) {
            if (plugin != null && plugin.getLogger() != null) {
                plugin.getLogger().warning("[VibesInDev] Failed to get top played tracks for player: " + e.getMessage());
            }
        }
        return list;
    }
}

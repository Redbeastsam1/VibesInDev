package dev.vibesindev.vibesmusic.recommendation;

import dev.vibesindev.vibesmusic.VibesInDevPlugin;
import dev.vibesindev.vibesmusic.model.Track;
import dev.vibesindev.vibesmusic.party.MusicParty;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central coordinator for the autoplay recommendation system and taste profile management.
 */
public final class RecommendationManager implements org.bukkit.event.Listener {

    private final VibesInDevPlugin plugin;
    private final GenreResolver genreResolver;
    private final ArtistLineageEnricher enricher;
    private RecommendationEngine engine;

    /** Per-player taste profile models. */
    private final Map<UUID, PlayerTasteProfile> tasteProfiles = new ConcurrentHashMap<>();

    /** Per-player autoplay toggle. Absent = use config/network default. */
    private final ConcurrentHashMap<UUID, Boolean> autoplayStates = new ConcurrentHashMap<>();

    /** Per-party autoplay toggle (keyed by party UUID). */
    private final ConcurrentHashMap<UUID, Boolean> partyAutoplayStates = new ConcurrentHashMap<>();

    public RecommendationManager(VibesInDevPlugin plugin) {
        this(plugin, null);
    }

    public RecommendationManager(VibesInDevPlugin plugin, RecommendationEngine engine) {
        this.plugin = plugin;
        this.genreResolver = new GenreResolver(plugin);
        this.enricher = new ArtistLineageEnricher(plugin);
        this.engine = (engine != null) ? engine : new RecommendationEngine(plugin, this.genreResolver);
        loadConfiguredLineages();
        initTelemetryPruning();
    }

    private void initTelemetryPruning() {
        if (plugin != null && plugin.cache() != null) {
            int retentionDays = (plugin.getConfig() != null)
                    ? plugin.getConfig().getInt("recommendations.telemetry.retention-days", 30) : 30;
            CompletableFuture.runAsync(() -> plugin.cache().pruneRecTelemetry(retentionDays));
            if (org.bukkit.Bukkit.getServer() != null) {
                try {
                    org.bukkit.Bukkit.getScheduler().runTaskTimerAsynchronously(
                            plugin,
                            () -> plugin.cache().pruneRecTelemetry(retentionDays),
                            20L * 3600 * 24,
                            20L * 3600 * 24
                    );
                } catch (Exception ignored) {
                    // Ignored in unit test environments without bukkit scheduler
                }
            }
        }
    }

    public void setEngine(RecommendationEngine engine) {
        this.engine = engine;
    }

    public GenreResolver genreResolver() {
        return genreResolver;
    }

    public ArtistLineageEnricher enricher() {
        return enricher;
    }

    public RecommendationEngine engine() {
        return engine;
    }

    // =========================================================
    // TASTE PROFILE MANAGEMENT
    // =========================================================

    /**
     * Gets or creates the taste profile for a player, bootstrapping from SQLite history if needed.
     */
    /**
     * Gets or creates the taste profile in-memory without blocking Bukkit's main thread on SQLite.
     */
    public PlayerTasteProfile getOrCreateProfile(UUID playerUuid) {
        if (playerUuid == null) return null;
        PlayerTasteProfile existing = tasteProfiles.get(playerUuid);
        if (existing != null) {
            return existing;
        }

        PlayerTasteProfile fresh = new PlayerTasteProfile(playerUuid);
        double halfLife = (plugin != null && plugin.getConfig() != null)
                ? plugin.getConfig().getDouble("recommendations.affinity-half-life-days", 30.0)
                : 30.0;
        fresh.setHalfLifeDays(halfLife);
        PlayerTasteProfile previous = tasteProfiles.putIfAbsent(playerUuid, fresh);
        if (previous != null) {
            return previous;
        }

        // Trigger async hydration if not already preloaded
        if (plugin != null && plugin.cache() != null) {
            CompletableFuture.runAsync(() -> hydrateProfileFromDiskSync(playerUuid, fresh));
        }
        return fresh;
    }

    public CompletableFuture<PlayerTasteProfile> loadProfileAsync(UUID playerUuid) {
        if (playerUuid == null) return CompletableFuture.completedFuture(null);
        PlayerTasteProfile cached = tasteProfiles.get(playerUuid);
        if (cached != null) return CompletableFuture.completedFuture(cached);

        if (plugin == null || plugin.cache() == null) {
            PlayerTasteProfile fresh = new PlayerTasteProfile(playerUuid);
            tasteProfiles.put(playerUuid, fresh);
            return CompletableFuture.completedFuture(fresh);
        }

        return CompletableFuture.supplyAsync(() -> loadProfileFromDiskSync(playerUuid));
    }

    public PlayerTasteProfile loadProfileFromDiskSync(UUID playerUuid) {
        if (playerUuid == null) return null;
        PlayerTasteProfile profile = new PlayerTasteProfile(playerUuid);
        hydrateProfileFromDiskSync(playerUuid, profile);
        tasteProfiles.put(playerUuid, profile);
        return profile;
    }

    private void hydrateProfileFromDiskSync(UUID playerUuid, PlayerTasteProfile profile) {
        if (plugin == null || plugin.cache() == null || profile == null) return;
        double halfLife = (plugin != null && plugin.getConfig() != null)
                ? plugin.getConfig().getDouble("recommendations.affinity-half-life-days", 30.0)
                : 30.0;
        profile.setHalfLifeDays(halfLife);

        // 1. Restore persistent profile snapshot from SQLite
        dev.vibesindev.vibesmusic.cache.TrackCache.TasteProfileSnapshot snapshot = plugin.cache().loadTasteProfile(playerUuid);
        if (snapshot != null) {
            profile.restoreFromSnapshot(
                    snapshot.affinities(),
                    snapshot.genres(),
                    snapshot.playGenreMass(),
                    snapshot.spotifyGenreMass(),
                    snapshot.dislikes(),
                    snapshot.lineage(),
                    snapshot.lastSync(),
                    snapshot.affinityTimestamps(),
                    snapshot.exploreRatio()
            );
        }

        // 2. Restore recent fatigue cooldowns from SQLite (within last 45 mins, up to 50 tracks)
        List<PlayerTasteProfile.FatigueEntry> recentFatigue = plugin.cache().getRecentFatigueHistory(playerUuid, 50, 45 * 60_000L);
        if (recentFatigue != null && !recentFatigue.isEmpty()) {
            profile.restoreFatigue(recentFatigue);
        }

        // 3. Fallback: if artist affinities are still empty, bootstrap from SQLite play history
        if (profile.getArtistAffinities().isEmpty()) {
            Map<String, Integer> top = plugin.cache().getTopArtistsForPlayer(playerUuid, 50);
            if (!top.isEmpty()) {
                List<String> artists = new ArrayList<>(top.keySet());
                profile.ingestSpotifyArtists(artists, null);
            }
        }
    }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onPlayerPreLogin(org.bukkit.event.player.AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() == org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            loadProfileAsync(event.getUniqueId());
        }
    }

    /**
     * Records a track played by a player to update their taste profile and fatigue history.
     */
    public enum Outcome {
        COMPLETED,
        SKIPPED_EARLY,
        SKIPPED_LATE,
        REPEATED,
        REQUEUED,
        AFK_OR_UNKNOWN,
        DISCONNECTED,
        MANUAL_STOP
    }

    private final Map<UUID, String> activeImpressionIds = new ConcurrentHashMap<>();

    public void setActiveImpression(UUID playerUuid, String impressionId) {
        if (playerUuid != null && impressionId != null) {
            activeImpressionIds.put(playerUuid, impressionId);
        }
    }

    public String getActiveImpression(UUID playerUuid) {
        return playerUuid != null ? activeImpressionIds.get(playerUuid) : null;
    }

    public void recordStart(UUID playerUuid, Track track) {
        if (playerUuid == null || track == null) return;
        getOrCreateProfile(playerUuid).recordStart(track);
    }

    public void recordOutcome(UUID playerUuid, Track track, long playedMs, long durationMs, Outcome outcome) {
        if (playerUuid == null || track == null) return;
        PlayerTasteProfile profile = getOrCreateProfile(playerUuid);
        profile.recordOutcome(track, playedMs, durationMs, outcome,
                plugin != null ? plugin.getConfig() : null);

        // Task 2.1: Ingest track genres on completion
        double ratio = (durationMs > 0) ? (double) playedMs / durationMs : 0.0;
        if (outcome == Outcome.COMPLETED || ratio >= 0.80) {
            double gain = (plugin != null && plugin.getConfig() != null)
                    ? plugin.getConfig().getDouble("recommendations.signals.completion-gain", 0.08) : 0.08;
            List<String> cachedGenres = Collections.emptyList();
            if (genreResolver != null && track.artist() != null) {
                List<String> genres = genreResolver.getCachedGenres(track.artist());
                if (genres != null && !genres.isEmpty()) {
                    cachedGenres = genres;
                    profile.ingestArtistGenres(genres, gain);
                }
            }

            // Task 3.2: Ingest into session context
            SessionContext sc = getOrCreateSessionContext(playerUuid);
            sc.recordCompletedTrack(track, cachedGenres, 0, null);

            MusicParty party = (plugin != null && plugin.parties() != null) ? plugin.parties().getPartyByPlayer(playerUuid) : null;
            if (party != null && party.getMemberCount() > 1) {
                getOrCreateSessionContext(party.getPartyId()).recordCompletedTrack(track, cachedGenres, 0, null);
            }
        }

        // Task 5.3: Adaptive exploration tuning
        RankedPick lastPick = lastAutoplayPicks.get(playerUuid);
        if (lastPick != null && track.id() != null && track.id().equals(lastPick.track().id())) {
            boolean adaptive = (plugin != null && plugin.getConfig() != null)
                    && plugin.getConfig().getBoolean("recommendations.exploration.adaptive", false);
            if (adaptive && lastPick.mode() == RecommendationMode.EXPLORE) {
                profile.recordExploreOutcome(outcome, plugin != null ? plugin.getConfig() : null);
            }
        }

        saveProfile(playerUuid);

        String imprId = activeImpressionIds.get(playerUuid);
        if (imprId != null && plugin != null && plugin.cache() != null) {
            double outcomeRatio = (durationMs > 0) ? Math.min(1.0, (double) playedMs / durationMs) : (outcome == Outcome.COMPLETED ? 1.0 : 0.0);
            plugin.cache().updateRecImpressionOutcome(imprId, outcomeRatio, outcome != null ? outcome.name() : "UNKNOWN");
        }
    }

    @Deprecated
    public void recordPlay(UUID playerUuid, Track track) {
        recordStart(playerUuid, track);
    }

    public void recordPlaylistAdd(UUID playerUuid, Track track) {
        if (playerUuid == null || track == null) return;
        getOrCreateProfile(playerUuid).recordPlaylistAdd(track);
        saveProfile(playerUuid);
    }

    public void recordLike(UUID playerUuid, Track track) {
        if (playerUuid == null || track == null) return;
        getOrCreateProfile(playerUuid).recordLike(track);
        saveProfile(playerUuid);
    }

    public void recordDislike(UUID playerUuid, Track track) {
        if (playerUuid == null || track == null) return;
        getOrCreateProfile(playerUuid).recordDislike(track);
        saveProfile(playerUuid);
    }

    @Deprecated
    public void recordSkip(UUID playerUuid, Track track) {
        recordOutcome(playerUuid, track, 0, 0, Outcome.SKIPPED_EARLY);
    }

    // =========================================================
    // AUTOPLAY TOGGLES
    // =========================================================

    public boolean isAutoplayEnabled(UUID playerUuid) {
        Boolean override = autoplayStates.get(playerUuid);
        if (override != null) return override;
        return (plugin.network() != null) ? plugin.network().getAutoplay(playerUuid) : true;
    }

    public void setAutoplay(UUID playerUuid, boolean enabled) {
        autoplayStates.put(playerUuid, enabled);
        if (plugin.network() != null) {
            plugin.network().setAutoplay(playerUuid, enabled);
        }
    }

    public void loadAutoplayState(UUID playerUuid, boolean enabled) {
        autoplayStates.put(playerUuid, enabled);
    }

    public boolean isPartyAutoplayEnabled(UUID partyUuid) {
        if (partyUuid == null) return true;
        MusicParty party = (plugin.parties() != null) ? plugin.parties().getParty(partyUuid) : null;
        if (party != null) {
            return party.isAutoplay();
        }
        return partyAutoplayStates.getOrDefault(partyUuid, true);
    }

    public void setPartyAutoplay(UUID partyUuid, boolean enabled) {
        if (partyUuid == null) return;
        partyAutoplayStates.put(partyUuid, enabled);
        MusicParty party = (plugin.parties() != null) ? plugin.parties().getParty(partyUuid) : null;
        if (party != null) {
            party.setAutoplay(enabled);
        }
    }

    // =========================================================
    // CORE RECOMMENDATION
    // =========================================================

    /**
     * Predicts the next ranked list of candidate tracks (up to 3) using multi-objective scoring,
     * diversity-maximizing greedy MMR, and temperature-weighted softmax selection with relaxation ladder.
     */
    public CompletableFuture<List<RankedPick>> getOrPredictNextRanked(UUID playerUuid, Track seed) {
        if (playerUuid == null) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        // Check if player is in a party: if so, compute Spotify Blend centroid
        MusicParty party = (plugin.parties() != null) ? plugin.parties().getPartyByPlayer(playerUuid) : null;
        PlayerTasteProfile profile;

        if (party != null && party.getMemberCount() > 1) {
            List<PlayerTasteProfile> memberProfiles = new ArrayList<>();
            List<UUID> members = new ArrayList<>(party.getMembers());
            for (UUID memberId : members) {
                memberProfiles.add(getOrCreateProfile(memberId));
            }

            boolean rotateLeader = plugin != null && plugin.getConfig() != null
                    && plugin.getConfig().getBoolean("recommendations.party.rotate-leader", false);
            int interval = plugin != null && plugin.getConfig() != null
                    ? plugin.getConfig().getInt("recommendations.party.rotate-leader-interval", 3) : 3;
            if (interval <= 0) interval = 3;

            java.util.concurrent.atomic.AtomicInteger counter = partyPickCounters.computeIfAbsent(party.getPartyId(), k -> new java.util.concurrent.atomic.AtomicInteger(0));
            int pickNum = counter.getAndIncrement();

            if (rotateLeader && ((pickNum + 1) % interval == 0) && !memberProfiles.isEmpty()) {
                int leaderIndex = (pickNum / interval) % memberProfiles.size();
                profile = memberProfiles.get(leaderIndex);
            } else {
                double lambda = plugin != null && plugin.getConfig() != null
                        ? plugin.getConfig().getDouble("recommendations.party.misery-lambda", 0.5) : 0.5;
                profile = PlayerTasteProfile.blend(party.getPartyId(), memberProfiles);
                profile.setMiseryLambda(lambda);
            }
        } else {
            profile = getOrCreateProfile(playerUuid);
            checkAndSendColdStartHint(playerUuid, profile);
        }

        UUID targetUuid = (party != null && party.getMemberCount() > 1) ? party.getPartyId() : playerUuid;
        SessionContext sessionContext = getOrCreateSessionContext(targetUuid);
        CompletableFuture<List<RankedPick>> future = (engine != null)
                ? engine.recommendNextRanked(seed, profile, sessionContext)
                : CompletableFuture.completedFuture(Collections.emptyList());
        if (future == null) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        return future.thenApply(picks -> {
                    if (picks != null && !picks.isEmpty()) {
                        RankedPick top = picks.get(0);
                        setLastAutoplayPick(targetUuid, top);
                        if (top.impressionId() != null) {
                            setActiveImpression(targetUuid, top.impressionId());
                        }
                        if (party != null && party.getMemberCount() > 1) {
                            for (UUID m : party.getMembers()) {
                                setLastAutoplayPick(m, top);
                                if (top.impressionId() != null) {
                                    setActiveImpression(m, top.impressionId());
                                }
                            }
                        }
                    }
                    return picks;
                });
    }

    // =========================================================
    // COLD START ACTION BAR HINT (Task 4.4)
    // =========================================================

    private final Set<UUID> sessionSpotifyHinted = ConcurrentHashMap.newKeySet();

    public boolean hasSentColdStartHint(UUID playerUuid) {
        return sessionSpotifyHinted.contains(playerUuid);
    }

    public void checkAndSendColdStartHint(UUID playerUuid, PlayerTasteProfile profile) {
        if (playerUuid == null || profile == null) return;
        if (!sessionSpotifyHinted.contains(playerUuid) && profile.isColdStart()) {
            boolean hasSpotify = plugin != null && plugin.spotify() != null && plugin.spotify().hasUserAuthorization(playerUuid);
            if (!hasSpotify) {
                sessionSpotifyHinted.add(playerUuid);
                if (org.bukkit.Bukkit.getServer() != null) {
                    Player player = org.bukkit.Bukkit.getPlayer(playerUuid);
                    if (player != null && player.isOnline()) {
                        String tip = ChatColor.GOLD + "Tip: Connect your Spotify with "
                                + ChatColor.YELLOW + "/music spotify login "
                                + ChatColor.GOLD + "for personalized autoplay!";
                        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacy(tip));
                    }
                }
            }
        }
    }

    // =========================================================
    // SESSION CONTEXT
    // =========================================================

    private final Map<UUID, SessionContext> sessionContexts = new ConcurrentHashMap<>();
    private final Map<UUID, java.util.concurrent.atomic.AtomicInteger> partyPickCounters = new ConcurrentHashMap<>();

    public SessionContext getOrCreateSessionContext(UUID targetUuid) {
        if (targetUuid == null) return new SessionContext();
        return sessionContexts.computeIfAbsent(targetUuid, k -> new SessionContext());
    }

    // =========================================================
    // EXPLAINABILITY & LAST PICKS
    // =========================================================

    private final Map<UUID, RankedPick> lastAutoplayPicks = new ConcurrentHashMap<>();

    public void setLastAutoplayPick(UUID targetUuid, RankedPick pick) {
        if (targetUuid == null || pick == null) return;
        lastAutoplayPicks.put(targetUuid, pick);
    }

    public RankedPick getLastAutoplayPick(UUID targetUuid) {
        if (targetUuid == null) return null;
        return lastAutoplayPicks.get(targetUuid);
    }

    /**
     * Predicts the single highest-scoring next track to play.
     *
     * @deprecated Use {@link #getOrPredictNextRanked(UUID, Track)} for ranked fallback support.
     */
    @Deprecated
    public CompletableFuture<Track> getOrPredictNext(UUID playerUuid, Track seed) {
        return getOrPredictNextRanked(playerUuid, seed)
                .thenApply(picks -> (picks != null && !picks.isEmpty()) ? picks.get(0).track() : null);
    }

    // =========================================================
    // NOTIFICATIONS
    // =========================================================

    public void sendAutoplayNotification(Player player, Track recommended) {
        if (player == null || !player.isOnline() || recommended == null) return;

        String msg = ChatColor.LIGHT_PURPLE + "♫ Autoplay: "
                + ChatColor.WHITE + recommended.title()
                + ChatColor.GRAY + " by "
                + ChatColor.WHITE + recommended.artist()
                + ChatColor.DARK_GRAY + " (Recommended)";

        player.spigot().sendMessage(
                ChatMessageType.ACTION_BAR,
                TextComponent.fromLegacy(msg)
        );
    }

    // =========================================================
    // LIFECYCLE
    // =========================================================

    public void saveProfile(UUID playerUuid) {
        if (playerUuid == null || plugin == null || plugin.cache() == null) return;
        PlayerTasteProfile profile = tasteProfiles.get(playerUuid);
        if (profile == null) return;
        CompletableFuture.runAsync(() -> saveProfileSync(playerUuid));
    }

    public void saveProfileSync(UUID playerUuid) {
        if (playerUuid == null || plugin == null || plugin.cache() == null) return;
        PlayerTasteProfile profile = tasteProfiles.get(playerUuid);
        if (profile == null) return;
        plugin.cache().saveTasteProfile(
                playerUuid,
                profile.exportAffinitiesJson(),
                profile.exportGenresJson(),
                profile.exportDislikesJson(),
                profile.exportLineageJson(),
                profile.getLastSyncTimestamp(),
                profile.exportGenreMassJson(),
                profile.exportAffinityTimestampsJson(),
                profile.getExploreRatio()
        );
    }

    public void saveAll() {
        for (UUID uuid : tasteProfiles.keySet()) {
            saveProfileSync(uuid);
        }
    }

    public void shutdown() {
        saveAll();
        if (engine != null) {
            engine.shutdown();
        }
    }

    public void onPlayerQuit(UUID playerUuid) {
        if (playerUuid == null) return;
        saveProfileSync(playerUuid);
        sessionContexts.remove(playerUuid);
        sessionSpotifyHinted.remove(playerUuid);
        activeImpressionIds.remove(playerUuid);
        lastAutoplayPicks.remove(playerUuid);
    }

    public void onPartyDisband(UUID partyUuid) {
        partyAutoplayStates.remove(partyUuid);
        sessionContexts.remove(partyUuid);
        partyPickCounters.remove(partyUuid);
        activeImpressionIds.remove(partyUuid);
        lastAutoplayPicks.remove(partyUuid);
    }

    private void loadConfiguredLineages() {
        org.bukkit.configuration.ConfigurationSection sec = plugin != null ? plugin.getConfig().getConfigurationSection("recommendations.lineage") : null;
        if (sec != null) {
            for (String hub : sec.getKeys(false)) {
                List<String> members = sec.getStringList(hub);
                for (String m : members) {
                    PlayerTasteProfile.registerGlobalLineage(hub, m);
                }
            }
        }
        if (plugin != null && plugin.cache() != null) {
            List<dev.vibesindev.vibesmusic.cache.TrackCache.ArtistRelation> dbRelations = plugin.cache().getAllArtistRelations();
            if (dbRelations != null && !dbRelations.isEmpty()) {
                PlayerTasteProfile.loadGlobalLineage(dbRelations);
            }
        }
    }

}

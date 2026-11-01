package dev.vibesindev.vibesmusic.recommendation;

import dev.vibesindev.vibesmusic.VibesInDevPlugin;
import dev.vibesindev.vibesmusic.model.Track;
import dev.vibesindev.vibesmusic.party.MusicParty;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central coordinator for the autoplay recommendation system and taste profile management.
 */
public final class RecommendationManager {

    private final VibesInDevPlugin plugin;
    private final RecommendationEngine engine;

    /** Per-player taste profile models. */
    private final Map<UUID, PlayerTasteProfile> tasteProfiles = new ConcurrentHashMap<>();

    /** Per-player autoplay toggle. Absent = use config/network default. */
    private final ConcurrentHashMap<UUID, Boolean> autoplayStates = new ConcurrentHashMap<>();

    /** Per-party autoplay toggle (keyed by party UUID). */
    private final ConcurrentHashMap<UUID, Boolean> partyAutoplayStates = new ConcurrentHashMap<>();

    public RecommendationManager(VibesInDevPlugin plugin) {
        this.plugin = plugin;
        this.engine = new RecommendationEngine(plugin);
        loadConfiguredLineages();
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
    public PlayerTasteProfile getOrCreateProfile(UUID playerUuid) {
        return tasteProfiles.computeIfAbsent(playerUuid, uuid -> {
            PlayerTasteProfile profile = new PlayerTasteProfile(uuid);
            if (plugin.cache() != null) {
                // 1. Restore persistent profile snapshot from SQLite
                dev.vibesindev.vibesmusic.cache.TrackCache.TasteProfileSnapshot snapshot = plugin.cache().loadTasteProfile(uuid);
                if (snapshot != null) {
                    profile.restoreFromSnapshot(
                            snapshot.affinities(),
                            snapshot.genres(),
                            snapshot.dislikes(),
                            snapshot.lineage(),
                            snapshot.lastSync()
                    );
                }

                // 2. Restore recent fatigue cooldowns from SQLite (within last 45 mins)
                List<PlayerTasteProfile.FatigueEntry> recentFatigue = plugin.cache().getRecentFatigueHistory(uuid, 20, 45 * 60_000L);
                if (recentFatigue != null && !recentFatigue.isEmpty()) {
                    profile.restoreFatigue(recentFatigue);
                }

                // 3. Fallback: if artist affinities are still empty, bootstrap from SQLite play history
                if (profile.getArtistAffinities().isEmpty()) {
                    Map<String, Integer> top = plugin.cache().getTopArtistsForPlayer(uuid, 50);
                    if (!top.isEmpty()) {
                        List<String> artists = new ArrayList<>(top.keySet());
                        profile.ingestSpotifyArtists(artists, null);
                    }
                }
            }
            return profile;
        });
    }

    /**
     * Records a track played by a player to update their taste profile and fatigue history.
     */
    public void recordPlay(UUID playerUuid, Track track) {
        if (playerUuid == null || track == null) return;
        getOrCreateProfile(playerUuid).recordPlay(track);
        saveProfile(playerUuid);
    }

    /**
     * Records a track added to a playlist by a player.
     */
    public void recordPlaylistAdd(UUID playerUuid, Track track) {
        if (playerUuid == null || track == null) return;
        getOrCreateProfile(playerUuid).recordPlaylistAdd(track);
        saveProfile(playerUuid);
    }

    /**
     * Records an explicit thumbs-up like by a player (+0.5 artist affinity boost).
     */
    public void recordLike(UUID playerUuid, Track track) {
        if (playerUuid == null || track == null) return;
        getOrCreateProfile(playerUuid).recordLike(track);
        saveProfile(playerUuid);
    }

    /**
     * Records an explicit thumbs-down dislike by a player (7-day soft cooldown).
     */
    public void recordDislike(UUID playerUuid, Track track) {
        if (playerUuid == null || track == null) return;
        getOrCreateProfile(playerUuid).recordDislike(track);
        saveProfile(playerUuid);
    }

    /**
     * Records an early skip (< 30s) by a player to downweight artist affinity.
     */
    public void recordSkip(UUID playerUuid, Track track) {
        if (playerUuid == null || track == null) return;
        getOrCreateProfile(playerUuid).recordSkip(track);
        saveProfile(playerUuid);
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
     * Predicts the next track to play using the mathematical multi-objective recommendation engine.
     */
    public CompletableFuture<Track> getOrPredictNext(UUID playerUuid, Track seed) {
        if (playerUuid == null) {
            return CompletableFuture.completedFuture(null);
        }

        // Check if player is in a party: if so, compute Spotify Blend centroid
        MusicParty party = plugin.parties().getPartyByPlayer(playerUuid);
        PlayerTasteProfile profile;

        if (party != null && party.getMemberCount() > 1) {
            List<PlayerTasteProfile> memberProfiles = new ArrayList<>();
            for (UUID memberId : party.getMembers()) {
                memberProfiles.add(getOrCreateProfile(memberId));
            }
            profile = PlayerTasteProfile.blend(memberProfiles);
        } else {
            profile = getOrCreateProfile(playerUuid);
        }

        return engine.recommendNext(seed, profile);
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
        if (playerUuid == null || plugin.cache() == null) return;
        PlayerTasteProfile profile = tasteProfiles.get(playerUuid);
        if (profile == null) return;
        CompletableFuture.runAsync(() -> saveProfileSync(playerUuid));
    }

    public void saveProfileSync(UUID playerUuid) {
        if (playerUuid == null || plugin.cache() == null) return;
        PlayerTasteProfile profile = tasteProfiles.get(playerUuid);
        if (profile == null) return;
        plugin.cache().saveTasteProfile(
                playerUuid,
                profile.exportAffinitiesJson(),
                profile.exportGenresJson(),
                profile.exportDislikesJson(),
                profile.exportLineageJson(),
                profile.getLastSyncTimestamp()
        );
    }

    public void saveAll() {
        for (UUID uuid : tasteProfiles.keySet()) {
            saveProfileSync(uuid);
        }
    }

    public void shutdown() {
        saveAll();
    }

    public void onPlayerQuit(UUID playerUuid) {
        if (playerUuid == null) return;
        saveProfileSync(playerUuid);
    }

    public void onPartyDisband(UUID partyUuid) {
        partyAutoplayStates.remove(partyUuid);
    }

    private void loadConfiguredLineages() {
        org.bukkit.configuration.ConfigurationSection sec = plugin.getConfig().getConfigurationSection("recommendations.lineage");
        if (sec != null) {
            for (String hub : sec.getKeys(false)) {
                List<String> members = sec.getStringList(hub);
                for (String m : members) {
                    PlayerTasteProfile.registerGlobalLineage(hub, m);
                }
            }
        }
    }

}

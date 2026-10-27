package dev.vibesindev.vibesmusic.playback;

import dev.vibesindev.vibesmusic.VibesInDevPlugin;
import dev.vibesindev.vibesmusic.model.Track;
import dev.vibesindev.vibesmusic.party.MusicParty;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import dev.vibesindev.vibesmusic.util.RateLimiter;

public final class MusicPlaybackManager {

    private final VibesInDevPlugin plugin;

    /*
     * Used to prevent an old asynchronous download/stream
     * from starting after the player has already skipped/stopped.
     */
    private final ConcurrentMap<UUID, Long> generations =
            new ConcurrentHashMap<>();

    /*
     * Party playback generations prevent an old party preparation
     * from starting after the party has been stopped/skipped.
     */
    private final ConcurrentMap<UUID, Long> partyGenerations =
            new ConcurrentHashMap<>();

    /*
     * party UUID -> members that have reported the current track finished.
     */
    private final ConcurrentMap<UUID, Set<UUID>> partyCompletions =
            new ConcurrentHashMap<>();

    /*
     * target UUID (player or party) -> active playback session.
     */
    private final ConcurrentMap<UUID, PlaybackSession> sessions =
            new ConcurrentHashMap<>();

    public PlaybackSession getSession(UUID targetId) {
        return sessions.get(targetId);
    }

    /*
     * track ID -> CompletableFuture of the active preparation.
     */
    private final ConcurrentMap<String, CompletableFuture<Path>> activePreparations =
            new ConcurrentHashMap<>();
            
    private final RateLimiter requestRateLimiter;

    public MusicPlaybackManager(VibesInDevPlugin plugin) {
        this.plugin = plugin;
        this.requestRateLimiter = new RateLimiter(plugin.getConfig().getInt("limits.requests-per-minute", 10));
        
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, requestRateLimiter::cleanup, 1200L, 1200L);
        
        if (plugin.getConfig().getBoolean("playback.synchronization.enabled", true)) {
            long intervalTicks = plugin.getConfig().getLong("playback.synchronization.position-sync-interval-ms", 5000) / 50;
            
            plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
                for (Player player : plugin.getServer().getOnlinePlayers()) {
                    if (player == null) {
                        continue;
                    }
                    if (plugin.network().isPlaying(player)) {
                        PlaybackSession session = null;
                        
                        MusicParty party = plugin.parties().getPartyByPlayer(player.getUniqueId());
                        if (party != null) {
                            session = sessions.get(party.getPartyId());
                        } else {
                            session = sessions.get(player.getUniqueId());
                        }
                        
                        if (session != null && session.getState() == PlaybackState.PLAYING) {
                            plugin.network().sync(
                                player, 
                                session.getSessionId(), 
                                session.getTrack().id(), 
                                (byte) session.getState().ordinal(), 
                                session.getPositionMs()
                            );
                        }
                    }
                }
            }, intervalTicks, intervalTicks);
        }

        // Gapless playback: check every 5s if upcoming tracks need background caching
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!plugin.getConfig().getBoolean("playback.preload-next", true)) return;

            for (MusicParty party : plugin.parties().getAllParties()) {
                if (party.getCurrentTrack() != null && !party.isPaused()) {
                    Track next = party.peekNext();
                    if (next != null && !plugin.cache().hasAudio(next.id()) && !activePreparations.containsKey(next.id())) {
                        prepareTrack(next);
                    }
                }
            }

            for (Player player : plugin.getServer().getOnlinePlayers()) {
                if (player == null) {
                    continue;
                }
                UUID uuid = player.getUniqueId();
                if (plugin.network().isPlaying(player) && !plugin.network().isPaused(player)) {
                    Track current = plugin.queues().current(uuid);
                    if (current != null) {
                        Track next = plugin.queues().peekNext(uuid, current, true);
                        if (next != null && !plugin.cache().hasAudio(next.id()) && !activePreparations.containsKey(next.id())) {
                            prepareTrack(next);
                        }
                    }
                }
            }
        }, 100L, 100L);
    }

    public void playInput(
            Player player,
            String input
    ) {
        if (!requestRateLimiter.tryAcquire(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "You are making too many requests. Please slow down.");
            return;
        }
        
        plugin.metrics().incrementTracksRequested();

        if (input == null || input.isBlank()) {

            player.sendMessage(
                    ChatColor.RED
                            + "✕ "
                            + ChatColor.WHITE
                            + "Enter a song name or URL."
            );

            return;
        }

        /*
         * If the player is in a music party, normal /music play
         * automatically controls the party.
         *
         * Example:
         *
         *   /music play never gonna give you up
         *
         * plays the track for every party member.
         */
        if (plugin.parties().getPartyByPlayer(
                player.getUniqueId()
        ) != null) {

            playPartyInput(
                    player,
                    input
            );

            return;
        }

        String trimmed = input.trim();
        String lower = trimmed.toLowerCase();
        
        if (lower.contains("spotify.com") || lower.startsWith("spotify:")) {
            if (lower.contains("/playlist") || lower.contains("/album") || lower.contains(":playlist:") || lower.contains(":album:")) {
                if (!player.hasPermission("vibesindev.spotify.playlist")) {
                    player.sendMessage(ChatColor.RED + "You do not have permission to play Spotify playlists.");
                    return;
                }
            } else {
                if (!player.hasPermission("vibesindev.spotify")) {
                    player.sendMessage(ChatColor.RED + "You do not have permission to play Spotify links.");
                    return;
                }
            }
        }

        /*
         * Looks like a URL:
         * resolve it directly.
         */
        if (isUrl(trimmed)) {

            player.sendMessage(
                    ChatColor.GRAY
                            + "Resolving "
                            + ChatColor.AQUA
                            + trimmed
                            + ChatColor.GRAY
                            + "..."
            );

            plugin.sources()
                    .resolve(trimmed)
                    .thenAccept(tracks ->
                            plugin.getServer()
                                    .getScheduler()
                                    .runTask(
                                            plugin,
                                            () -> playResolvedTracks(
                                                    player,
                                                    tracks
                                            )
                                    )
                    )
                    .exceptionally(error -> {

                        sendResolveError(
                                player,
                                error
                        );

                        return null;
                    });

            return;
        }


        /*
         * Otherwise treat it as a search query.
         */
        player.sendMessage(
                ChatColor.GRAY
                        + "Searching for "
                        + ChatColor.AQUA
                        + "\""
                        + trimmed
                        + "\""
                        + ChatColor.GRAY
                        + "..."
        );


        plugin.ytDlp()
                .search(trimmed)
                .thenAccept(track ->
                        plugin.getServer()
                                .getScheduler()
                                .runTask(
                                        plugin,
                                        () -> {

                                            player.sendMessage(
                                                    ChatColor.GRAY
                                                            + "Found: "
                                                            + ChatColor.AQUA
                                                            + track.title()
                                                            + ChatColor.GRAY
                                                            + " — "
                                                            + ChatColor.AQUA
                                                            + track.artist()
                                            );
                                            sendActionBarNotification(player, track);

                                            play(
                                                    player,
                                                    track
                                            );
                                        }
                                )
                )
                .exceptionally(error -> {

                    sendResolveError(
                            player,
                            error
                    );

                    return null;
                });
    }

    /**
     * Starts the first resolved track and queues the remaining tracks.
     *
     * This is used for playlists from any supported source.
     */
    private void playResolvedTracks(
            Player player,
            List<Track> tracks
    ) {

        if (tracks == null || tracks.isEmpty()) {

            player.sendMessage(
                    ChatColor.YELLOW
                            + "No tracks were found."
            );

            return;
        }

        /*
         * play() handles whether the first track should start
         * immediately or be added to the existing queue.
         */
        play(
                player,
                tracks.get(0)
        );

        /*
         * Queue every remaining playlist track.
         */
        for (int i = 1; i < tracks.size(); i++) {

            plugin.queues()
                    .enqueue(
                            player.getUniqueId(),
                            tracks.get(i)
                    );
        }

        if (tracks.size() > 1) {

            player.sendMessage(
                    ChatColor.GREEN
                            + "✓ "
                            + ChatColor.WHITE
                            + "Queued "
                            + ChatColor.AQUA
                            + (tracks.size() - 1)
                            + ChatColor.WHITE
                            + " additional tracks."
            );
        }
    }

    /**
     * Play immediately if nothing is playing.
     * Otherwise add the track to the queue.
     */
    public void play(Player player, Track track) {

        UUID uuid = player.getUniqueId();

        if (plugin.network().isPlaying(player)) {

            plugin.queues().enqueue(uuid, track);
            if (plugin.getConfig().getBoolean("playback.preload-next", true)) {
                Track next = plugin.queues().peekNext(uuid, plugin.queues().current(uuid), true);
                if (next != null) {
                    prepareTrack(next);
                }
            }

            player.sendMessage(
                    ChatColor.GREEN
                            + "✓ "
                            + ChatColor.WHITE
                            + "Added to queue: "
                            + ChatColor.AQUA
                            + track.title()
            );

            return;
        }

        startTrack(player, track, false);
    }

    /**
     * Always play this track immediately.
     */
    public void playNow(Player player, Track track) {

        Track current = plugin.queues().current(player.getUniqueId());
        if (current != null) {
            plugin.queues().addHistory(player.getUniqueId(), current);
        }

        stopCurrentOnly(player);

        startTrack(
                player,
                track,
                false
        );
    }

    /**
     * Skip the current track and play the next queued track.
     */
    public void next(Player player) {

        UUID uuid = player.getUniqueId();

        Track current =
                plugin.queues().current(uuid);

        Track next =
                plugin.queues().advance(uuid, current, false);

        if (current != null) {
            plugin.queues().addHistory(
                    uuid,
                    current
            );
        }

        if (next == null) {

            stopCurrentOnly(player);

            plugin.queues().setCurrent(
                    uuid,
                    null
            );

            player.sendMessage(
                    ChatColor.YELLOW
                            + "There is no next track."
            );

            return;
        }

        stopCurrentOnly(player);

        startTrack(
                player,
                next,
                true
        );
    }

    /**
     * Return to the previously played track.
     */
    public void previous(Player player) {

        UUID uuid = player.getUniqueId();

        Track previous =
                plugin.queues().previous(uuid);

        if (previous == null) {

            player.sendMessage(
                    ChatColor.YELLOW
                            + "There is no previous track."
            );

            return;
        }

        Track current =
                plugin.queues().current(uuid);

        if (current != null) {
            plugin.queues().pushFront(
                    uuid,
                    current
            );
        }

        stopCurrentOnly(player);

        startTrack(
                player,
                previous,
                false
        );
    }

    public void pause(Player player) {
        if (!plugin.network().isPlaying(player)) return;
        if (plugin.network().isPaused(player)) return;

        plugin.network().pause(player);
        PlaybackSession session = sessions.get(player.getUniqueId());
        if (session != null) {
            session.setState(PlaybackState.PAUSED);
            String title = (session.getTrack() != null) ? session.getTrack().title() : "Music";
            sendActionBarStatus(player, ChatColor.YELLOW + "⏸ Paused: " + ChatColor.WHITE + title);
        }
    }

    public void resume(Player player) {
        if (!plugin.network().isPlaying(player)) return;
        if (!plugin.network().isPaused(player)) return;

        plugin.network().resume(player);
        PlaybackSession session = sessions.get(player.getUniqueId());
        if (session != null) {
            session.setState(PlaybackState.PLAYING);
            String title = (session.getTrack() != null) ? session.getTrack().title() : "Music";
            sendActionBarStatus(player, ChatColor.GREEN + "▶ Resumed: " + ChatColor.WHITE + title);
        }
    }
    
    public void seek(Player player, long positionMs) {
        if (!plugin.network().isPlaying(player)) return;
        
        PlaybackSession session = sessions.get(player.getUniqueId());
        if (session != null) {
            session.seek(positionMs);
            plugin.network().seek(player, positionMs);
            
            player.sendMessage(
                org.bukkit.ChatColor.GRAY + "Seeked to " + (positionMs / 1000) + "s."
            );
        }
    }

    /**
     * Stop playback and clear the queue.
     */
    public void stop(Player player) {

        Track current = plugin.queues().current(player.getUniqueId());
        if (current != null) {
            plugin.queues().addHistory(player.getUniqueId(), current);
        }

        invalidate(player);

        plugin.network().stop(player);

        plugin.queues().clear(
                player.getUniqueId()
        );

        player.sendMessage(
                ChatColor.RED
                        + "■ "
                        + ChatColor.WHITE
                        + "Music stopped."
        );
    }

    /**
     * Called when the network streamer reports a natural
     * end of a track.
     */
    public void onTrackFinished(
            Player player,
            UUID streamId,
            Track track
    ) {

        plugin.metrics().incrementTracksCompleted();

        /*
         * Party streams are coordinated separately. Do not let a
         * party member's completion advance that player's personal queue.
         */
        MusicParty party =
                plugin.parties().getPartyByPlayer(
                        player.getUniqueId()
                );

        if (party != null
                && party.getCurrentTrack() != null
                && party.getCurrentTrack().id().equals(track.id())) {

            onPartyTrackFinished(
                    player,
                    streamId,
                    track
            );

            return;
        }

        UUID uuid = player.getUniqueId();

        plugin.network().sendStop(player, streamId);

        Track current =
                plugin.queues().current(uuid);

        /*
         * Ignore stale stream completions.
         */
        if (current == null
                || !current.id().equals(track.id())) {
            return;
        }

        plugin.queues().addHistory(
                uuid,
                track
        );

        PlaybackSession session = sessions.get(uuid);
        if (session != null && session.getTrack().id().equals(track.id())) {
            session.setState(PlaybackState.COMPLETED);
        }

        Track next =
                plugin.queues().advance(uuid, track, true);

        if (next == null) {

            plugin.queues().setCurrent(
                    uuid,
                    null
            );

            return;
        }

        startTrack(
                player,
                next,
                true
        );
    }


    // ============================================================
    // PARTY PLAYBACK
    // ============================================================

    public void playPartyInput(
            Player player,
            String input
    ) {
        if (!requestRateLimiter.tryAcquire(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "You are making too many requests. Please slow down.");
            return;
        }
        
        plugin.metrics().incrementTracksRequested();
        if (input == null || input.isBlank()) {
            player.sendMessage(
                    ChatColor.RED
                            + "✕ "
                            + ChatColor.WHITE
                            + "Enter a song name or URL."
            );
            return;
        }

        MusicParty party =
                plugin.parties().getPartyByPlayer(
                        player.getUniqueId()
                );

        if (party == null) {
            player.sendMessage(
                    ChatColor.YELLOW
                            + "You are not in a music party."
            );
            return;
        }

        String trimmed = input.trim();
        String lower = trimmed.toLowerCase();
        
        if (lower.contains("spotify.com") || lower.startsWith("spotify:")) {
            if (lower.contains("/playlist") || lower.contains("/album") || lower.contains(":playlist:") || lower.contains(":album:")) {
                if (!player.hasPermission("vibesindev.spotify.playlist")) {
                    player.sendMessage(ChatColor.RED + "You do not have permission to play Spotify playlists.");
                    return;
                }
            } else {
                if (!player.hasPermission("vibesindev.spotify")) {
                    player.sendMessage(ChatColor.RED + "You do not have permission to play Spotify links.");
                    return;
                }
            }
        }

        if (isUrl(trimmed)) {

            player.sendMessage(
                    ChatColor.GRAY
                            + "Resolving party playlist/track "
                            + ChatColor.AQUA
                            + trimmed
                            + ChatColor.GRAY
                            + "..."
            );

            plugin.sources()
                    .resolve(trimmed)
                    .thenAccept(tracks ->
                            plugin.getServer()
                                    .getScheduler()
                                    .runTask(
                                            plugin,
                                            () -> playResolvedPartyTracks(
                                                    player,
                                                    tracks
                                            )
                                    )
                    )
                    .exceptionally(error -> {

                        sendResolveError(
                                player,
                                error
                        );

                        return null;
                    });

            return;
        }

        player.sendMessage(
                ChatColor.GRAY
                        + "Searching for "
                        + ChatColor.AQUA
                        + "\""
                        + trimmed
                        + "\""
                        + ChatColor.GRAY
                        + "..."
        );

        plugin.ytDlp()
                .search(trimmed)
                .thenAccept(track ->
                        plugin.getServer()
                                .getScheduler()
                                .runTask(
                                        plugin,
                                        () -> {
                                            player.sendMessage(
                                                    ChatColor.GRAY
                                                            + "Found: "
                                                            + ChatColor.AQUA
                                                            + track.title()
                                                            + ChatColor.GRAY
                                                            + " — "
                                                            + ChatColor.AQUA
                                                            + track.artist()
                                            );

                                            playParty(
                                                    player,
                                                    track
                                            );
                                        }
                                )
                )
                .exceptionally(error -> {
                    sendResolveError(player, error);
                    return null;
                });
    }

    /**
     * Starts the first resolved party track and queues the rest
     * in the shared party queue.
     *
     * This is used for playlists from any supported source.
     */
    private void playResolvedPartyTracks(
            Player player,
            List<Track> tracks
    ) {

        if (tracks == null || tracks.isEmpty()) {

            player.sendMessage(
                    ChatColor.YELLOW
                            + "No party tracks were found."
            );

            return;
        }

        /*
         * playParty() starts the first track immediately when the
         * party has no current track, otherwise it queues it.
         */
        playParty(
                player,
                tracks.get(0)
        );

        MusicParty party =
                plugin.parties()
                        .getPartyByPlayer(
                                player.getUniqueId()
                        );

        if (party == null) {
            return;
        }

        /*
         * Add the remaining playlist tracks to the shared party queue.
         */
        for (int i = 1; i < tracks.size(); i++) {

            party.enqueue(
                    tracks.get(i)
            );
        }

        if (tracks.size() > 1) {

            player.sendMessage(
                    ChatColor.GREEN
                            + "✓ "
                            + ChatColor.WHITE
                            + "Queued "
                            + ChatColor.AQUA
                            + (tracks.size() - 1)
                            + ChatColor.WHITE
                            + " additional party tracks."
            );
        }
    }

    public void playParty(
            Player player,
            Track track
    ) {
        if (track == null) {
            return;
        }

        MusicParty party =
                plugin.parties().getPartyByPlayer(
                        player.getUniqueId()
                );

        if (party == null) {
            player.sendMessage(
                    ChatColor.YELLOW
                            + "You are not in a music party."
            );
            return;
        }

        if (party.getCurrentTrack() != null) {
            party.enqueue(track);
            if (plugin.getConfig().getBoolean("playback.preload-next", true)) {
                Track next = party.peekNext();
                if (next != null) {
                    prepareTrack(next);
                }
            }

            player.sendMessage(
                    ChatColor.GREEN
                            + "✓ "
                            + ChatColor.WHITE
                            + "Added to party queue: "
                            + ChatColor.AQUA
                            + track.title()
            );
            return;
        }

        startPartyTrack(player, party, track);
    }

    public void partyPause(Player player) {
        MusicParty party =
                plugin.parties().getPartyByPlayer(
                        player.getUniqueId()
                );

        if (party == null) {
            player.sendMessage(
                    ChatColor.YELLOW
                            + "You are not in a music party."
            );
            return;
        }

        if (!party.isOwner(player.getUniqueId())) {
            player.sendMessage(
                    ChatColor.RED
                            + "Only the party owner can control party playback."
            );
            return;
        }

        if (party.getCurrentTrack() == null) {
            player.sendMessage(
                    ChatColor.YELLOW
                            + "No party music is currently playing."
            );
            return;
        }

        if (party.isPaused()) {
            player.sendMessage(
                    ChatColor.YELLOW
                            + "Party music is already paused."
            );
            return;
        }

        for (UUID member : party.getMembers()) {
            Player target =
                    plugin.getServer().getPlayer(member);

            if (target != null
                    && target.isOnline()
                    && plugin.network().isSupported(target)) {
                plugin.network().pause(target);
            }
        }

        party.setPaused(true);

        PlaybackSession session = sessions.get(party.getPartyId());
        if (session != null) {
            session.setState(PlaybackState.PAUSED);
        }

        player.sendMessage(
                ChatColor.YELLOW
                        + "Ⅱ Party music paused."
        );
        String pTitle = (party.getCurrentTrack() != null) ? party.getCurrentTrack().title() : "Party Music";
        for (UUID m : party.getMembers()) {
            Player pTarget = plugin.getServer().getPlayer(m);
            if (pTarget != null && pTarget.isOnline()) {
                sendActionBarStatus(pTarget, ChatColor.YELLOW + "⏸ Party Paused: " + ChatColor.WHITE + pTitle);
            }
        }
    }

    public void partyResume(Player player) {
        MusicParty party =
                plugin.parties().getPartyByPlayer(
                        player.getUniqueId()
                );

        if (party == null) {
            player.sendMessage(
                    ChatColor.YELLOW
                            + "You are not in a music party."
            );
            return;
        }

        if (!party.isOwner(player.getUniqueId())) {
            player.sendMessage(
                    ChatColor.RED
                            + "Only the party owner can control party playback."
            );
            return;
        }

        if (party.getCurrentTrack() == null) {
            player.sendMessage(
                    ChatColor.YELLOW
                            + "No party music is currently playing."
            );
            return;
        }

        if (!party.isPaused()) {
            player.sendMessage(
                    ChatColor.YELLOW
                            + "Party music is already playing."
            );
            return;
        }

        for (UUID member : party.getMembers()) {
            Player target =
                    plugin.getServer().getPlayer(member);

            if (target != null
                    && target.isOnline()
                    && plugin.network().isSupported(target)) {
                plugin.network().resume(target);
            }
        }

        party.setPaused(false);

        PlaybackSession session = sessions.get(party.getPartyId());
        if (session != null) {
            session.setState(PlaybackState.PLAYING);
        }

        player.sendMessage(
                ChatColor.GREEN
                        + "▶ Party music resumed."
        );
        String prTitle = (party.getCurrentTrack() != null) ? party.getCurrentTrack().title() : "Party Music";
        for (UUID m : party.getMembers()) {
            Player pTarget = plugin.getServer().getPlayer(m);
            if (pTarget != null && pTarget.isOnline()) {
                sendActionBarStatus(pTarget, ChatColor.GREEN + "▶ Party Resumed: " + ChatColor.WHITE + prTitle);
            }
        }
    }

    public void partySeek(Player player, long positionMs) {
        MusicParty party = plugin.parties().getPartyByPlayer(player.getUniqueId());
        if (party == null) {
            player.sendMessage(org.bukkit.ChatColor.YELLOW + "You are not in a music party.");
            return;
        }

        if (!party.isOwner(player.getUniqueId())) {
            player.sendMessage(org.bukkit.ChatColor.RED + "Only the party owner can control party playback.");
            return;
        }

        if (party.getCurrentTrack() == null) {
            player.sendMessage(org.bukkit.ChatColor.YELLOW + "No party music is currently playing.");
            return;
        }
        
        PlaybackSession session = sessions.get(party.getPartyId());
        if (session != null) {
            session.seek(positionMs);
            
            for (UUID member : party.getMembers()) {
                Player target = plugin.getServer().getPlayer(member);
                if (target != null && target.isOnline() && plugin.network().isSupported(target)) {
                    plugin.network().seek(target, positionMs);
                }
            }
            
            String seekMsg = org.bukkit.ChatColor.LIGHT_PURPLE + "[Party] " + org.bukkit.ChatColor.GRAY + player.getName() + " seeked to " + (positionMs / 1000) + "s.";
            player.sendMessage(seekMsg);
            broadcastToParty(party, seekMsg, player.getUniqueId());
        }
    }

    public void partyStop(Player player) {
        MusicParty party =
                plugin.parties().getPartyByPlayer(
                        player.getUniqueId()
                );

        if (party == null) {
            player.sendMessage(
                    ChatColor.YELLOW
                            + "You are not in a music party."
            );
            return;
        }

        if (!party.isOwner(player.getUniqueId())) {
            player.sendMessage(
                    ChatColor.RED
                            + "Only the party owner can control party playback."
            );
            return;
        }

        stopPartyPlayback(party);

        player.sendMessage(
                ChatColor.RED
                        + "■ "
                        + ChatColor.WHITE
                        + "Party music stopped."
        );
    }

    public void partyNext(Player player) {
        MusicParty party =
                plugin.parties().getPartyByPlayer(
                        player.getUniqueId()
                );

        if (party == null) {
            player.sendMessage(
                    ChatColor.YELLOW
                            + "You are not in a music party."
            );
            return;
        }

        if (!party.isOwner(player.getUniqueId())) {
            player.sendMessage(
                    ChatColor.RED
                            + "Only the party owner can control party playback."
            );
            return;
        }

        Track current = party.getCurrentTrack();
        Track next = party.dequeue();

        if (next == null) {
            stopPartyPlayback(party);

            player.sendMessage(
                    ChatColor.YELLOW
                            + "There is no next party track."
            );
            return;
        }

        stopPartyStreams(party);

        if (current != null
                && party.getRepeatMode() == MusicParty.RepeatMode.ALL) {
            party.enqueue(current);
        }

        startPartyTrack(player, party, next);
    }

    public void syncNewPartyMember(Player player, MusicParty party) {
        PlaybackSession session = sessions.get(party.getPartyId());
        if (session == null || (session.getState() != PlaybackState.PLAYING && session.getState() != PlaybackState.PAUSED)) {
            return;
        }

        if (party.getCurrentTrack() == null) {
            return;
        }

        if (!plugin.network().isSupported(player)) {
            return;
        }

        prepareTrack(party.getCurrentTrack())
            .thenAccept(path -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (plugin.network().stream(player, party.getCurrentTrack(), path)) {
                    long positionMs = session.getPositionMs();
                    
                    if (positionMs > 0) {
                        plugin.network().seek(player, positionMs);
                    }
                    
                    if (session.getState() == PlaybackState.PAUSED) {
                        plugin.network().pause(player);
                    } else {
                        plugin.network().sync(
                            player, 
                            session.getSessionId(), 
                            session.getTrack().id(), 
                            (byte) session.getState().ordinal(), 
                            positionMs
                        );
                    }
                    
                    Set<UUID> started = partyCompletions.get(party.getPartyId());
                    if (started != null) {
                        started.add(player.getUniqueId());
                    sendActionBarNotification(player, party.getCurrentTrack());
                    }
                    
                    plugin.metrics().incrementTracksPlayed();
                }
            }));
    }

    private void startPartyTrack(
            Player owner,
            MusicParty party,
            Track track
    ) {
        UUID partyId = party.getPartyId();

        long generation =
                partyGenerations.merge(
                        partyId,
                        1L,
                        (a, b) -> a + b
                );

        party.setCurrentTrack(track);
        party.setPaused(false);

        PlaybackSession session = new PlaybackSession(partyId, track, generation);
        session.setState(PlaybackState.PREPARING);
        sessions.put(partyId, session);

        partyCompletions.put(
                partyId,
                ConcurrentHashMap.newKeySet()
        );

        owner.sendMessage(
                ChatColor.GRAY
                        + "Preparing party track "
                        + ChatColor.AQUA
                        + track.title()
                        + ChatColor.GRAY
                        + "..."
        );

        prepareTrack(track)
                .thenAccept(path ->
                        plugin.getServer()
                                .getScheduler()
                                .runTask(
                                        plugin,
                                        () -> {
                                            if (!isPartyGenerationCurrent(
                                                    partyId,
                                                    generation
                                            )) {
                                                return;
                                            }

                                            Set<UUID> started =
                                                    new HashSet<>();

                                            for (UUID member :
                                                    party.getMembers()) {

                                                Player target =
                                                        plugin.getServer()
                                                                .getPlayer(member);

                                                if (target == null
                                                        || !target.isOnline()
                                                        || !plugin.network()
                                                        .isSupported(target)) {
                                                    continue;
                                                }

                                                if (plugin.network().stream(
                                                        target,
                                                        track,
                                                        path
                                                )) {
                                                    plugin.metrics().incrementTracksPlayed();
                                                    started.add(member);
                                                }
                                            }

                                            if (started.isEmpty()) {
                                                party.setCurrentTrack(null);
                                                partyCompletions.remove(partyId);

                                                PlaybackSession currentSession = sessions.get(partyId);
                                                if (currentSession != null && currentSession.getGeneration() == generation) {
                                                    currentSession.setState(PlaybackState.FAILED);
                                                }

                                                owner.sendMessage(
                                                        ChatColor.RED
                                                                + "Could not start party playback. "
                                                                + "No party members have the VibesInDev client."
                                                );
                                                return;
                                            }

                                            PlaybackSession currentSession = sessions.get(partyId);
                                            if (currentSession != null && currentSession.getGeneration() == generation) {
                                                currentSession.setState(PlaybackState.PLAYING);
                                            }

                                            if (plugin.getConfig().getBoolean("playback.preload-next", true)) {
                                                Track next = party.peekNext();
                                                if (next != null) {
                                                    prepareTrack(next);
                                                }
                                            }

                                            partyCompletions.put(
                                                    partyId,
                                                    ConcurrentHashMap.newKeySet()
                                            );

                                            String message =
                                                    ChatColor.GREEN
                                                            + "▶ "
                                                            + ChatColor.WHITE
                                                            + "Now playing: "
                                                            + ChatColor.AQUA
                                                            + track.title()
                                                            + ChatColor.GRAY
                                                            + " — "
                                                            + ChatColor.AQUA
                                                            + track.artist();

                                            for (UUID member : party.getMembers()) {
                                                Player target =
                                                        plugin.getServer()
                                                                .getPlayer(member);

                                                if (target != null && target.isOnline()) {
                                                    target.sendMessage(message);
                                                    sendActionBarNotification(target, track);
                                                }
                                            }
                                        }
                                )
                )
                .exceptionally(error -> {
                    plugin.getLogger().warning(
                            "Could not prepare party track "
                                    + track.title()
                                    + ": "
                                    + error.getMessage()
                    );

                    plugin.getServer()
                            .getScheduler()
                            .runTask(
                                    plugin,
                                    () -> {
                                        if (!isPartyGenerationCurrent(
                                                partyId,
                                                generation
                                        )) {
                                            return;
                                        }

                                        party.setCurrentTrack(null);
                                        partyCompletions.remove(partyId);

                                        PlaybackSession exSession = sessions.get(partyId);
                                        if (exSession != null && exSession.getGeneration() == generation) {
                                            exSession.setState(PlaybackState.FAILED);
                                        }

                                        owner.sendMessage(
                                                ChatColor.RED
                                                        + "Could not prepare party track "
                                                        + track.title()
                                                        + "."
                                        );
                                    }
                            );

                    return null;
                });
    }

    public void onPartyTrackFinished(
            Player player,
            UUID streamId,
            Track track
    ) {
        MusicParty party =
                plugin.parties().getPartyByPlayer(
                        player.getUniqueId()
                );

        if (party == null
                || party.getCurrentTrack() == null
                || !party.getCurrentTrack().id().equals(track.id())) {
            return;
        }

        Set<UUID> completed =
                partyCompletions.computeIfAbsent(
                        party.getPartyId(),
                        ignored -> ConcurrentHashMap.newKeySet()
                );

        completed.add(player.getUniqueId());

        int activeMembers = 0;

        for (UUID member : party.getMembers()) {
            Player target =
                    plugin.getServer().getPlayer(member);

            if (target != null
                    && target.isOnline()
                    && plugin.network().isSupported(target)) {
                activeMembers++;
            }
        }

        if (completed.size() < Math.max(1, activeMembers)) {
            return;
        }

        Track current = party.getCurrentTrack();

        if (current == null
                || !current.id().equals(track.id())) {
            return;
        }

        partyCompletions.remove(party.getPartyId());

        PlaybackSession session = sessions.get(party.getPartyId());
        if (session != null && session.getTrack().id().equals(track.id())) {
            session.setState(PlaybackState.COMPLETED);
        }

        if (party.getRepeatMode() == MusicParty.RepeatMode.ONE) {
            Player owner =
                    plugin.getServer().getPlayer(party.getOwner());

            if (owner != null) {
                startPartyTrack(owner, party, current);
            }

            return;
        }

        Track next = party.dequeue();

        if (next == null) {
            if (party.getRepeatMode() == MusicParty.RepeatMode.ALL) {
                party.enqueue(current);
                next = party.dequeue();
            }
        }

        if (next == null) {
            party.setCurrentTrack(null);
            party.setPaused(false);
            return;
        }

        Player owner =
                plugin.getServer().getPlayer(party.getOwner());

        if (owner == null) {
            party.setCurrentTrack(null);
            return;
        }

        startPartyTrack(owner, party, next);
    }

    private void stopPartyPlayback(
            MusicParty party
    ) {
        partyGenerations.merge(
                party.getPartyId(),
                1L,
                (a, b) -> a + b
        );

        stopPartyStreams(party);

        partyCompletions.remove(
                party.getPartyId()
        );

        PlaybackSession session = sessions.get(party.getPartyId());
        if (session != null && session.getState() != PlaybackState.COMPLETED) {
            session.setState(PlaybackState.STOPPING);
            cancelPreparationIfNotNeeded(session.getTrack().id());
        }

        party.clearCurrentTrack();
        party.setPaused(false);
        party.clearQueue();
    }

    private void stopPartyStreams(
            MusicParty party
    ) {
        for (UUID member : party.getMembers()) {
            Player target =
                    plugin.getServer().getPlayer(member);

            if (target != null
                    && target.isOnline()) {
                plugin.network().stop(target);
            }
        }
    }

    private boolean isPartyGenerationCurrent(
            UUID partyId,
            long generation
    ) {
        return partyGenerations.getOrDefault(
                partyId,
                0L
        ) == generation;
    }


    public Track current(Player player) {
        return plugin.queues().current(
                player.getUniqueId()
        );
    }

    public void clear(Player player) {

        invalidate(player);

        plugin.queues().clear(
                player.getUniqueId()
        );
    }

    private void startTrack(
            Player player,
            Track track,
            boolean fromQueue
    ) {

        UUID uuid = player.getUniqueId();

        long generation =
                generations.merge(
                        uuid,
                        1L,
                        (a, b) -> a + b
                );

        plugin.queues().setCurrent(
                uuid,
                track
        );

        PlaybackSession session = new PlaybackSession(uuid, track, generation);
        session.setState(PlaybackState.PREPARING);
        sessions.put(uuid, session);

        player.sendMessage(
                ChatColor.GRAY
                        + "Preparing "
                        + ChatColor.AQUA
                        + track.title()
                        + ChatColor.GRAY
                        + "..."
        );

        prepareTrack(track)
                .thenAccept(path ->
                        plugin.getServer()
                                .getScheduler()
                                .runTask(
                                        plugin,
                                        () -> {

                                            if (!isGenerationCurrent(
                                                    uuid,
                                                    generation
                                            )) {
                                                return;
                                            }

                                            if (!player.isOnline()) {
                                                return;
                                            }

                                            if (!plugin.network()
                                                    .stream(
                                                            player,
                                                            track,
                                                            path
                                                    )) {

                                                player.sendMessage(
                                                        ChatColor.RED
                                                                + "Could not start music playback."
                                                );

                                                plugin.queues()
                                                        .setCurrent(
                                                                uuid,
                                                                null
                                                        );

                                                PlaybackSession currentSession = sessions.get(uuid);
                                                if (currentSession != null && currentSession.getGeneration() == generation) {
                                                    currentSession.setState(PlaybackState.FAILED);
                                                }
                                                return;
                                            }

                                            plugin.metrics().incrementTracksPlayed();

                                            PlaybackSession currentSession = sessions.get(uuid);
                                            if (currentSession != null && currentSession.getGeneration() == generation) {
                                                currentSession.setState(PlaybackState.PLAYING);
                                            }

                                            if (plugin.getConfig().getBoolean("playback.preload-next", true)) {
                                                Track next = plugin.queues().peekNext(uuid, track, true);
                                                if (next != null) {
                                                    prepareTrack(next);
                                                }
                                            }

                                            player.sendMessage(
                                                    ChatColor.GREEN
                                                            + "▶ "
                                                            + ChatColor.WHITE
                                                            + track.title()
                                                            + ChatColor.GRAY
                                                            + " — "
                                                            + ChatColor.AQUA
                                                            + track.artist()
                                            );
                                        }
                                )
                )
                .exceptionally(error -> {

                    plugin.getLogger().warning(
                            "Could not prepare track "
                                    + track.title()
                                    + ": "
                                    + error.getMessage()
                    );

                    plugin.getServer()
                            .getScheduler()
                            .runTask(
                                    plugin,
                                    () -> {

                                        if (!isGenerationCurrent(
                                                uuid,
                                                generation
                                        )) {
                                            return;
                                        }

                                        player.sendMessage(
                                                ChatColor.RED
                                                        + "Could not prepare "
                                                        + track.title()
                                                        + "."
                                        );

                                        PlaybackSession currentSession = sessions.get(uuid);
                                        if (currentSession != null && currentSession.getGeneration() == generation) {
                                            currentSession.setState(PlaybackState.FAILED);
                                        }

                                        /*
                                         * Automatically continue with
                                         * the next queued track.
                                         */
                                        Track next =
                                                plugin.queues()
                                                        .advance(uuid, null, false);

                                        if (next != null) {
                                            startTrack(
                                                    player,
                                                    next,
                                                    true
                                            );
                                        } else {
                                            plugin.queues()
                                                    .setCurrent(
                                                            uuid,
                                                            null
                                                    );
                                        }
                                    }
                            );

                    return null;
                });
    }

    private CompletableFuture<Path> prepareTrack(
            Track track
    ) {

        if (plugin.cache().hasAudio(track.id())) {
            Track cached = plugin.cache().readMetadataQuietly(track.id());
            if (cached != null) {
                boolean titleMismatch = !track.title().equalsIgnoreCase(track.id())
                        && !cached.title().equalsIgnoreCase(track.title());
                boolean corruptOldRegex = cached.title().equalsIgnoreCase(cached.artist())
                        && !track.title().equalsIgnoreCase(track.artist());
                if (titleMismatch || corruptOldRegex) {
                    plugin.getLogger().warning("[VibesInDev] Invalidating stale/corrupted cache for track: "
                            + track.id() + " (cached: " + cached.title() + " - " + cached.artist()
                            + ", requested: " + track.title() + " - " + track.artist() + ")");
                    plugin.cache().invalidate(track.id());
                } else {
                    plugin.getLogger().info("[VibesInDev] Cache HIT for track: " + track.id());
                    plugin.metrics().incrementCacheHits();
                    return CompletableFuture.completedFuture(
                            plugin.cache().audio(track.id())
                    );
                }
            } else {
                plugin.getLogger().info("[VibesInDev] Cache HIT for track: " + track.id());
                plugin.metrics().incrementCacheHits();
                return CompletableFuture.completedFuture(
                        plugin.cache().audio(track.id())
                );
            }
        }

        plugin.getLogger().info("[VibesInDev] Cache MISS for track: " + track.id());
        plugin.metrics().incrementCacheMisses();
        return activePreparations.computeIfAbsent(track.id(), id -> {
            CompletableFuture<Track> resolvedTrackFuture;

            if (track.sourceUrl().startsWith("spotify-lazy:")) {
                resolvedTrackFuture = plugin.ytDlp().searchAndResolve(track.title(), track.artist(), track.durationSeconds())
                        .thenApply(realTrack -> new Track(
                                track.id(), // Keep the Spotify ID so caching works correctly
                                track.title(),
                                track.artist(),
                                track.durationSeconds(),
                                realTrack.sourceUrl() // use youtube URL for downloading
                        ));
            } else {
                resolvedTrackFuture = CompletableFuture.completedFuture(track);
            }

            return resolvedTrackFuture.thenCompose(resolvedTrack ->
                    plugin.ytDlp()
                            .downloadAudio(
                                    resolvedTrack,
                                    plugin.cache().rawDirectory()
                            )
                    .thenCompose(raw ->
                            plugin.ffmpeg()
                                    .convert(
                                            raw,
                                            plugin.cache()
                                                    .audioDirectory()
                                    )
                                    .whenComplete(
                                            (path, error) ->
                                                    plugin.cache()
                                                            .deleteRawFile(raw)
                                    )
                    )
                    .thenApply(path -> {

                        try {
                            plugin.cache()
                                    .writeMetadata(track);

                        } catch (Exception e) {

                            throw new RuntimeException(
                                    "Could not write track metadata",
                                    e
                            );
                        }

                        return path;
                    })
            )
            .whenComplete((path, error) -> {
                activePreparations.remove(id);
            });
        });
    }

    private void stopCurrentOnly(Player player) {

        invalidate(player);

        plugin.network().stop(player);
    }

    private void invalidate(Player player) {

        generations.merge(
                player.getUniqueId(),
                1L,
                (a, b) -> a + b
        );
        
        PlaybackSession session = sessions.get(player.getUniqueId());
        if (session != null && session.getState() != PlaybackState.COMPLETED) {
            session.setState(PlaybackState.STOPPING);
            cancelPreparationIfNotNeeded(session.getTrack().id());
        }
    }

    private void cancelPreparationIfNotNeeded(String trackId) {
        if (trackId == null) return;
        
        boolean needed = sessions.values().stream()
                .anyMatch(s -> s.getTrack().id().equals(trackId) && 
                        (s.getState() == PlaybackState.PREPARING || s.getState() == PlaybackState.PLAYING));
                        
        if (!needed) {
            CompletableFuture<Path> prep = activePreparations.remove(trackId);
            if (prep != null) {
                plugin.getLogger().info("[VibesInDev] Cancelling preparation for track: " + trackId);
                prep.cancel(true);
                dev.vibesindev.vibesmusic.util.ProcessRunner.cancelProcessesContaining(trackId);
            }
        }
    }

    private boolean isGenerationCurrent(
            UUID player,
            long generation
    ) {

        return generations.getOrDefault(
                player,
                0L
        ) == generation;
    }

    private boolean isUrl(String input) {
        if (input == null) return false;
        String lower = input.toLowerCase();

        return lower.startsWith("http://")
                || lower.startsWith("https://")
                || lower.startsWith("www.")
                || lower.startsWith("spotify:");
    }

    private void sendResolveError(
            Player player,
            Throwable error
    ) {

        Throwable cause = error;

        if (cause instanceof java.util.concurrent.CompletionException
                && cause.getCause() != null) {

            cause = cause.getCause();
        }

        String message =
                cause.getMessage();

        if (message == null
                || message.isBlank()) {

            message = "Unknown error.";
        }

        String finalMessage = message;
        plugin.metrics().incrementTracksFailed();

        plugin.getServer()
                .getScheduler()
                .runTask(
                        plugin,
                        () -> player.sendMessage(
                                ChatColor.RED
                                        + "✕ "
                                        + ChatColor.WHITE
                                        + "Could not find/play that song: "
                                        + finalMessage
                        )
                );
    }

    /**
     * Sends a chat message to every online member of a party.
     * The sender already received their own message before calling this,
     * so pass the sender's UUID to skip them.
     */
    public void sendActionBarNotification(Player player, Track track) {
        if (player == null || !player.isOnline() || track == null) return;
        try {
            String bar = ChatColor.GOLD + "🎵 " + ChatColor.YELLOW + "Now Playing: "
                    + ChatColor.WHITE + track.title()
                    + ChatColor.GRAY + " • "
                    + ChatColor.AQUA + track.artist()
                    + ChatColor.DARK_GRAY + " [" + ChatColor.GRAY + track.durationText() + ChatColor.DARK_GRAY + "]";
            player.spigot().sendMessage(
                    net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                    net.md_5.bungee.api.chat.TextComponent.fromLegacy(bar)
            );
        } catch (Throwable ignored) {
        }
    }

    public void sendActionBarStatus(Player player, String message) {
        if (player == null || !player.isOnline() || message == null) return;
        try {
            player.spigot().sendMessage(
                    net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                    net.md_5.bungee.api.chat.TextComponent.fromLegacy(message)
            );
        } catch (Throwable ignored) {
        }
    }

    public void playCustomPlaylist(Player player, String playlistName, List<Track> tracks) {
        if (tracks == null || tracks.isEmpty()) {
            player.sendMessage(ChatColor.RED + "Playlist is empty.");
            return;
        }

        MusicParty party = plugin.parties().getPartyByPlayer(player.getUniqueId());
        if (party != null) {
            if (!party.isOwner(player.getUniqueId())) {
                player.sendMessage(ChatColor.RED + "Only the party owner can start playlists for the party.");
                return;
            }
            playResolvedPartyTracks(player, tracks);
            broadcastToPartyAll(party, ChatColor.GOLD + "[Party] " + ChatColor.GREEN + "Started playlist: " + ChatColor.AQUA + playlistName + ChatColor.GRAY + " (" + tracks.size() + " tracks)");
        } else {
            playResolvedTracks(player, tracks);
            player.sendMessage(ChatColor.GOLD + "[VibesInDev] " + ChatColor.GREEN + "Started playlist: " + ChatColor.AQUA + playlistName + ChatColor.GRAY + " (" + tracks.size() + " tracks)");
        }
    }

        public void broadcastToParty(MusicParty party, String message, UUID exceptUuid) {
        for (UUID member : party.getMembers()) {
            if (member.equals(exceptUuid)) continue;
            Player p = plugin.getServer().getPlayer(member);
            if (p != null && p.isOnline()) {
                p.sendMessage(message);
            }
        }
    }

    public void broadcastToPartyAll(MusicParty party, String message) {
        broadcastToParty(party, message, null);
    }

}



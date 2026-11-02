package dev.vibesindev.vibesmusic.command;

import dev.vibesindev.vibesmusic.VibesInDevPlugin;
import dev.vibesindev.vibesmusic.model.Track;
import dev.vibesindev.vibesmusic.party.MusicParty;
import dev.vibesindev.vibesmusic.party.MusicPartyManager;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.Bukkit;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class MusicCommand
        implements CommandExecutor, TabCompleter {

    private final VibesInDevPlugin plugin;

    public MusicCommand(
            VibesInDevPlugin plugin
    ) {

        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {

        if (args.length == 0) {

            if (!(sender instanceof Player player)) {
                help(sender);
                return true;
            }

            if (!player.hasPermission(
                    "vibesindev.use"
            )) {
                return deny(player);
            }

            plugin.musicMenu().open(player);

            return true;
        }

        String sub =
                args[0].toLowerCase();

        if (sub.equals("reload")) {

            if (!sender.hasPermission(
                    "vibesindev.admin"
            )) {
                return deny(sender);
            }

            plugin.reloadConfig();

            sender.sendMessage(
                    ChatColor.GREEN
                            + "✓ "
                            + ChatColor.WHITE
                            + "VibesInDev reloaded."
            );

            return true;
        }

        if (sub.equals("rec")) {
            recCommand(sender, args);
            return true;
        }

        if (sub.equals("recstats")) {
            recStatsCommand(sender, args);
            return true;
        }

        // /music autoplay [on|off]
        if (sub.equals("autoplay")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "Only players can use this command.");
                return true;
            }
            if (!player.hasPermission("vibesindev.use")) {
                return deny(player);
            }
            if (plugin.recommendations() == null) {
                player.sendMessage(ChatColor.RED + "Recommendation system is not available.");
                return true;
            }
            if (args.length >= 2) {
                boolean enable = args[1].equalsIgnoreCase("on");
                boolean disable = args[1].equalsIgnoreCase("off");
                if (!enable && !disable) {
                    player.sendMessage(ChatColor.RED + "Usage: /music autoplay [on|off]");
                    return true;
                }
                plugin.recommendations().setAutoplay(player.getUniqueId(), enable);
                player.sendMessage(
                        (enable ? ChatColor.GREEN + "✓ " : ChatColor.GRAY + "○ ")
                                + ChatColor.WHITE + "Autoplay "
                                + (enable ? ChatColor.GREEN + "enabled." : ChatColor.GRAY + "disabled.")
                );
            } else {
                // Toggle
                boolean current = plugin.recommendations().isAutoplayEnabled(player.getUniqueId());
                boolean newState = !current;
                plugin.recommendations().setAutoplay(player.getUniqueId(), newState);
                player.sendMessage(
                        (newState ? ChatColor.GREEN + "✓ " : ChatColor.GRAY + "○ ")
                                + ChatColor.WHITE + "Autoplay "
                                + (newState ? ChatColor.GREEN + "enabled." : ChatColor.GRAY + "disabled.")
                );
            }
            return true;
        }

        // /music party autoplay [on|off]
        if (sub.equals("party") && args.length >= 3
                && args[1].equalsIgnoreCase("autoplay")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "Only players can use this command.");
                return true;
            }
            if (!player.hasPermission("vibesindev.use")) {
                return deny(player);
            }
            if (plugin.recommendations() == null) {
                player.sendMessage(ChatColor.RED + "Recommendation system is not available.");
                return true;
            }
            dev.vibesindev.vibesmusic.party.MusicParty party =
                    plugin.parties().getPartyByPlayer(player.getUniqueId());
            if (party == null) {
                player.sendMessage(ChatColor.YELLOW + "You are not in a music party.");
                return true;
            }
            if (!party.isOwner(player.getUniqueId())) {
                player.sendMessage(ChatColor.RED + "Only the party owner can control party autoplay.");
                return true;
            }
            boolean enable = args[2].equalsIgnoreCase("on");
            plugin.recommendations().setPartyAutoplay(party.getPartyId(), enable);
            String msg = (enable ? ChatColor.GREEN + "✓ " : ChatColor.GRAY + "○ ")
                    + ChatColor.WHITE + "Party autoplay "
                    + (enable ? ChatColor.GREEN + "enabled." : ChatColor.GRAY + "disabled.");
            player.sendMessage(msg);
            // Broadcast to party members
            for (UUID memberId : party.getMembers()) {
                if (!memberId.equals(player.getUniqueId())) {
                    Player member = plugin.getServer().getPlayer(memberId);
                    if (member != null && member.isOnline()) {
                        member.sendMessage(
                                ChatColor.LIGHT_PURPLE + "[Party] " + ChatColor.WHITE
                                        + player.getName() + " " + (enable ? "enabled" : "disabled")
                                        + " party autoplay."
                        );
                    }
                }
            }
            return true;
        }

        // /music like
        if (sub.equals("like")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "Only players can use this command.");
                return true;
            }
            if (!player.hasPermission("vibesindev.use")) {
                return deny(player);
            }
            dev.vibesindev.vibesmusic.playback.PlaybackSession session = plugin.playback().getSession(player.getUniqueId());
            Track track = (session != null) ? session.getTrack() : null;
            if (track == null) {
                player.sendMessage(ChatColor.YELLOW + "No track is currently playing.");
                return true;
            }
            boolean isLiked = plugin.cache().isTrackLiked(player.getUniqueId(), track.id());
            boolean newLiked = !isLiked;
            plugin.cache().recordTrackLiked(player.getUniqueId(), track.id(), newLiked);
            if (newLiked) {
                if (plugin.recommendations() != null) {
                    plugin.recommendations().recordLike(player.getUniqueId(), track);
                }
                player.sendMessage(
                        ChatColor.GREEN + "❤ Liked "
                                + ChatColor.WHITE + track.title()
                                + ChatColor.GREEN + " (+0.5 taste affinity)!"
                );
            } else {
                player.sendMessage(
                        ChatColor.YELLOW + "Unliked "
                                + ChatColor.WHITE + track.title()
                                + ChatColor.YELLOW + "."
                );
            }
            return true;
        }

        // /music dislike
        if (sub.equals("dislike")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "Only players can use this command.");
                return true;
            }
            if (!player.hasPermission("vibesindev.use")) {
                return deny(player);
            }
            dev.vibesindev.vibesmusic.playback.PlaybackSession session = plugin.playback().getSession(player.getUniqueId());
            Track track = (session != null) ? session.getTrack() : null;
            if (track == null) {
                player.sendMessage(ChatColor.YELLOW + "No track is currently playing.");
                return true;
            }
            if (plugin.recommendations() != null) {
                plugin.recommendations().recordDislike(player.getUniqueId(), track);
            }
            plugin.cache().recordTrackLiked(player.getUniqueId(), track.id(), false);
            plugin.cache().recordTrackSkipped(player.getUniqueId(), track.id());
            player.sendMessage(
                    ChatColor.RED + "👎 Disliked "
                            + ChatColor.WHITE + track.title()
                            + ChatColor.RED + ". 7-day cooldown applied. Skipping..."
            );
            plugin.playback().next(player);
            return true;
        }

        // /music spotify [login|code|sync|status|logout]
        if (sub.equals("spotify")) {
            if (plugin.spotify() == null) {
                sender.sendMessage(ChatColor.RED + "Spotify support is disabled.");
                return true;
            }

            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "Only players can manage personal Spotify authentication.");
                return true;
            }

            if (!player.hasPermission("vibesindev.use")) {
                return deny(player);
            }

            String action = args.length >= 2 ? args[1].toLowerCase() : "status";

            switch (action) {
                case "login" -> {
                    try {
                        String url = plugin.spotify().beginLogin(player);

                        player.sendMessage("");
                        player.sendMessage(ChatColor.GREEN + "=== Spotify Authentication ===");

                        TextComponent link = new TextComponent(ChatColor.GREEN + "" + ChatColor.BOLD + " [CLICK HERE TO LOG IN] ");
                        link.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
                        link.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(ChatColor.AQUA + "Click to open Spotify authorization in your browser")));

                        player.spigot().sendMessage(new TextComponent(ChatColor.WHITE + "1. "), link);
                        player.sendMessage(ChatColor.GRAY + "2. Click 'Agree' on Spotify's official website.");
                        player.sendMessage(ChatColor.DARK_AQUA + "   ⚡ If playing locally, authentication completes automatically!");
                        player.sendMessage("");
                        player.sendMessage(ChatColor.GRAY + "If on a remote server (zero-port-forwarding):");

                        TextComponent bookLink = new TextComponent(ChatColor.GOLD + "" + ChatColor.BOLD + " [CLICK FOR AUTH BOOK] ");
                        bookLink.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/music spotify book"));
                        bookLink.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(ChatColor.YELLOW + "Get a Book & Quill to paste your URL without character limits!")));

                        player.spigot().sendMessage(new TextComponent(ChatColor.GRAY + "3. "), bookLink, new TextComponent(ChatColor.GRAY + "(paste up to 1024 chars)"));
                        player.sendMessage(ChatColor.GRAY + "4. Or run: " + ChatColor.AQUA + "/music spotify code <paste_url_or_code>");
                        player.sendMessage("");
                    } catch (Exception e) {
                        player.sendMessage(ChatColor.RED + "Could not start Spotify login: " + e.getMessage());
                    }
                    return true;
                }
                case "book" -> {
                    dev.vibesindev.vibesmusic.spotify.SpotifyAuthBookListener.giveAuthBook(player);
                    return true;
                }
                case "code" -> {
                    if (args.length < 3) {
                        player.sendMessage(ChatColor.RED + "Usage: /music spotify code <paste_url_or_code>");
                        player.sendMessage(ChatColor.YELLOW + "💡 Minecraft chat limits messages to 256 characters!");
                        player.sendMessage(ChatColor.AQUA + "👉 Run /music spotify book to paste with a Book & Quill (supports 1024 chars)!");
                        return true;
                    }
                    String rawInput = args[2];
                    if (args.length > 3) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 2; i < args.length; i++) {
                            if (i > 2) sb.append(" ");
                            sb.append(args[i]);
                        }
                        rawInput = sb.toString();
                    }
                    player.sendMessage(ChatColor.GRAY + "Exchanging authorization code with Spotify...");
                    plugin.spotify().exchangeCodeForPlayer(player, rawInput)
                            .thenAccept(msg -> player.sendMessage(ChatColor.GREEN + "✔ " + ChatColor.WHITE + msg))
                            .exceptionally(err -> {
                                player.sendMessage(ChatColor.RED + "✖ Spotify authentication failed: " + err.getMessage());
                                player.sendMessage(ChatColor.YELLOW + "💡 Hint: Minecraft chat truncates messages at 256 characters!");
                                player.sendMessage(ChatColor.AQUA + "👉 Run " + ChatColor.WHITE + "/music spotify book" + ChatColor.AQUA + " to paste with a Book & Quill (no character limit)!");
                                player.sendMessage(ChatColor.AQUA + "👉 Or run " + ChatColor.WHITE + "/music spotify code append <second_half>" + ChatColor.AQUA + " to append the rest!");
                                return null;
                            });
                    return true;
                }
                case "sync" -> {
                    if (!plugin.spotify().hasUserAuthorization(player.getUniqueId())) {
                        player.sendMessage(ChatColor.YELLOW + "You are not logged into Spotify. Run /music spotify login first.");
                        return true;
                    }
                    player.sendMessage(ChatColor.GRAY + "Syncing taste profile with your Spotify account...");
                    plugin.spotify().syncTasteProfile(player);
                    return true;
                }
                case "logout" -> {
                    boolean loggedOut = plugin.spotify().logoutPlayer(player.getUniqueId());
                    if (loggedOut) {
                        player.sendMessage(ChatColor.GREEN + "✓ " + ChatColor.WHITE + "Spotify account unlinked successfully.");
                    } else {
                        player.sendMessage(ChatColor.GRAY + "You were not logged into Spotify.");
                    }
                    return true;
                }
                case "status" -> {
                    boolean auth = plugin.spotify().hasUserAuthorization(player.getUniqueId());
                    player.sendMessage(ChatColor.GREEN + "=== Spotify Status ===");
                    player.sendMessage(ChatColor.GRAY + "Account Linked: " + (auth ? ChatColor.GREEN + "YES" : ChatColor.RED + "NO"));
                    if (auth && plugin.recommendations() != null) {
                        dev.vibesindev.vibesmusic.recommendation.PlayerTasteProfile profile =
                                plugin.recommendations().getOrCreateProfile(player.getUniqueId());
                        long lastSync = profile.getLastSyncTimestamp();
                        String syncText = lastSync > 0 ? (System.currentTimeMillis() - lastSync) / 60000 + "m ago" : "Never";
                        player.sendMessage(ChatColor.GRAY + "Last Profile Sync: " + ChatColor.WHITE + syncText);
                    }
                    if (!auth) {
                        player.sendMessage(ChatColor.YELLOW + "Type /music spotify login to connect your account.");
                    }
                    return true;
                }
                default -> {
                    player.sendMessage(ChatColor.RED + "Usage: /music spotify <login|code|sync|status|logout>");
                    return true;
                }
            }
        }

        if (!(sender instanceof Player player)) {

            sender.sendMessage(
                    "This command requires a player."
            );

            return true;
        }

        if (!player.hasPermission(
                "vibesindev.use"
        )) {
            return deny(player);
        }

        if (sub.equals("party")) {
            partyCommand(player, args);
            return true;
        }

        switch (sub) {

            case "play" ->
                    request(
                            player,
                            args
                    );

            case "nowplaying" ->
                    nowPlaying(player);

            case "queue" ->
                    queue(player, args);

            case "skip" ->
                    skip(player);

            case "seek" ->
                    seek(player, args);

            case "previous" ->
                    plugin.playback()
                            .previous(player);

            case "shuffle" ->
                    shuffle(player);

            case "repeat" ->
                    repeat(player, args);

            case "pause" ->
                    pause(player);

            case "resume" ->
                    resume(player);

            case "stop" ->
                    stop(player);

            case "volume", "vol" ->
                    volume(player, args);

            case "playlist", "playlists" ->
                    playlistCommand(player, args);

            case "status", "diagnostics", "metrics" ->
                    status(player);

            case "why" ->
                    why(player);
                    
            case "debug" ->
                    debug(player, args);

            default ->
                    help(player);
        }

        return true;
    }

    private void volume(Player player, String[] args) {
        if (args.length < 2) {
            float current = plugin.network().getVolume(player);
            player.sendMessage(
                    ChatColor.GOLD + "[VibesInDev] "
                            + ChatColor.YELLOW + "Current volume: "
                            + ChatColor.AQUA + Math.round(current * 100) + "%"
                            + ChatColor.GRAY + " (Use " + ChatColor.WHITE + "/music volume <0-100>" + ChatColor.GRAY + " to change)"
            );
            return;
        }

        try {
            int percent = Integer.parseInt(args[1]);
            if (percent < 0 || percent > 100) {
                player.sendMessage(
                        ChatColor.RED + "Volume must be between 0 and 100."
                );
                return;
            }

            float newVol = percent / 100.0F;
            plugin.network().setVolume(player, newVol);

            player.sendMessage(
                    ChatColor.GOLD + "[VibesInDev] "
                            + ChatColor.GREEN + "Volume set to "
                            + ChatColor.AQUA + percent + "%"
                            + ChatColor.GRAY + " (saved persistently)"
            );

        } catch (NumberFormatException e) {
            player.sendMessage(
                    ChatColor.RED + "Invalid volume percentage: " + args[1]
            );
        }
    }

    private void status(Player player) {
        if (!player.hasPermission("vibesindev.admin")) {
            deny(player);
            return;
        }

        player.sendMessage(ChatColor.GOLD + "=== VibesInDev Diagnostics ===");
        player.sendMessage(ChatColor.YELLOW + "Plugin Version: " + ChatColor.WHITE + plugin.getDescription().getVersion());
        player.sendMessage(ChatColor.YELLOW + "Server Version: " + ChatColor.WHITE + plugin.getServer().getVersion());
        player.sendMessage(ChatColor.YELLOW + "Active External Processes: " + ChatColor.WHITE + dev.vibesindev.vibesmusic.util.ProcessRunner.getActiveProcesses().size());
        
        long freeMemory = Runtime.getRuntime().freeMemory() / 1024 / 1024;
        long totalMemory = Runtime.getRuntime().totalMemory() / 1024 / 1024;
        player.sendMessage(ChatColor.YELLOW + "Memory: " + ChatColor.WHITE + freeMemory + "MB free / " + totalMemory + "MB total");

        player.sendMessage(ChatColor.GOLD + "--- Metrics ---");
        player.sendMessage(ChatColor.YELLOW + "Tracks Requested: " + ChatColor.WHITE + plugin.metrics().getTracksRequested());
        player.sendMessage(ChatColor.YELLOW + "Tracks Played: " + ChatColor.WHITE + plugin.metrics().getTracksPlayed());
        player.sendMessage(ChatColor.YELLOW + "Tracks Completed: " + ChatColor.WHITE + plugin.metrics().getTracksCompleted());
        player.sendMessage(ChatColor.YELLOW + "Tracks Failed: " + ChatColor.WHITE + plugin.metrics().getTracksFailed());
        player.sendMessage(ChatColor.YELLOW + "Cache Hits: " + ChatColor.WHITE + plugin.metrics().getCacheHits());
        player.sendMessage(ChatColor.YELLOW + "Cache Misses: " + ChatColor.WHITE + plugin.metrics().getCacheMisses());
        player.sendMessage(ChatColor.YELLOW + "yt-dlp Failures: " + ChatColor.WHITE + plugin.metrics().getYtDlpFailures());
        player.sendMessage(ChatColor.YELLOW + "FFmpeg Failures: " + ChatColor.WHITE + plugin.metrics().getFfmpegFailures());
        player.sendMessage(ChatColor.YELLOW + "Disconnects: " + ChatColor.WHITE + plugin.metrics().getDisconnects());
    }

    private void debug(Player player, String[] args) {
        if (!player.hasPermission("vibesindev.admin")) {
            deny(player);
            return;
        }

        Player target = args.length > 1 ? Bukkit.getPlayer(args[1]) : player;
        if (target == null) {
            player.sendMessage(ChatColor.RED + "Player not found.");
            return;
        }

        player.sendMessage(ChatColor.GOLD + "=== Debug: " + target.getName() + " ===");

        // [Playback]
        player.sendMessage(ChatColor.YELLOW + "[Playback]");
        dev.vibesindev.vibesmusic.playback.PlaybackSession session = plugin.playback().getSession(target.getUniqueId());
        
        if (session != null) {
            player.sendMessage("player=" + target.getName());
            player.sendMessage("track=" + (session.getTrack() != null ? session.getTrack().id() : "null"));
            
            if (session.getTrack() != null) {
                long posS = session.getPositionMs() / 1000;
                long h = posS / 3600;
                long m = (posS % 3600) / 60;
                long s = posS % 60;
                String posStr = h > 0 ? String.format("%02d:%02d:%02d", h, m, s) : String.format("%02d:%02d", m, s);
                player.sendMessage("position=" + posStr + " / " + session.getTrack().durationText());
            }

            player.sendMessage("session=" + session.getSessionId());
            player.sendMessage("state=" + session.getState().name());
        } else {
            player.sendMessage("No active playback session.");
        }

        // [Queue]
        player.sendMessage(ChatColor.YELLOW + "\n[Queue]");
        dev.vibesindev.vibesmusic.model.Track current = plugin.queues().current(target.getUniqueId());
        java.util.List<dev.vibesindev.vibesmusic.model.Track> q = plugin.queues().list(target.getUniqueId());
        player.sendMessage("current=" + (current != null ? current.id() : "null"));
        player.sendMessage("next=" + (!q.isEmpty() ? q.get(0).id() : "null"));
        player.sendMessage("size=" + q.size());
        player.sendMessage("repeat=" + plugin.queues().getRepeatMode(target.getUniqueId()).name());
        player.sendMessage("shuffle=" + plugin.queues().isShuffle(target.getUniqueId()));

        // [Party]
        player.sendMessage(ChatColor.YELLOW + "\n[Party]");
        dev.vibesindev.vibesmusic.party.MusicParty party = plugin.parties().getPartyByPlayer(target.getUniqueId());
        if (party != null) {
            player.sendMessage("party=" + party.getPartyId());
            player.sendMessage("members=" + party.getMembers().size());
            player.sendMessage("position=" + (party.getCurrentTrack() != null ? party.getCurrentTrack().id() : "null"));
        } else {
            player.sendMessage("No active party.");
        }

        // [Stream]
        player.sendMessage(ChatColor.YELLOW + "\n[Stream]");
        player.sendMessage("Connection: " + (plugin.network().isSupported(target) ? "CONNECTED" : "DISCONNECTED"));
        if (session != null && session.getStreamId() != null) {
            player.sendMessage("session=" + session.getSessionId());
            player.sendMessage("stream=" + session.getStreamId());
            player.sendMessage("state=" + (plugin.network().isPlaying(target) ? "ACTIVE" : "INACTIVE"));
        } else {
            player.sendMessage("No active stream.");
        }
    }

    // ============================================================
    // PARTY COMMANDS
    // ============================================================

    private void playlistCommand(Player player, String[] args) {
        if (args.length < 2) {
            plugin.playlistsMenu().open(player);
            return;
        }

        String sub = args[1].toLowerCase(java.util.Locale.ROOT);
        switch (sub) {
            case "save" -> {
                if (args.length < 3) {
                    player.sendMessage(ChatColor.RED + "Usage: /music playlist save <name>");
                    return;
                }
                String name = args[2];
                java.util.List<Track> tracks = new java.util.ArrayList<>();
                MusicParty party = plugin.parties().getPartyByPlayer(player.getUniqueId());
                if (party != null) {
                    if (party.getCurrentTrack() != null) {
                        tracks.add(party.getCurrentTrack());
                    }
                    tracks.addAll(party.getQueue());
                } else {
                    Track current = plugin.queues().current(player.getUniqueId());
                    if (current != null) {
                        tracks.add(current);
                    }
                    tracks.addAll(plugin.queues().list(player.getUniqueId()));
                }

                if (tracks.isEmpty()) {
                    player.sendMessage(ChatColor.RED + "Your queue is empty! Play or add songs first to save a playlist.");
                    return;
                }

                plugin.playlists().savePlaylist(player.getUniqueId(), name, tracks);
                player.sendMessage(ChatColor.GOLD + "[VibesInDev] " + ChatColor.GREEN + "Saved playlist '" 
                        + ChatColor.AQUA + name + ChatColor.GREEN + "' with " + tracks.size() + " songs!");
            }
            case "play", "load" -> {
                if (args.length < 3) {
                    player.sendMessage(ChatColor.RED + "Usage: /music playlist play <name>");
                    return;
                }
                String name = args[2];
                var pl = plugin.playlists().getPlaylist(player.getUniqueId(), name);
                if (pl == null) {
                    player.sendMessage(ChatColor.RED + "No playlist found named '" + name + "'. Use /music playlist list.");
                    return;
                }
                plugin.playback().playCustomPlaylist(player, pl.name(), pl.tracks());
            }
            case "list" -> {
                var playlists = plugin.playlists().getPlaylists(player.getUniqueId());
                if (playlists.isEmpty()) {
                    player.sendMessage(ChatColor.YELLOW + "You have no saved playlists! Use /music playlist save <name>.");
                    return;
                }
                player.sendMessage(ChatColor.GOLD + "=== Your Playlists (" + playlists.size() + ") ===");
                for (var pl : playlists.values()) {
                    player.sendMessage(ChatColor.YELLOW + "• " + ChatColor.AQUA + pl.name() 
                            + ChatColor.GRAY + " (" + pl.tracks().size() + " tracks, " + plugin.playlists().formatTotalDuration(pl.tracks()) + ")"
                            + ChatColor.DARK_GRAY + " - " + ChatColor.GREEN + "/music playlist play " + pl.name());
                }
            }
            case "delete", "remove" -> {
                if (args.length < 3) {
                    player.sendMessage(ChatColor.RED + "Usage: /music playlist delete <name>");
                    return;
                }
                String name = args[2];
                boolean deleted = plugin.playlists().deletePlaylist(player.getUniqueId(), name);
                if (deleted) {
                    player.sendMessage(ChatColor.GOLD + "[VibesInDev] " + ChatColor.GREEN + "Deleted playlist '" + name + "'.");
                } else {
                    player.sendMessage(ChatColor.RED + "No playlist found named '" + name + "'.");
                }
            }
            default -> {
                player.sendMessage(ChatColor.GOLD + "=== Playlist Help ===");
                player.sendMessage(ChatColor.YELLOW + "/music playlist" + ChatColor.GRAY + " - Open playlists GUI");
                player.sendMessage(ChatColor.YELLOW + "/music playlist save <name>" + ChatColor.GRAY + " - Save current songs as playlist");
                player.sendMessage(ChatColor.YELLOW + "/music playlist play <name>" + ChatColor.GRAY + " - Play saved playlist");
                player.sendMessage(ChatColor.YELLOW + "/music playlist list" + ChatColor.GRAY + " - List your playlists");
                player.sendMessage(ChatColor.YELLOW + "/music playlist delete <name>" + ChatColor.GRAY + " - Delete a playlist");
            }
        }
    }

    private void partyCommand(
            Player player,
            String[] args
    ) {
        if (args.length < 2) {
            partyHelp(player);
            return;
        }

        String action =
                args[1].toLowerCase();

        switch (action) {
            case "create" ->
                    partyCreate(player);

            case "invite" ->
                    partyInvite(player, args);

            case "accept" ->
                    partyAccept(player);

            case "leave" ->
                    partyLeave(player);

            case "disband" ->
                    partyDisband(player);

            case "members" ->
                    partyMembers(player);

            case "info" ->
                    partyInfo(player);

            case "kick" ->
                    partyKick(player, args);

            case "transfer" ->
                    partyTransfer(player, args);

            case "play" ->
                    partyPlay(player, args);

            case "pause" ->
                    plugin.playback()
                            .partyPause(player);

            case "resume" ->
                    plugin.playback()
                            .partyResume(player);

            case "skip" ->
                    plugin.playback()
                            .partyNext(player);

            case "seek" ->
                    partySeek(player, args);

            case "stop" ->
                    plugin.playback()
                            .partyStop(player);

            case "queue" ->
                    partyQueue(player);

            case "shuffle" ->
                    partyShuffle(player);

            case "repeat" ->
                    partyRepeat(player, args);

            default ->
                    partyHelp(player);
        }
    }

    private void partyCreate(
            Player player
    ) {
        if (!player.hasPermission("vibesindev.party.create")) {
            deny(player);
            return;
        }
        MusicParty existing =
                plugin.parties()
                        .getPartyByPlayer(
                                player.getUniqueId()
                        );

        if (existing != null) {
            player.sendMessage(
                    ChatColor.YELLOW
                            + "You are already in a party."
            );
            return;
        }

        MusicParty party =
                plugin.parties()
                        .createParty(
                                player.getUniqueId()
                        );

        player.sendMessage(
                ChatColor.GREEN
                        + "✓ "
                        + ChatColor.WHITE
                        + "Party created."
        );

        player.sendMessage(
                ChatColor.GRAY
                        + "Party ID: "
                        + ChatColor.AQUA
                        + party.getPartyId()
        );
    }

    private void partyInvite(
            Player player,
            String[] args
    ) {
        if (!player.hasPermission("vibesindev.party.manage")) {
            deny(player);
            return;
        }
        if (args.length < 3) {
            player.sendMessage(
                    ChatColor.YELLOW
                            + "Usage: /music party invite <player>"
            );
            return;
        }

        MusicParty party =
                plugin.parties()
                        .getPartyByPlayer(
                                player.getUniqueId()
                        );

        /*
         * Automatically create a party when the player
         * invites someone while not already in a party.
         */
        if (party == null) {
            party =
                    plugin.parties()
                            .createParty(
                                    player.getUniqueId()
                            );

            player.sendMessage(
                    ChatColor.GREEN
                            + "✓ "
                            + ChatColor.WHITE
                            + "Party created."
            );
        }

        if (!party.isOwner(
                player.getUniqueId()
        )) {
            player.sendMessage(
                    ChatColor.RED
                            + "Only the party owner can invite players."
            );
            return;
        }

        Player target =
                Bukkit.getPlayerExact(args[2]);

        if (target == null
                || !target.isOnline()) {
            player.sendMessage(
                    ChatColor.RED
                            + "That player is not online."
            );
            return;
        }

        if (target.getUniqueId().equals(
                player.getUniqueId()
        )) {
            player.sendMessage(
                    ChatColor.YELLOW
                            + "You are already in the party."
            );
            return;
        }

        if (plugin.parties().hasParty(
                target.getUniqueId()
        )) {
            player.sendMessage(
                    ChatColor.YELLOW
                            + target.getName()
                            + " is already in a party."
            );
            return;
        }

        if (!plugin.parties().invite(
                player.getUniqueId(),
                target.getUniqueId()
        )) {
            player.sendMessage(
                    ChatColor.RED
                            + "Could not invite that player."
            );
            return;
        }

        player.sendMessage(
                ChatColor.GREEN
                        + "✓ "
                        + ChatColor.WHITE
                        + "Invited "
                        + ChatColor.AQUA
                        + target.getName()
                        + ChatColor.WHITE
                        + "."
        );

        TextComponent invitation =
                new TextComponent(
                        ChatColor.LIGHT_PURPLE
                                + "♫ "
                                + ChatColor.WHITE
                                + player.getName()
                                + ChatColor.GRAY
                                + " invited you to their music party. "
                );

        TextComponent join =
                new TextComponent(
                        ChatColor.AQUA
                                + ChatColor.UNDERLINE.toString()
                                + "CLICK TO JOIN"
                );

        join.setClickEvent(
                new ClickEvent(
                        ClickEvent.Action.RUN_COMMAND,
                        "/music party accept"
                )
        );

        join.setHoverEvent(
                new HoverEvent(
                        HoverEvent.Action.SHOW_TEXT,
                        new Text(
                                ChatColor.GREEN
                                        + "Click to join the party"
                        )
                )
        );

        invitation.addExtra(join);

        target.spigot().sendMessage(
                invitation
        );
    }

    private void partyAccept(
            Player player
    ) {
        MusicParty invited =
                plugin.parties()
                        .getInvitedParty(
                                player.getUniqueId()
                        );

        if (invited == null) {
            player.sendMessage(
                    ChatColor.YELLOW
                            + "You do not have a pending party invitation, or it has expired."
            );
            return;
        }

        if (!plugin.parties().acceptInvitation(
                player.getUniqueId()
        )) {
            player.sendMessage(
                    ChatColor.RED
                            + "Could not accept the party invitation. It may have expired."
            );
            return;
        }

        player.sendMessage(
                ChatColor.GREEN
                        + "✓ "
                        + ChatColor.WHITE
                        + "You joined the music party."
        );

        for (UUID member : invited.getMembers()) {
            Player target = Bukkit.getPlayer(member);
            if (target != null && target.isOnline()
                    && !target.getUniqueId().equals(player.getUniqueId())) {
                target.sendMessage(
                        ChatColor.GREEN
                                + "♫ "
                                + ChatColor.AQUA
                                + player.getName()
                                + ChatColor.WHITE
                                + " joined the party."
                );
            }
        }
    }

    private void partyLeave(
            Player player
    ) {
        MusicParty party =
                plugin.parties()
                        .getPartyByPlayer(
                                player.getUniqueId()
                        );

        if (party == null) {
            player.sendMessage(
                    ChatColor.YELLOW
                            + "You are not in a party."
            );
            return;
        }

        List<UUID> members = List.copyOf(party.getMembers());

        if (plugin.network().isSupported(player)) {
            plugin.network().stop(player);
        }

        MusicPartyManager.PartyLeaveResult result =
                plugin.parties().leavePartyDetailed(
                        player.getUniqueId()
                );

        player.sendMessage(
                ChatColor.GREEN
                        + "✓ "
                        + ChatColor.WHITE
                        + "You left the music party."
        );

        if (result == null) {
            return;
        }

        if (result.newOwner() != null) {
            Player newOwner = Bukkit.getPlayer(result.newOwner());
            if (newOwner != null && newOwner.isOnline()) {
                newOwner.sendMessage(
                        ChatColor.GREEN
                                + "♫ You are now the party owner."
                );
            }
        }

        for (UUID member : members) {
            if (member.equals(player.getUniqueId())) {
                continue;
            }

            Player target = Bukkit.getPlayer(member);
            if (target != null && target.isOnline()) {
                if (result.newOwner() != null) {
                    Player newOwner =
                            Bukkit.getPlayer(result.newOwner());

                    String newOwnerName =
                            newOwner != null
                                    ? newOwner.getName()
                                    : result.newOwner().toString();

                    target.sendMessage(
                            ChatColor.YELLOW
                                    + "♫ "
                                    + player.getName()
                                    + " left. Ownership transferred to "
                                    + ChatColor.AQUA
                                    + newOwnerName
                                    + ChatColor.YELLOW
                                    + "."
                    );
                } else {
                    target.sendMessage(
                            ChatColor.GRAY
                                    + "♫ "
                                    + player.getName()
                                    + " left the party."
                    );
                }
            }
        }
    }

    private void partyDisband(
            Player player
    ) {
        if (!player.hasPermission("vibesindev.party.manage")) {
            deny(player);
            return;
        }
        MusicParty party =
                plugin.parties()
                        .getPartyByPlayer(
                                player.getUniqueId()
                        );

        if (party == null) {
            player.sendMessage(
                    ChatColor.YELLOW
                            + "You are not in a party."
            );
            return;
        }

        if (!party.isOwner(
                player.getUniqueId()
        )) {
            player.sendMessage(
                    ChatColor.RED
                            + "Only the party owner can disband it."
            );
            return;
        }

        plugin.playback()
                .partyStop(player);

        List<UUID> members =
                party.getMembers();

        if (plugin.parties()
                .disbandParty(
                        player.getUniqueId()
                )) {

            for (UUID member : members) {
                Player target =
                        Bukkit.getPlayer(member);

                if (target != null
                        && target.isOnline()
                        && !target.getUniqueId()
                        .equals(player.getUniqueId())) {
                    target.sendMessage(
                            ChatColor.YELLOW
                                    + "The music party was disbanded."
                    );
                }
            }

            player.sendMessage(
                    ChatColor.GREEN
                            + "✓ "
                            + ChatColor.WHITE
                            + "Party disbanded."
            );
        } else {
            player.sendMessage(
                    ChatColor.RED
                            + "Could not disband the party."
            );
        }
    }

    private void partyInfo(
            Player player
    ) {
        if (plugin.parties().getPartyByPlayer(player.getUniqueId()) == null) {
            player.sendMessage(
                    ChatColor.YELLOW
                            + "You are not in a party."
            );
            return;
        }

        plugin.partyInfoMenu().open(player);
    }

    private void partyKick(
            Player player,
            String[] args
    ) {
        if (!player.hasPermission("vibesindev.party.kick")) {
            deny(player);
            return;
        }
        if (args.length < 3) {
            player.sendMessage(
                    ChatColor.YELLOW
                            + "Usage: /music party kick <player>"
            );
            return;
        }

        MusicParty party =
                plugin.parties()
                        .getPartyByPlayer(
                                player.getUniqueId()
                        );

        if (party == null) {
            player.sendMessage(
                    ChatColor.YELLOW
                            + "You are not in a party."
            );
            return;
        }

        if (!party.isOwner(
                player.getUniqueId()
        )) {
            player.sendMessage(
                    ChatColor.RED
                            + "Only the party owner can kick players."
            );
            return;
        }

        Player target =
                Bukkit.getPlayerExact(args[2]);

        if (target == null
                || !target.isOnline()) {
            player.sendMessage(
                    ChatColor.RED
                            + "That player is not online."
            );
            return;
        }

        if (!party.hasMember(
                target.getUniqueId()
        )) {
            player.sendMessage(
                    ChatColor.YELLOW
                            + target.getName()
                            + " is not in your party."
            );
            return;
        }

        if (!plugin.parties().kickMember(
                player.getUniqueId(),
                target.getUniqueId()
        )) {
            player.sendMessage(
                    ChatColor.RED
                            + "Could not kick that player."
            );
            return;
        }

        /*
         * Stop their client-side party audio immediately.
         */
        if (plugin.network().isSupported(target)) {
            plugin.network().stop(target);
        }

        player.sendMessage(
                ChatColor.GREEN
                        + "✓ "
                        + ChatColor.WHITE
                        + "Kicked "
                        + ChatColor.AQUA
                        + target.getName()
                        + ChatColor.WHITE
                        + "."
        );

        target.sendMessage(
                ChatColor.RED
                        + "You were removed from the music party."
        );
    }

    private void partyTransfer(
            Player player,
            String[] args
    ) {
        if (!player.hasPermission("vibesindev.party.transfer")) {
            deny(player);
            return;
        }
        if (args.length < 3) {
            player.sendMessage(
                    ChatColor.YELLOW
                            + "Usage: /music party transfer <player>"
            );
            return;
        }

        MusicParty party =
                plugin.parties()
                        .getPartyByPlayer(
                                player.getUniqueId()
                        );

        if (party == null) {
            player.sendMessage(
                    ChatColor.YELLOW
                            + "You are not in a party."
            );
            return;
        }

        if (!party.isOwner(
                player.getUniqueId()
        )) {
            player.sendMessage(
                    ChatColor.RED
                            + "Only the party owner can transfer ownership."
            );
            return;
        }

        Player target =
                Bukkit.getPlayerExact(args[2]);

        if (target == null
                || !target.isOnline()) {
            player.sendMessage(
                    ChatColor.RED
                            + "That player is not online."
            );
            return;
        }

        if (!party.hasMember(
                target.getUniqueId()
        )) {
            player.sendMessage(
                    ChatColor.YELLOW
                            + target.getName()
                            + " is not in your party."
            );
            return;
        }

        if (!plugin.parties().transferOwnership(
                player.getUniqueId(),
                target.getUniqueId()
        )) {
            player.sendMessage(
                    ChatColor.RED
                            + "Could not transfer party ownership."
            );
            return;
        }

        player.sendMessage(
                ChatColor.GREEN
                        + "✓ "
                        + ChatColor.WHITE
                        + "Party ownership transferred to "
                        + ChatColor.AQUA
                        + target.getName()
                        + ChatColor.WHITE
                        + "."
        );

        target.sendMessage(
                ChatColor.GREEN
                        + "✓ "
                        + ChatColor.WHITE
                        + "You are now the party owner."
        );
    }

    private void partyMembers(
            Player player
    ) {
        MusicParty party =
                plugin.parties()
                        .getPartyByPlayer(
                                player.getUniqueId()
                        );

        if (party == null) {
            player.sendMessage(
                    ChatColor.YELLOW
                            + "You are not in a party."
            );
            return;
        }

        player.sendMessage(
                ChatColor.LIGHT_PURPLE
                        + "♫ Party Members"
        );

        for (UUID member : party.getMembers()) {
            Player target =
                    Bukkit.getPlayer(member);

            String name =
                    target != null
                            ? target.getName()
                            : member.toString();

            String owner =
                    party.isOwner(member)
                            ? ChatColor.GOLD
                            + " [Owner]"
                            : "";

            player.sendMessage(
                    ChatColor.GRAY
                            + "• "
                            + ChatColor.WHITE
                            + name
                            + owner
            );
        }
    }

    private void partyPlay(
            Player player,
            String[] args
    ) {
        if (args.length < 3) {
            player.sendMessage(
                    ChatColor.YELLOW
                            + "Usage: /music party play <song name or URL>"
            );
            return;
        }

        String input =
                String.join(
                        " ",
                        Arrays.copyOfRange(
                                args,
                                2,
                                args.length
                        )
                ).trim();

        plugin.playback()
                .playPartyInput(
                        player,
                        input
                );
    }

    private void partyQueue(
            Player player
    ) {
        MusicParty party =
                plugin.parties()
                        .getPartyByPlayer(
                                player.getUniqueId()
                        );

        if (party == null) {
            player.sendMessage(
                    ChatColor.YELLOW
                            + "You are not in a party."
            );
            return;
        }

        player.sendMessage(
                ChatColor.LIGHT_PURPLE
                        + "♫ Party Queue"
        );

        Track current =
                party.getCurrentTrack();

        if (current != null) {
            player.sendMessage(
                    ChatColor.GREEN
                            + "Now: "
                            + ChatColor.WHITE
                            + current.title()
            );
        }

        List<Track> queue =
                party.getQueue();

        if (queue.isEmpty()) {
            player.sendMessage(
                    ChatColor.GRAY
                            + "Party queue is empty."
            );
            return;
        }

        for (int i = 0;
             i < queue.size();
             i++) {

            Track track =
                    queue.get(i);

            player.sendMessage(
                    ChatColor.GRAY
                            + String.valueOf(i + 1)
                            + ". "
                            + ChatColor.WHITE
                            + track.title()
                            + ChatColor.DARK_GRAY
                            + " — "
                            + ChatColor.GRAY
                            + track.artist()
            );
        }
    }

    private void partyShuffle(
            Player player
    ) {
        if (!player.hasPermission("vibesindev.party.manage")) {
            deny(player);
            return;
        }
        MusicParty party =
                plugin.parties()
                        .getPartyByPlayer(
                                player.getUniqueId()
                        );

        if (party == null) {
            player.sendMessage(
                    ChatColor.YELLOW
                            + "You are not in a party."
            );
            return;
        }

        if (!party.isOwner(
                player.getUniqueId()
        )) {
            player.sendMessage(
                    ChatColor.RED
                            + "Only the party owner can change shuffle."
            );
            return;
        }

        boolean enabled =
                !party.isShuffle();

        party.setShuffle(enabled);

        String shuffleMsg = ChatColor.LIGHT_PURPLE + "[Party] " + ChatColor.GREEN + player.getName()
                + (enabled ? " enabled shuffle." : " disabled shuffle.");
        player.sendMessage(shuffleMsg);
        plugin.playback().broadcastToParty(party, shuffleMsg, player.getUniqueId());
    }

    private void partyRepeat(
            Player player,
            String[] args
    ) {
        if (!player.hasPermission("vibesindev.party.manage")) {
            deny(player);
            return;
        }
        MusicParty party =
                plugin.parties()
                        .getPartyByPlayer(
                                player.getUniqueId()
                        );

        if (party == null) {
            player.sendMessage(
                    ChatColor.YELLOW
                            + "You are not in a party."
            );
            return;
        }

        if (!party.isOwner(
                player.getUniqueId()
        )) {
            player.sendMessage(
                    ChatColor.RED
                            + "Only the party owner can change repeat mode."
            );
            return;
        }

        if (args.length < 3) {
            player.sendMessage(
                    ChatColor.YELLOW
                            + "Usage: /music party repeat <off|one|all>"
            );
            return;
        }

        MusicParty.RepeatMode mode;

        switch (args[2].toLowerCase()) {
            case "off" ->
                    mode = MusicParty.RepeatMode.OFF;

            case "one" ->
                    mode = MusicParty.RepeatMode.ONE;

            case "all" ->
                    mode = MusicParty.RepeatMode.ALL;

            default -> {
                player.sendMessage(
                        ChatColor.YELLOW
                                + "Use off, one, or all."
                );
                return;
            }
        }

        party.setRepeatMode(mode);

        String repeatMsg = ChatColor.LIGHT_PURPLE + "[Party] " + ChatColor.GREEN + player.getName()
                + " set repeat to " + ChatColor.WHITE + mode.name() + ChatColor.GREEN + ".";
        player.sendMessage(repeatMsg);
        plugin.playback().broadcastToParty(party, repeatMsg, player.getUniqueId());
    }

    private void partyHelp(
            Player player
    ) {
        player.sendMessage(
                ChatColor.LIGHT_PURPLE
                        + "♫ VibesInDev Party"
        );

        player.sendMessage(
                ChatColor.GRAY
                        + "/music party create"
        );

        player.sendMessage(
                ChatColor.GRAY
                        + "/music party invite <player>"
        );

        player.sendMessage(
                ChatColor.GRAY
                        + "/music party accept"
        );

        player.sendMessage(
                ChatColor.GRAY
                        + "/music party leave"
        );

        player.sendMessage(
                ChatColor.GRAY
                        + "/music party disband"
        );

        player.sendMessage(
                ChatColor.GRAY
                        + "/music party members"
        );

        player.sendMessage(
                ChatColor.GRAY
                        + "/music party info"
        );

        player.sendMessage(
                ChatColor.GRAY
                        + "/music party kick <player>"
        );

        player.sendMessage(
                ChatColor.GRAY
                        + "/music party transfer <player>"
        );

        player.sendMessage(
                ChatColor.GRAY
                        + "/music party play <song name or URL>"
        );

        player.sendMessage(
                ChatColor.GRAY
                        + "/music party pause"
        );

        player.sendMessage(
                ChatColor.GRAY
                        + "/music party resume"
        );

        player.sendMessage(
                ChatColor.GRAY
                        + "/music party skip"
        );

        player.sendMessage(
                ChatColor.GRAY
                        + "/music party stop"
        );

        player.sendMessage(
                ChatColor.GRAY
                        + "/music party queue"
        );

        player.sendMessage(
                ChatColor.GRAY
                        + "/music party shuffle"
        );

        player.sendMessage(
                ChatColor.GRAY
                        + "/music party repeat <off|one|all>"
        );
    }

    private void request(
            Player player,
            String[] args
    ) {
        if (!player.hasPermission("vibesindev.play")) {
            deny(player);
            return;
        }

        if (args.length < 2) {

            player.sendMessage(
                    ChatColor.YELLOW
                            + "Usage: /music play <song name or URL>"
            );

            return;
        }


        String input =
                String.join(
                        " ",
                        Arrays.copyOfRange(
                                args,
                                1,
                                args.length
                        )
                );


        plugin.playback()
                .playInput(
                        player,
                        input
                );
    }

    private void nowPlaying(
            Player player
    ) {
        MusicParty party =
                plugin.parties()
                        .getPartyByPlayer(
                                player.getUniqueId()
                        );

        if (party != null) {
            Track track =
                    party.getCurrentTrack();

            if (track == null) {
                player.sendMessage(
                        ChatColor.YELLOW
                                + "No party music is currently playing."
                );
                return;
            }

            player.sendMessage(
                    ChatColor.LIGHT_PURPLE
                            + "♫ Party Now Playing"
            );

            player.sendMessage(
                    ChatColor.WHITE
                            + track.title()
            );

            player.sendMessage(
                    ChatColor.GRAY
                            + "Artist: "
                            + ChatColor.WHITE
                            + track.artist()
            );

            player.sendMessage(
                    ChatColor.GRAY
                            + "Duration: "
                            + ChatColor.WHITE
                            + track.durationText()
            );

            return;
        }

        Track track =
                plugin.playback()
                        .current(player);

        if (track == null) {
            player.sendMessage(
                    ChatColor.YELLOW
                            + "Nothing is currently playing."
            );
            return;
        }

        player.sendMessage(
                ChatColor.AQUA
                        + "♫ Now Playing"
        );

        player.sendMessage(
                ChatColor.WHITE
                        + track.title()
        );

        player.sendMessage(
                ChatColor.GRAY
                        + "Artist: "
                        + ChatColor.WHITE
                        + track.artist()
        );

        player.sendMessage(
                ChatColor.GRAY
                        + "Duration: "
                        + ChatColor.WHITE
                        + track.durationText()
        );
    }

    private void shuffle(Player player) {
        boolean isShuffle = !plugin.queues().isShuffle(player.getUniqueId());
        plugin.queues().setShuffle(player.getUniqueId(), isShuffle);
        player.sendMessage(ChatColor.GREEN + "Queue shuffle is now " + (isShuffle ? "ON" : "OFF") + ".");
    }

    private void repeat(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(ChatColor.YELLOW + "Usage: /music repeat <off|track|queue>");
            return;
        }
        
        dev.vibesindev.vibesmusic.queue.RepeatMode mode;
        switch (args[1].toLowerCase()) {
            case "off" -> mode = dev.vibesindev.vibesmusic.queue.RepeatMode.OFF;
            case "track", "one" -> mode = dev.vibesindev.vibesmusic.queue.RepeatMode.TRACK;
            case "queue", "all" -> mode = dev.vibesindev.vibesmusic.queue.RepeatMode.QUEUE;
            default -> {
                player.sendMessage(ChatColor.YELLOW + "Use off, track, or queue.");
                return;
            }
        }
        
        plugin.queues().setRepeatMode(player.getUniqueId(), mode);
        player.sendMessage(ChatColor.GREEN + "Repeat mode set to " + mode.name() + ".");
    }

    private void queue(
            Player player,
            String[] args
    ) {
        if (!player.hasPermission("vibesindev.queue")) {
            deny(player);
            return;
        }
        if (args.length > 1) {
            String action = args[1].toLowerCase();
            switch (action) {
                case "add":
                    // /music queue add <url/search>
                    if (args.length < 3) {
                        player.sendMessage(ChatColor.YELLOW + "Usage: /music queue add <song>");
                        return;
                    }
                    String[] playArgs = new String[args.length - 1];
                    playArgs[0] = "play";
                    System.arraycopy(args, 2, playArgs, 1, args.length - 2);
                    request(player, playArgs);
                    return;
                case "remove":
                    // /music queue remove <position>
                    if (args.length < 3) {
                        player.sendMessage(ChatColor.YELLOW + "Usage: /music queue remove <position>");
                        return;
                    }
                    try {
                        int index = Integer.parseInt(args[2]) - 1;
                        if (plugin.queues().remove(player.getUniqueId(), index)) {
                            player.sendMessage(ChatColor.GREEN + "Removed track from queue.");
                        } else {
                            player.sendMessage(ChatColor.RED + "Invalid queue position.");
                        }
                    } catch (NumberFormatException e) {
                        player.sendMessage(ChatColor.RED + "Position must be a number.");
                    }
                    return;
                case "move":
                    if (args.length < 4) {
                        player.sendMessage(ChatColor.YELLOW + "Usage: /music queue move <from> <to>");
                        return;
                    }
                    try {
                        int from = Integer.parseInt(args[2]) - 1;
                        int to = Integer.parseInt(args[3]) - 1;
                        if (plugin.queues().move(player.getUniqueId(), from, to)) {
                            player.sendMessage(ChatColor.GREEN + "Moved track in queue.");
                        } else {
                            player.sendMessage(ChatColor.RED + "Invalid queue position.");
                        }
                    } catch (NumberFormatException e) {
                        player.sendMessage(ChatColor.RED + "Positions must be numbers.");
                    }
                    return;
                case "clear":
                    plugin.queues().clearQueue(player.getUniqueId());
                    player.sendMessage(ChatColor.GREEN + "Queue cleared.");
                    return;
            }
        }

        MusicParty party =
                plugin.parties()
                        .getPartyByPlayer(
                                player.getUniqueId()
                        );

        if (party != null) {
            player.sendMessage(
                    ChatColor.LIGHT_PURPLE
                            + "♫ Party Queue"
            );

            Track current =
                    party.getCurrentTrack();

            if (current != null) {
                player.sendMessage(
                        ChatColor.GREEN
                                + "Now: "
                                + ChatColor.WHITE
                                + current.title()
                );
            }

            List<Track> queue =
                    party.getQueue();

            if (queue.isEmpty()) {
                player.sendMessage(
                        ChatColor.GRAY
                                + "Party queue is empty."
                );
                return;
            }

            for (int i = 0;
                 i < queue.size();
                 i++) {

                Track track =
                        queue.get(i);

                player.sendMessage(
                        ChatColor.GRAY
                                + String.valueOf(i + 1)
                                + ". "
                                + ChatColor.WHITE
                                + track.title()
                                + ChatColor.DARK_GRAY
                                + " — "
                                + ChatColor.GRAY
                                + track.artist()
                );
            }

            return;
        }

        List<Track> queue =
                plugin.queues()
                        .list(
                                player.getUniqueId()
                        );

        Track current =
                plugin.playback()
                        .current(player);

        player.sendMessage(
                ChatColor.AQUA
                        + "♫ Music Queue"
        );

        if (current != null) {
            player.sendMessage(
                    ChatColor.GREEN
                            + "Now: "
                            + ChatColor.WHITE
                            + current.title()
            );
        }

        if (queue.isEmpty()) {
            player.sendMessage(
                    ChatColor.GRAY
                            + "Queue is empty."
            );
            return;
        }

        for (int i = 0;
             i < queue.size();
             i++) {

            Track track =
                    queue.get(i);

            player.sendMessage(
                    ChatColor.GRAY
                            + String.valueOf(i + 1)
                            + ". "
                            + ChatColor.WHITE
                            + track.title()
                            + ChatColor.DARK_GRAY
                            + " — "
                            + ChatColor.GRAY
                            + track.artist()
            );
        }
    }

    private long parseTime(String time) {
        if (time.contains(":")) {
            String[] parts = time.split(":");
            if (parts.length == 2) {
                try {
                    long m = Long.parseLong(parts[0]);
                    long s = Long.parseLong(parts[1]);
                    return (m * 60 + s) * 1000;
                } catch (NumberFormatException ignored) {}
            } else if (parts.length == 3) {
                try {
                    long h = Long.parseLong(parts[0]);
                    long m = Long.parseLong(parts[1]);
                    long s = Long.parseLong(parts[2]);
                    return (h * 3600 + m * 60 + s) * 1000;
                } catch (NumberFormatException ignored) {}
            }
        } else {
            try {
                long s = Long.parseLong(time.replace("s", ""));
                return s * 1000;
            } catch (NumberFormatException ignored) {}
        }
        return -1;
    }

    private void seek(Player player, String[] args) {
        if (!player.hasPermission("vibesindev.seek")) {
            deny(player);
            return;
        }
        if (args.length < 2) {
            player.sendMessage(ChatColor.RED + "Usage: /music seek <time>");
            return;
        }

        long ms = parseTime(args[1]);
        if (ms < 0) {
            player.sendMessage(ChatColor.RED + "Invalid time format. Use seconds or MM:SS.");
            return;
        }

        if (plugin.parties().getPartyByPlayer(player.getUniqueId()) != null) {
            plugin.playback().partySeek(player, ms);
            return;
        }

        plugin.playback().seek(player, ms);
    }

    private void partySeek(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(ChatColor.RED + "Usage: /music party seek <time>");
            return;
        }

        long ms = parseTime(args[2]);
        if (ms < 0) {
            player.sendMessage(ChatColor.RED + "Invalid time format. Use seconds or MM:SS.");
            return;
        }

        plugin.playback().partySeek(player, ms);
    }

    private void skip(
            Player player
    ) {
        if (!player.hasPermission("vibesindev.skip")) {
            deny(player);
            return;
        }
        if (plugin.parties()
                .getPartyByPlayer(
                        player.getUniqueId()
                ) != null) {

            plugin.playback()
                    .partyNext(player);

            return;
        }

        plugin.playback()
                .next(player);
    }

    private void pause(
            Player player
    ) {
        if (!player.hasPermission("vibesindev.pause")) {
            deny(player);
            return;
        }
        if (plugin.parties()
                .getPartyByPlayer(
                        player.getUniqueId()
                ) != null) {

            plugin.playback()
                    .partyPause(player);

            return;
        }

        if (!plugin.network()
                .isPlaying(player)) {

            player.sendMessage(
                    ChatColor.YELLOW
                            + "No music is currently playing."
            );
            return;
        }

        if (plugin.network()
                .isPaused(player)) {

            player.sendMessage(
                    ChatColor.YELLOW
                            + "Music is already paused."
            );
            return;
        }

        plugin.playback()
                .pause(player);

        player.sendMessage(
                ChatColor.YELLOW
                        + ". Music paused."
        );
    }

    private void resume(
            Player player
    ) {
        if (!player.hasPermission("vibesindev.pause")) {
            deny(player);
            return;
        }
        if (plugin.parties()
                .getPartyByPlayer(
                        player.getUniqueId()
                ) != null) {

            plugin.playback()
                    .partyResume(player);

            return;
        }

        if (!plugin.network()
                .isPlaying(player)) {

            player.sendMessage(
                    ChatColor.YELLOW
                            + "No music is currently playing."
            );
            return;
        }

        if (!plugin.network()
                .isPaused(player)) {

            player.sendMessage(
                    ChatColor.YELLOW
                            + "Music is already playing."
            );
            return;
        }

        plugin.playback()
                .resume(player);

        player.sendMessage(
                ChatColor.GREEN
                        + "▶ Music resumed."
        );
    }

    private void stop(
            Player player
    ) {
        if (!player.hasPermission("vibesindev.stop")) {
            deny(player);
            return;
        }
        if (plugin.parties()
                .getPartyByPlayer(
                        player.getUniqueId()
                ) != null) {

            plugin.playback()
                    .partyStop(player);

            return;
        }

        plugin.playback()
                .stop(player);
    }

    private boolean deny(
            CommandSender sender
    ) {

        sender.sendMessage(
                ChatColor.RED
                        + "You do not have permission."
        );

        return true;
    }

    private void why(Player player) {
        if (!player.hasPermission("vibesindev.use")) {
            deny(player);
            return;
        }
        if (plugin.recommendations() == null) {
            player.sendMessage(ChatColor.RED + "Recommendation engine is not available.");
            return;
        }

        UUID targetUuid = player.getUniqueId();
        MusicParty party = (plugin.parties() != null) ? plugin.parties().getPartyByPlayer(player.getUniqueId()) : null;
        if (party != null && party.getMemberCount() > 1) {
            targetUuid = party.getPartyId();
        }

        dev.vibesindev.vibesmusic.recommendation.RankedPick pick = plugin.recommendations().getLastAutoplayPick(targetUuid);
        if (pick == null) {
            pick = plugin.recommendations().getLastAutoplayPick(player.getUniqueId());
        }
        if (pick == null) {
            player.sendMessage(ChatColor.YELLOW + "No recent autoplay recommendation found for this session.");
            return;
        }

        player.sendMessage(ChatColor.LIGHT_PURPLE + "✦ Autoplay Recommendation Explanation ✦");
        player.sendMessage(ChatColor.GRAY + "Track: " + ChatColor.WHITE + pick.track().title() + ChatColor.GRAY + " by " + ChatColor.WHITE + pick.track().artist());
        player.sendMessage(ChatColor.GRAY + "Mode: " + ChatColor.AQUA + pick.mode().name() + ChatColor.GRAY + " | Source: " + ChatColor.GREEN + pick.source().name());
        player.sendMessage(ChatColor.GRAY + "Final Score: " + ChatColor.YELLOW + String.format(java.util.Locale.ROOT, "%.3f", pick.score()));
        if (pick.reason() != null && !pick.reason().isBlank()) {
            player.sendMessage(ChatColor.GRAY + "Reason: " + ChatColor.GOLD + pick.reason());
        }
    }

    private void recCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("vibesindev.admin")) {
            deny(sender);
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("stats")) {
            String[] statsArgs = Arrays.copyOfRange(args, 1, args.length);
            recStatsCommand(sender, statsArgs);
            return;
        }
        sender.sendMessage(ChatColor.RED + "Usage: /music rec stats [days]");
    }

    private void recStatsCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("vibesindev.admin")) {
            deny(sender);
            return;
        }
        if (plugin.cache() == null) {
            sender.sendMessage(ChatColor.RED + "Database cache is not available.");
            return;
        }

        int days = 7;
        if (args.length >= 2) {
            try {
                days = Math.max(1, Integer.parseInt(args[1]));
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "Invalid number of days: " + args[1]);
                return;
            }
        }

        int queryDays = days;
        sender.sendMessage(ChatColor.GRAY + "Querying recommendation telemetry for the last " + queryDays + " days...");
        CompletableFuture.runAsync(() -> {
            try {
                dev.vibesindev.vibesmusic.cache.TrackCache.TelemetryStats stats = plugin.cache().getRecommendationStats(queryDays);
                if (stats == null || stats.totalImpressions() == 0) {
                    sender.sendMessage(ChatColor.YELLOW + "No recommendation impressions recorded in the last " + queryDays + " days.");
                    return;
                }

                sender.sendMessage(ChatColor.GOLD + "=== Recommendation Engine Telemetry (Last " + queryDays + "d) ===");
                sender.sendMessage(ChatColor.GRAY + "Total Impressions: " + ChatColor.WHITE + stats.totalImpressions()
                        + ChatColor.GRAY + " (v2: " + ChatColor.GREEN + stats.v2Count()
                        + ChatColor.GRAY + ", v1: " + ChatColor.YELLOW + stats.v1Count() + ChatColor.GRAY + ")"
                        + ChatColor.GRAY + " | Solo: " + ChatColor.WHITE + stats.soloCount()
                        + ChatColor.GRAY + ", Party: " + ChatColor.WHITE + stats.partyCount());

                sender.sendMessage(ChatColor.GRAY + "Listening Outcomes: " + ChatColor.WHITE + stats.totalOutcomes()
                        + ChatColor.GRAY + " (Completed: " + ChatColor.GREEN + String.format(java.util.Locale.ROOT, "%.1f%%", stats.completionRate() * 100)
                        + ChatColor.GRAY + " [" + stats.completedCount() + "], Early Skip: " + ChatColor.RED + String.format(java.util.Locale.ROOT, "%.1f%%", stats.earlySkipRate() * 100)
                        + ChatColor.GRAY + " [" + stats.earlySkipCount() + "], Late Skip: " + ChatColor.YELLOW + stats.lateSkipCount() + ")");

                sender.sendMessage(ChatColor.GRAY + "Latency (ms): " + ChatColor.AQUA + "p50=" + stats.p50LatencyMs() + "ms"
                        + ChatColor.GRAY + ", " + ChatColor.AQUA + "p95=" + stats.p95LatencyMs() + "ms");

                if (!stats.modeCounts().isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    stats.modeCounts().forEach((k, v) -> sb.append(k).append(": ").append(v).append("  "));
                    sender.sendMessage(ChatColor.GRAY + "Modes: " + ChatColor.WHITE + sb.toString().trim());
                }

                if (!stats.sourceCounts().isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    stats.sourceCounts().forEach((k, v) -> sb.append(k).append(": ").append(v).append("  "));
                    sender.sendMessage(ChatColor.GRAY + "Selected Sources: " + ChatColor.WHITE + sb.toString().trim());
                }

                if (!stats.relaxationLevels().isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    stats.relaxationLevels().forEach((k, v) -> sb.append(k).append(": ").append(v).append("  "));
                    sender.sendMessage(ChatColor.GRAY + "Relaxation: " + ChatColor.WHITE + sb.toString().trim());
                }
            } catch (Exception e) {
                sender.sendMessage(ChatColor.RED + "Failed to retrieve telemetry stats: " + e.getMessage());
            }
        });
    }

    private void help(
            CommandSender sender
    ) {

        sender.sendMessage(
                ChatColor.AQUA
                        + "VibesInDev Music"
        );

        sender.sendMessage(
                ChatColor.GRAY
                        + "/music"
                        + ChatColor.WHITE
                        + " - Open music menu"
        );

        sender.sendMessage(
                ChatColor.GRAY
                        + "/music volume <0-100>"
                        + ChatColor.WHITE
                        + " - Set and save listening volume"
        );

        sender.sendMessage(
                ChatColor.GRAY
                        + "/music playlist [save|play|list|delete]"
                        + ChatColor.WHITE
                        + " - Manage custom playlists and open GUI"
        );

        sender.sendMessage(
                ChatColor.GRAY
                        + "/music play <song name or URL>"
        );

        sender.sendMessage(
                ChatColor.GRAY
                        + "/music nowplaying"
        );

        sender.sendMessage(
                ChatColor.GRAY
                        + "/music queue"
        );

        sender.sendMessage(
                ChatColor.GRAY
                        + "/music previous"
        );

        sender.sendMessage(
                ChatColor.GRAY
                        + "/music skip"
        );

        sender.sendMessage(
                ChatColor.GRAY
                        + "/music pause"
        );

        sender.sendMessage(
                ChatColor.GRAY
                        + "/music resume"
        );

        sender.sendMessage(
                ChatColor.GRAY
                        + "/music stop"
        );

        sender.sendMessage(
                ChatColor.GRAY
                        + "/music party"
                        + ChatColor.WHITE
                        + " - Music party commands"
        );

        sender.sendMessage(
                ChatColor.GRAY
                        + "/music spotify login"
        );
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args
    ) {

        /*
         * /music <command>
         *
         * Matches anywhere in the command, while prioritizing
         * suggestions that START with what the player typed.
         *
         * Examples:
         *   /music par<TAB> -> party
         *   /music sto<TAB> -> stop
         *   /music now<TAB> -> nowplaying
         */
        if (args.length == 1) {

            return fuzzyComplete(
                    List.of(
                            "play",
                            "nowplaying",
                            "queue",
                            "previous",
                            "skip",
                            "seek",
                            "pause",
                            "resume",
                            "stop",
                            "shuffle",
                            "repeat",
                            "party",
                            "autoplay",
                            "like",
                            "dislike",
                            "spotify",
                            "reload",
                            "status",
                            "diagnostics",
                            "metrics",
                            "debug",
                            "history",
                            "volume",
                            "playlist",
                            "why",
                            "rec",
                            "recstats"
                    ),
                    args[0]
            );
        }

        if ((args[0].equalsIgnoreCase("playlist") || args[0].equalsIgnoreCase("playlists"))
                && args.length == 2) {
            return fuzzyComplete(
                    List.of("save", "play", "load", "list", "delete"),
                    args[1]
            );
        }

        if ((args[0].equalsIgnoreCase("playlist") || args[0].equalsIgnoreCase("playlists"))
                && args.length == 3) {
            if (args[1].equalsIgnoreCase("play") || args[1].equalsIgnoreCase("load") || args[1].equalsIgnoreCase("delete")) {
                if (sender instanceof Player p) {
                    return fuzzyComplete(
                            plugin.playlists().getPlaylistNames(p.getUniqueId()),
                            args[2]
                    );
                }
            }
        }

        if ((args[0].equalsIgnoreCase("volume") || args[0].equalsIgnoreCase("vol"))
                && args.length == 2) {
            return fuzzyComplete(
                    List.of("0", "25", "50", "75", "100"),
                    args[1]
            );
        }

        /*
         * /music spotify <command>
         */
        if (args[0].equalsIgnoreCase("spotify")
                && args.length == 2) {

            return fuzzyComplete(
                    List.of("login", "book", "code", "sync", "status", "logout"),
                    args[1]
            );
        }

        if (args[0].equalsIgnoreCase("rec") && args.length == 2) {
            return fuzzyComplete(List.of("stats"), args[1]);
        }

        if (args[0].equalsIgnoreCase("rec") && args.length == 3 && args[1].equalsIgnoreCase("stats")) {
            return fuzzyComplete(List.of("1", "7", "14", "30"), args[2]);
        }

        if (args[0].equalsIgnoreCase("recstats") && args.length == 2) {
            return fuzzyComplete(List.of("1", "7", "14", "30"), args[1]);
        }

        /*
         * /music autoplay <on|off>
         */
        if (args[0].equalsIgnoreCase("autoplay")
                && args.length == 2) {

            return fuzzyComplete(
                    List.of("on", "off"),
                    args[1]
            );
        }

        /*
         * /music queue <command>
         */
        if (args[0].equalsIgnoreCase("queue")
                && args.length == 2) {

            return fuzzyComplete(
                    List.of("add", "remove", "move", "clear"),
                    args[1]
            );
        }

        /*
         * /music repeat <mode>
         */
        if (args[0].equalsIgnoreCase("repeat")
                && args.length == 2) {

            return fuzzyComplete(
                    List.of("off", "track", "queue"),
                    args[1]
            );
        }

        /*
         * /music party <command>
         */
        if (args[0].equalsIgnoreCase("party")
                && args.length == 2) {

            return fuzzyComplete(
                    List.of(
                            "create",
                            "invite",
                            "accept",
                            "leave",
                            "disband",
                            "members",
                            "info",
                            "kick",
                            "transfer",
                            "play",
                            "pause",
                            "resume",
                            "skip",
                            "seek",
                            "stop",
                            "queue",
                            "shuffle",
                            "repeat",
                            "autoplay"
                    ),
                    args[1]
            );
        }

        /*
         * /music party autoplay <on|off>
         */
        if (args[0].equalsIgnoreCase("party")
                && args.length == 3
                && args[1].equalsIgnoreCase("autoplay")) {
            return fuzzyComplete(
                    List.of("on", "off"),
                    args[2]
            );
        }

        /*
         * /music party invite <player>
         */
        if (args[0].equalsIgnoreCase("party")
                && args[1].equalsIgnoreCase("invite")
                && args.length == 3) {

            List<String> playerNames = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p != null) {
                    playerNames.add(p.getName());
                }
            }
            playerNames.sort(String.CASE_INSENSITIVE_ORDER);
            return fuzzyComplete(playerNames, args[2]);
        }

        /*
         * /music party kick <player>
         * /music party transfer <player>
         */
        if (args[0].equalsIgnoreCase("party")
                && args.length == 3
                && (args[1].equalsIgnoreCase("kick")
                || args[1].equalsIgnoreCase("transfer"))) {

            if (!(sender instanceof Player player)) {
                return List.of();
            }

            MusicParty party =
                    plugin.parties()
                            .getPartyByPlayer(
                                    player.getUniqueId()
                            );

            if (party == null) {
                return List.of();
            }

            String query =
                    args[2] == null
                            ? ""
                            : args[2].trim().toLowerCase();

            return party.getMembers()
                    .stream()
                    .filter(member ->
                            !member.equals(
                                    party.getOwner()
                            )
                    )
                    .map(Bukkit::getPlayer)
                    .filter(java.util.Objects::nonNull)
                    .map(Player::getName)
                    .filter(name ->
                            query.isBlank()
                                    || name.toLowerCase()
                                    .contains(query))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        }

        /*
         * /music party repeat <mode>
         */
        if (args[0].equalsIgnoreCase("party")
                && args[1].equalsIgnoreCase("repeat")
                && args.length == 3) {

            return fuzzyComplete(
                    List.of(
                            "off",
                            "one",
                            "all"
                    ),
                    args[2]
            );
        }

        return List.of();
    }

    /**
     * Fuzzy tab completion used throughout the command tree.
     *
     * Matching priority:
     *
     * 1. Exact match
     * 2. Starts with the typed text
     * 3. Contains the typed text
     * 4. Alphabetical order
     *
     * Examples:
     *
     *   "par" -> "party"
     *   "in"  -> "invite"
     *   "am"  -> "Sammy", "ExampleSam"
     */
    private List<String> fuzzyComplete(
            List<String> options,
            String input
    ) {
        String query =
                input == null
                        ? ""
                        : input.trim().toLowerCase();

        return options.stream()
                .filter(option -> {
                    if (query.isBlank()) {
                        return true;
                    }

                    return option.toLowerCase()
                            .contains(query);
                })
                .sorted(
                        (a, b) -> {
                            String lowerA =
                                    a.toLowerCase();

                            String lowerB =
                                    b.toLowerCase();

                            if (!query.isBlank()) {

                                boolean exactA =
                                        lowerA.equals(query);

                                boolean exactB =
                                        lowerB.equals(query);

                                if (exactA != exactB) {
                                    return exactA
                                            ? -1
                                            : 1;
                                }

                                boolean startsA =
                                        lowerA.startsWith(query);

                                boolean startsB =
                                        lowerB.startsWith(query);

                                if (startsA != startsB) {
                                    return startsA
                                            ? -1
                                            : 1;
                                }

                                int positionA =
                                        lowerA.indexOf(query);

                                int positionB =
                                        lowerB.indexOf(query);

                                if (positionA != positionB) {
                                    return Integer.compare(
                                            positionA,
                                            positionB
                                    );
                                }
                            }

                            return String.CASE_INSENSITIVE_ORDER
                                    .compare(a, b);
                        }
                )
                .toList();
    }
}

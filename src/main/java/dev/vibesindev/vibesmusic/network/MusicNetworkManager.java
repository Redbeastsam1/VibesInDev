package dev.vibesindev.vibesmusic.network;

import dev.vibesindev.vibesmusic.VibesInDevPlugin;
import dev.vibesindev.vibesmusic.model.Track;
import dev.vibesindev.vibesmusic.playback.MusicPlaybackManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import dev.vibesindev.vibesmusic.model.Track;
import dev.vibesindev.vibesmusic.party.MusicParty;
import org.bukkit.Bukkit;

import java.nio.file.Path;

public final class MusicNetworkManager
        implements PluginMessageListener, Listener {

    public static final String CHANNEL =
            "vibesindev:music";

    public static final int PROTOCOL_VERSION = 1;

    static final byte OP_HELLO = 1;
    static final byte OP_HELLO_ACK = 2;
    static final byte OP_START = 3;
    static final byte OP_DATA = 4;
    static final byte OP_END = 5;
    static final byte OP_STOP = 6;
    static final byte OP_PAUSE = 7;
    static final byte OP_RESUME = 8;
    static final byte OP_VOLUME = 9;
    static final byte OP_SEEK = 10;
    static final byte OP_SYNC = 11;
    public static final byte OP_RECONNECT_QUERY = 12;
    public static final byte OP_RECONNECT_STATUS = 13;


    private final VibesInDevPlugin plugin;

    private final Set<UUID> supportedPlayers =
            ConcurrentHashMap.newKeySet();

    private final ConcurrentHashMap<UUID, Float> volumes =
            new ConcurrentHashMap<>();

    private final java.io.File settingsFile;
    private final org.bukkit.configuration.file.YamlConfiguration settingsConfig;

    private final MusicStreamer streamer;

    private MusicPlaybackManager playback;

    public MusicNetworkManager(
            VibesInDevPlugin plugin
    ) {

        this.plugin = plugin;
        this.streamer = new MusicStreamer(this);
        this.settingsFile = new java.io.File(plugin.getDataFolder(), "player_settings.yml");
        this.settingsConfig = new org.bukkit.configuration.file.YamlConfiguration();
        loadSettings();
    }

    private void loadSettings() {
        if (!settingsFile.exists()) {
            return;
        }
        try {
            settingsConfig.load(settingsFile);
            org.bukkit.configuration.ConfigurationSection sec = settingsConfig.getConfigurationSection("volumes");
            if (sec != null) {
                for (String key : sec.getKeys(false)) {
                    try {
                        UUID uuid = UUID.fromString(key);
                        float vol = (float) sec.getDouble(key, 1.0);
                        volumes.put(uuid, vol);
                    } catch (IllegalArgumentException ignored) {}
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Could not load player_settings.yml: " + e.getMessage());
        }
    }

    public synchronized void saveSettings() {
        try {
            if (!settingsFile.exists()) {
                settingsFile.getParentFile().mkdirs();
                settingsFile.createNewFile();
            }
            for (java.util.Map.Entry<UUID, Float> entry : volumes.entrySet()) {
                settingsConfig.set("volumes." + entry.getKey().toString(), entry.getValue());
            }
            settingsConfig.save(settingsFile);
        } catch (java.io.IOException e) {
            plugin.getLogger().warning("Could not save player_settings.yml: " + e.getMessage());
        }
    }

    public void setPlaybackManager(
            MusicPlaybackManager playback
    ) {

        this.playback = playback;
    }

    public void enable() {

        plugin.getServer()
                .getMessenger()
                .registerOutgoingPluginChannel(
                        plugin,
                        CHANNEL
                );

        plugin.getServer()
                .getMessenger()
                .registerIncomingPluginChannel(
                        plugin,
                        CHANNEL,
                        this
                );

        plugin.getServer()
                .getPluginManager()
                .registerEvents(
                        this,
                        plugin
                );

        plugin.getLogger().info(
                "Minecraft music networking enabled on "
                        + CHANNEL
        );
    }

    public void disable() {

        saveSettings();
        streamer.stopAll();
        supportedPlayers.clear();

        plugin.getServer()
                .getMessenger()
                .unregisterIncomingPluginChannel(
                        plugin,
                        CHANNEL,
                        this
                );

        plugin.getServer()
                .getMessenger()
                .unregisterOutgoingPluginChannel(
                        plugin,
                        CHANNEL
                );
    }

    VibesInDevPlugin plugin() {
        return plugin;
    }

    public boolean isSupported(
            Player player
    ) {

        return supportedPlayers.contains(
                player.getUniqueId()
        );
    }

    public boolean stream(
            Player player,
            Track track,
            java.nio.file.Path audioFile
    ) {

        return streamer.start(
                player,
                track,
                audioFile
        );
    }

    public int streamParty(
            MusicParty party,
            Track track,
            Path audioFile
    ) {

        if (party == null
                || track == null
                || audioFile == null) {

            return 0;
        }


        int started = 0;


        for (UUID memberId : party.getMembers()) {

            Player member =
                    Bukkit.getPlayer(memberId);


            /*
             * Player may have disconnected after joining.
             */
            if (member == null
                    || !member.isOnline()) {

                continue;
            }


            /*
             * Party members must have the Fabric client
             * installed and connected.
             */
            if (!isSupported(member)) {

                continue;
            }


            /*
             * Each player gets their own MusicStreamer session,
             * but everyone reads from the SAME cached OGG file.
             *
             * Therefore:
             *
             *        ONE download
             *        ONE FFmpeg conversion
             *        ONE cached file
             *                 ↓
             *       ┌─────────┼─────────┐
             *       ↓         ↓         ↓
             *     Player A  Player B  Player C
             */
            if (streamer.start(
                    member,
                    track,
                    audioFile
            )) {

                started++;
            }
        }


        return started;
    }

    public void stop(Player player) {
        streamer.stop(player);
    }

    public void stopParty(
            MusicParty party
    ) {

        if (party == null) {
            return;
        }


        for (UUID memberId : party.getMembers()) {

            Player member =
                    Bukkit.getPlayer(memberId);


            if (member == null
                    || !member.isOnline()) {

                continue;
            }


            streamer.stop(member);
        }
    }

    public void pause(Player player) {
        streamer.pause(player);
    }

    public void resume(Player player) {
        streamer.resume(player);
    }
    
    public void seek(Player player, long positionMs) {
        streamer.seek(player, positionMs);
    }
    
    public void sync(Player player, UUID sessionId, String trackId, byte state, long positionMs) {
        streamer.sync(player, sessionId, trackId, state, positionMs);
    }

    public void resumeParty(
            MusicParty party
    ) {

        if (party == null) {
            return;
        }


        for (UUID memberId : party.getMembers()) {

            Player member =
                    Bukkit.getPlayer(memberId);


            if (member == null
                    || !member.isOnline()) {

                continue;
            }


            if (streamer.isPlaying(member)
                    && streamer.isPaused(member)) {

                streamer.resume(member);
            }
        }
    }

    public boolean isPaused(Player player) {
        return streamer.isPaused(player);
    }

    public boolean isPartyPaused(
            MusicParty party
    ) {

        if (party == null) {
            return false;
        }


        for (UUID memberId : party.getMembers()) {

            Player member =
                    Bukkit.getPlayer(memberId);


            if (member != null
                    && member.isOnline()
                    && streamer.isPaused(member)) {

                return true;
            }
        }


        return false;
    }

    public boolean isPlaying(Player player) {
        return streamer.isPlaying(player);
    }

    public boolean isPartyPlaying(
            MusicParty party
    ) {

        if (party == null) {
            return false;
        }


        for (UUID memberId : party.getMembers()) {

            Player member =
                    Bukkit.getPlayer(memberId);


            if (member != null
                    && member.isOnline()
                    && streamer.isPlaying(member)) {

                return true;
            }
        }


        return false;
    }

    public float getVolume(Player player) {

        return volumes.getOrDefault(
                player.getUniqueId(),
                1.0F
        );
    }

    public float setVolume(
            Player player,
            float volume
    ) {

        volume = Math.max(
                0.0F,
                Math.min(
                        1.0F,
                        volume
                )
        );

        volumes.put(
                player.getUniqueId(),
                volume
        );

        sendVolume(
                player,
                volume
        );

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, this::saveSettings);

        return volume;
    }

    public float changeVolume(
            Player player,
            float delta
    ) {

        return setVolume(
                player,
                getVolume(player) + delta
        );
    }

    void onStreamFinished(
            Player player,
            UUID streamId,
            Track track
    ) {

        if (playback == null) {
            return;
        }

        plugin.getServer()
                .getScheduler()
                .runTask(
                        plugin,
                        () -> playback.onTrackFinished(
                                player,
                                streamId,
                                track
                        )
                );
    }

    @Override
    public void onPluginMessageReceived(
            String channel,
            Player player,
            byte[] message
    ) {

        if (!CHANNEL.equals(channel)
                || message.length < 1) {
            return;
        }

        byte opcode = message[0];

        if (opcode == OP_HELLO) {
            int version = 0;
            if (message.length >= 5) {
                version = ((message[1] & 0xFF) << 24)
                        | ((message[2] & 0xFF) << 16)
                        | ((message[3] & 0xFF) << 8)
                        | (message[4] & 0xFF);
            }

            if (version != PROTOCOL_VERSION) {
                plugin.getLogger().warning(
                        "VibesInDev client protocol mismatch for "
                                + player.getName()
                                + ": client="
                                + version
                                + ", server="
                                + PROTOCOL_VERSION
                );
                return;
            }

            supportedPlayers.add(player.getUniqueId());
            sendHelloAck(player);
            // Send persistent saved volume to client immediately
            sendVolume(player, getVolume(player));

            // Delayed re-sync (10 ticks) in case client audio engine / world loading was still initializing
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline() && isSupported(player)) {
                    sendVolume(player, getVolume(player));
                }
            }, 10L);

            plugin.getLogger().info(
                    "VibesInDev Fabric client detected: "
                            + player.getName()
            );

            // Auto-sync fallback: if client is in a party that's currently playing, resume them
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline() && isSupported(player) && !streamer.isPlaying(player)) {
                    handleReconnectResume(player, false);
                }
            }, 5L);
        } else if (opcode == OP_RECONNECT_QUERY) {
            handleReconnectQuery(player);
        } else if (opcode == OP_END) {
            if (message.length >= 17) {
                long msb = (((long) message[1] & 0xFF) << 56)
                        | (((long) message[2] & 0xFF) << 48)
                        | (((long) message[3] & 0xFF) << 40)
                        | (((long) message[4] & 0xFF) << 32)
                        | (((long) message[5] & 0xFF) << 24)
                        | (((long) message[6] & 0xFF) << 16)
                        | (((long) message[7] & 0xFF) << 8)
                        | ((long) message[8] & 0xFF);
                long lsb = (((long) message[9] & 0xFF) << 56)
                        | (((long) message[10] & 0xFF) << 48)
                        | (((long) message[11] & 0xFF) << 40)
                        | (((long) message[12] & 0xFF) << 32)
                        | (((long) message[13] & 0xFF) << 24)
                        | (((long) message[14] & 0xFF) << 16)
                        | (((long) message[15] & 0xFF) << 8)
                        | ((long) message[16] & 0xFF);
                UUID streamId = new UUID(msb, lsb);
                streamer.handleClientEnd(player, streamId);
            } else {
                streamer.handleClientEndLegacy(player);
            }
        }
    }

    private void sendHelloAck(
            Player player
    ) {
        byte[] packet = new byte[5];
        packet[0] = OP_HELLO_ACK;
        writeIntBytes(packet, 1, PROTOCOL_VERSION);

        player.sendPluginMessage(
                plugin,
                CHANNEL,
                packet
        );
    }

    void sendStart(
            Player player,
            UUID streamId,
            Track track,
            long totalBytes,
            int chunkSize,
            int totalChunks
    ) {
        // Auto-sync volume guarantee: re-send saved volume on track start so client never gets stuck at 100%
        sendVolume(player, getVolume(player));

        byte[] title =
                track.title()
                        .getBytes(StandardCharsets.UTF_8);

        byte[] artist =
                track.artist()
                        .getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream bytes =
                new ByteArrayOutputStream();

        try (DataOutputStream out =
                     new DataOutputStream(bytes)) {

            out.writeByte(OP_START);

            writeUuid(
                    out,
                    streamId
            );

            out.writeLong(totalBytes);
            out.writeInt(chunkSize);
            out.writeInt(totalChunks);
            out.writeLong(
                    track.durationSeconds()
            );

            writeBytes(
                    out,
                    title
            );

            writeBytes(
                    out,
                    artist
            );

        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }

        player.sendPluginMessage(
                plugin,
                CHANNEL,
                bytes.toByteArray()
        );
    }

    void sendData(
            Player player,
            UUID streamId,
            int sequence,
            byte[] data,
            int length
    ) {
        byte[] packet = new byte[21 + length];
        packet[0] = OP_DATA;
        writeUuidBytes(packet, 1, streamId);
        writeIntBytes(packet, 17, sequence);
        System.arraycopy(data, 0, packet, 21, length);

        player.sendPluginMessage(
                plugin,
                CHANNEL,
                packet
        );
    }

    void sendEnd(
            Player player,
            UUID streamId
    ) {
        byte[] packet = new byte[17];
        packet[0] = OP_END;
        writeUuidBytes(packet, 1, streamId);

        player.sendPluginMessage(
                plugin,
                CHANNEL,
                packet
        );
    }

    public void sendStop(
            Player player,
            UUID streamId
    ) {
        byte[] packet = new byte[17];
        packet[0] = OP_STOP;
        writeUuidBytes(packet, 1, streamId);

        player.sendPluginMessage(
                plugin,
                CHANNEL,
                packet
        );
    }

    void sendPause(
            Player player,
            UUID streamId
    ) {
        byte[] packet = new byte[17];
        packet[0] = OP_PAUSE;
        writeUuidBytes(packet, 1, streamId);

        player.sendPluginMessage(
                plugin,
                CHANNEL,
                packet
        );
    }

    public void sendSeek(
            Player player,
            UUID streamId,
            long positionMs
    ) {
        byte[] packet = new byte[25];
        packet[0] = OP_SEEK;
        writeUuidBytes(packet, 1, streamId);
        writeLongBytes(packet, 17, positionMs);

        player.sendPluginMessage(
                plugin,
                CHANNEL,
                packet
        );
    }

    public void sendSync(
            Player player,
            UUID sessionId,
            String trackId,
            byte state,
            long positionMs
    ) {
        ByteArrayOutputStream bytes =
                new ByteArrayOutputStream(35 + trackId.length());

        try (DataOutputStream out =
                     new DataOutputStream(bytes)) {

            out.writeByte(OP_SYNC);

            writeUuid(
                    out,
                    sessionId
            );
            
            out.writeUTF(trackId);
            out.writeByte(state);
            out.writeLong(positionMs);

        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }

        player.sendPluginMessage(
                plugin,
                CHANNEL,
                bytes.toByteArray()
        );
    }

    void sendVolume(
            Player player,
            float volume
    ) {
        if (player == null || !player.isOnline()) {
            return;
        }

        // Apply quadratic perceptual curve: gain = volume^2
        // Linear 10% (0.10) becomes 0.01 (-40 dB), 50% (0.50) becomes 0.25 (-12 dB), 100% (1.00) becomes 1.0 (0 dB)
        float clamped = Math.max(0.0F, Math.min(1.0F, volume));
        float gain = clamped * clamped;

        byte[] packet = new byte[5];
        packet[0] = OP_VOLUME;
        writeIntBytes(packet, 1, Float.floatToIntBits(gain));

        player.sendPluginMessage(
                plugin,
                CHANNEL,
                packet
        );
    }

    void sendResume(
            Player player,
            UUID streamId
    ) {
        // Auto-sync volume guarantee: re-send saved volume on resume
        sendVolume(player, getVolume(player));

        byte[] packet = new byte[17];
        packet[0] = OP_RESUME;
        writeUuidBytes(packet, 1, streamId);

        player.sendPluginMessage(
                plugin,
                CHANNEL,
                packet
        );
    }

    private static void writeUuidBytes(byte[] dest, int offset, UUID uuid) {
        long msb = uuid.getMostSignificantBits();
        dest[offset] = (byte) (msb >>> 56);
        dest[offset + 1] = (byte) (msb >>> 48);
        dest[offset + 2] = (byte) (msb >>> 40);
        dest[offset + 3] = (byte) (msb >>> 32);
        dest[offset + 4] = (byte) (msb >>> 24);
        dest[offset + 5] = (byte) (msb >>> 16);
        dest[offset + 6] = (byte) (msb >>> 8);
        dest[offset + 7] = (byte) msb;

        long lsb = uuid.getLeastSignificantBits();
        dest[offset + 8] = (byte) (lsb >>> 56);
        dest[offset + 9] = (byte) (lsb >>> 48);
        dest[offset + 10] = (byte) (lsb >>> 40);
        dest[offset + 11] = (byte) (lsb >>> 32);
        dest[offset + 12] = (byte) (lsb >>> 24);
        dest[offset + 13] = (byte) (lsb >>> 16);
        dest[offset + 14] = (byte) (lsb >>> 8);
        dest[offset + 15] = (byte) lsb;
    }

    private static void writeLongBytes(byte[] dest, int offset, long val) {
        dest[offset] = (byte) (val >>> 56);
        dest[offset + 1] = (byte) (val >>> 48);
        dest[offset + 2] = (byte) (val >>> 40);
        dest[offset + 3] = (byte) (val >>> 32);
        dest[offset + 4] = (byte) (val >>> 24);
        dest[offset + 5] = (byte) (val >>> 16);
        dest[offset + 6] = (byte) (val >>> 8);
        dest[offset + 7] = (byte) val;
    }

    private static void writeIntBytes(byte[] dest, int offset, int val) {
        dest[offset] = (byte) (val >>> 24);
        dest[offset + 1] = (byte) (val >>> 16);
        dest[offset + 2] = (byte) (val >>> 8);
        dest[offset + 3] = (byte) val;
    }

    private static void writeUuid(
            DataOutputStream out,
            UUID uuid
    ) throws IOException {

        out.writeLong(
                uuid.getMostSignificantBits()
        );

        out.writeLong(
                uuid.getLeastSignificantBits()
        );
    }

    private static void writeBytes(
            DataOutputStream out,
            byte[] bytes
    ) throws IOException {

        out.writeInt(bytes.length);
        out.write(bytes);
    }

    public void handleReconnectQuery(Player player) {
        handleReconnectResume(player, true);
    }

    private void handleReconnectResume(Player player, boolean sendQueryReply) {
        UUID playerId = player.getUniqueId();
        MusicParty party = plugin.parties().getPartyByPlayer(playerId);

        if (party == null) {
            UUID lastPartyId = plugin.parties().getLastKnownParty(playerId);
            if (lastPartyId != null) {
                party = plugin.parties().getParty(lastPartyId);
                if (party != null) {
                    party.addMember(playerId);
                    plugin.parties().setPlayerParty(playerId, party);
                }
            }
        }

        if (party != null && playback != null) {
            dev.vibesindev.vibesmusic.playback.PlaybackSession session = playback.getSession(party.getPartyId());
            Track currentTrack = party.getCurrentTrack();

            if (session != null && currentTrack != null &&
                    (session.getState() == dev.vibesindev.vibesmusic.playback.PlaybackState.PLAYING
                            || session.getState() == dev.vibesindev.vibesmusic.playback.PlaybackState.PAUSED)) {

                if (party.isAutoPaused()) {
                    party.setAutoPaused(false);
                    party.setPaused(false);
                    session.setState(dev.vibesindev.vibesmusic.playback.PlaybackState.PLAYING);
                    plugin.getLogger().info("[VibesInDev] Auto-resumed party " + party.getPartyId() + " because member " + player.getName() + " rejoined.");
                    player.sendMessage(
                            org.bukkit.ChatColor.GOLD + "[VibesInDev] "
                                    + org.bukkit.ChatColor.GREEN + "Party music automatically resumed from where you left off!"
                    );
                }

                long positionMs = session.getPositionMs();
                byte status = (session.getState() == dev.vibesindev.vibesmusic.playback.PlaybackState.PAUSED) ? (byte) 2 : (byte) 1;

                if (sendQueryReply) {
                    sendReconnectStatus(player, status, positionMs, currentTrack, party);
                }

                player.sendMessage(
                        org.bukkit.ChatColor.GOLD + "[VibesInDev] "
                                + org.bukkit.ChatColor.GREEN + "Reconnected to party! Resuming: "
                                + org.bukkit.ChatColor.AQUA + currentTrack.title()
                                + org.bukkit.ChatColor.GRAY + " by "
                                + org.bukkit.ChatColor.AQUA + currentTrack.artist()
                                + org.bukkit.ChatColor.GRAY + " [" + formatMs(positionMs) + "]"
                );

                try {
                    player.spigot().sendMessage(
                            net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                            net.md_5.bungee.api.chat.TextComponent.fromLegacyText(
                                    org.bukkit.ChatColor.GOLD + "🎵 Reconnected • Resumed: "
                                            + org.bukkit.ChatColor.AQUA + currentTrack.title()
                                            + org.bukkit.ChatColor.GRAY + " [" + formatMs(positionMs) + "]"
                            )
                    );
                } catch (Throwable ignored) {}

                for (UUID memberId : party.getMembers()) {
                    if (!memberId.equals(playerId)) {
                        Player other = plugin.getServer().getPlayer(memberId);
                        if (other != null && other.isOnline()) {
                            other.sendMessage(
                                    org.bukkit.ChatColor.YELLOW + player.getName()
                                            + " reconnected to the party and resumed music."
                            );
                        }
                    }
                }

                playback.syncNewPartyMember(player, party);
                return;
            }
        }

        if (sendQueryReply) {
            sendReconnectStatus(player, (byte) 0, 0, null, null);
        }
    }

    public void sendReconnectStatus(
            Player player,
            byte status,
            long positionMs,
            Track track,
            MusicParty party
    ) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(64);
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeByte(OP_RECONNECT_STATUS);
                out.writeByte(status);
                out.writeLong(positionMs);

                if (track != null) {
                    out.writeLong(track.durationSeconds());
                    byte[] titleBytes = track.title().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    out.writeInt(titleBytes.length);
                    out.write(titleBytes);

                    byte[] artistBytes = track.artist().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    out.writeInt(artistBytes.length);
                    out.write(artistBytes);

                    String ownerName = "";
                    if (party != null && party.getOwner() != null) {
                        Player ownerP = plugin.getServer().getPlayer(party.getOwner());
                        ownerName = (ownerP != null) ? ownerP.getName() : party.getOwner().toString();
                    }
                    byte[] ownerBytes = ownerName.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    out.writeInt(ownerBytes.length);
                    out.write(ownerBytes);
                } else {
                    out.writeLong(0L);
                    out.writeInt(0);
                    out.writeInt(0);
                    out.writeInt(0);
                }
            }

            player.sendPluginMessage(plugin, CHANNEL, bytes.toByteArray());
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to send OP_RECONNECT_STATUS to " + player.getName() + ": " + e.getMessage());
        }
    }

    private static String formatMs(long ms) {
        long sec = ms / 1000;
        return String.format("%02d:%02d", sec / 60, sec % 60);
    }

    @EventHandler
    public void onPlayerQuit(
            PlayerQuitEvent event
    ) {
        plugin.metrics().incrementDisconnects();

        UUID playerId =
                event.getPlayer()
                        .getUniqueId();

        supportedPlayers.remove(
                playerId
        );

        // Keep persistent volume across disconnects - do not remove!

        streamer.stop(
                event.getPlayer()
        );
    }
}
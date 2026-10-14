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


    private final VibesInDevPlugin plugin;

    private final Set<UUID> supportedPlayers =
            ConcurrentHashMap.newKeySet();

    private final ConcurrentHashMap<UUID, Float> volumes =
            new ConcurrentHashMap<>();

    private final MusicStreamer streamer;

    private MusicPlaybackManager playback;

    public MusicNetworkManager(
            VibesInDevPlugin plugin
    ) {

        this.plugin = plugin;
        this.streamer = new MusicStreamer(this);
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

        try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(message))) {
            byte opcode = in.readByte();

            if (opcode == OP_HELLO) {
                int version = 0;
                if (in.available() >= 4) {
                    version = in.readInt();
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

                plugin.getLogger().info(
                        "VibesInDev Fabric client detected: "
                                + player.getName()
                );
            } else if (opcode == OP_END) {
                if (in.available() >= 16) {
                    long msb = in.readLong();
                    long lsb = in.readLong();
                    UUID streamId = new java.util.UUID(msb, lsb);
                    streamer.handleClientEnd(player, streamId);
                } else {
                    streamer.handleClientEndLegacy(player);
                }
            }
        } catch (java.io.IOException ignored) {
        }
    }

    private void sendHelloAck(
            Player player
    ) {

        ByteArrayOutputStream bytes =
                new ByteArrayOutputStream(5);

        try (DataOutputStream out =
                     new DataOutputStream(bytes)) {

            out.writeByte(OP_HELLO_ACK);
            out.writeInt(PROTOCOL_VERSION);

        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }

        player.sendPluginMessage(
                plugin,
                CHANNEL,
                bytes.toByteArray()
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

        ByteArrayOutputStream bytes =
                new ByteArrayOutputStream(
                        1 + 16 + 4 + length
                );

        try (DataOutputStream out =
                     new DataOutputStream(bytes)) {

            out.writeByte(OP_DATA);

            writeUuid(
                    out,
                    streamId
            );

            out.writeInt(sequence);
            out.write(data, 0, length);

        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }

        player.sendPluginMessage(
                plugin,
                CHANNEL,
                bytes.toByteArray()
        );
    }

    void sendEnd(
            Player player,
            UUID streamId
    ) {

        ByteArrayOutputStream bytes =
                new ByteArrayOutputStream(17);

        try (DataOutputStream out =
                     new DataOutputStream(bytes)) {

            out.writeByte(OP_END);

            writeUuid(
                    out,
                    streamId
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

    public void sendStop(
            Player player,
            UUID streamId
    ) {

        ByteArrayOutputStream bytes =
                new ByteArrayOutputStream(17);

        try (DataOutputStream out =
                     new DataOutputStream(bytes)) {

            out.writeByte(OP_STOP);

            writeUuid(
                    out,
                    streamId
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

    void sendPause(
            Player player,
            UUID streamId
    ) {

        ByteArrayOutputStream bytes =
                new ByteArrayOutputStream(17);

        try (DataOutputStream out =
                     new DataOutputStream(bytes)) {

            out.writeByte(OP_PAUSE);

            writeUuid(
                    out,
                    streamId
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

    public void sendSeek(
            Player player,
            UUID streamId,
            long positionMs
    ) {
        ByteArrayOutputStream bytes =
                new ByteArrayOutputStream(25);

        try (DataOutputStream out =
                     new DataOutputStream(bytes)) {

            out.writeByte(OP_SEEK);

            writeUuid(
                    out,
                    streamId
            );
            
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

        ByteArrayOutputStream bytes =
                new ByteArrayOutputStream(5);

        try (DataOutputStream out =
                     new DataOutputStream(bytes)) {

            out.writeByte(OP_VOLUME);
            out.writeFloat(volume);

        } catch (IOException impossible) {

            throw new IllegalStateException(
                    impossible
            );
        }

        player.sendPluginMessage(
                plugin,
                CHANNEL,
                bytes.toByteArray()
        );
    }

    void sendResume(
            Player player,
            UUID streamId
    ) {

        ByteArrayOutputStream bytes =
                new ByteArrayOutputStream(17);

        try (DataOutputStream out =
                     new DataOutputStream(bytes)) {

            out.writeByte(OP_RESUME);

            writeUuid(
                    out,
                    streamId
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

        volumes.remove(
                playerId
        );

        streamer.stop(
                event.getPlayer()
        );
    }
}
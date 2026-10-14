package dev.vibesindev.vibesmusic.client.network;

import dev.vibesindev.vibesmusic.client.audio.MusicAudioPlayer;
import dev.vibesindev.vibesmusic.client.audio.MusicStreamBuffer;
import dev.vibesindev.vibesmusic.network.MusicPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

public final class MusicNetwork {

    private static final byte OP_HELLO = 1;
    private static final byte OP_HELLO_ACK = 2;
    private static final byte OP_START = 3;
    private static final byte OP_DATA = 4;
    private static final byte OP_END = 5;
    private static final byte OP_STOP = 6;
    private static final byte OP_PAUSE = 7;
    private static final byte OP_RESUME = 8;
    private static final byte OP_VOLUME = 9;
    private static final byte OP_SEEK = 10;
    private static final byte OP_SYNC = 11;
    private static final byte OP_RECONNECT_QUERY = 12;
    private static final byte OP_RECONNECT_STATUS = 13;

    private static final int PROTOCOL_VERSION = 1;

    private MusicNetwork() {
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(
                MusicPayload.TYPE,
                (payload, context) ->
                        handleServerMessage(payload.data())
        );

        /*
         * The server can no longer send OP_STOP after the client
         * connection has been closed. Therefore disconnect handling
         * must stop local VibesInDev playback explicitly.
         *
         * This is especially important for recovery: without this
         * handler, MusicAudioPlayer sees the SoundInstance disappear
         * and assumes Minecraft temporarily interrupted the sound,
         * so it keeps rebuilding the decoder forever.
         */
        ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> {
                    System.out.println(
                            "[VibesInDev] Server connection closed. "
                                    + "Stopping local music playback."
                    );

                    client.execute(() -> {
                        MusicAudioPlayer.stop();
                        MusicStreamBuffer.stopCurrent();
                    });
                }
        );
    }

    public static void sendHello() {
        try {
            ByteArrayOutputStream bytes =
                    new ByteArrayOutputStream(5);

            try (DataOutputStream out =
                         new DataOutputStream(bytes)) {

                out.writeByte(OP_HELLO);
                out.writeInt(PROTOCOL_VERSION);
            }

            ClientPlayNetworking.send(
                    new MusicPayload(bytes.toByteArray())
            );

            System.out.println(
                    "[VibesInDev] Sent Fabric client HELLO."
            );

        } catch (IOException e) {
            throw new RuntimeException(
                    "Could not create VibesInDev HELLO payload.",
                    e
            );
        }
    }

    private static void handleServerMessage(byte[] data) {
        if (data == null || data.length < 1) {
            return;
        }

        int opcode = data[0] & 0xFF;

        try {
            switch (opcode) {
                case OP_HELLO_ACK -> handleHelloAck(data);
                case OP_START -> handleStart(data);
                case OP_DATA -> handleData(data);
                case OP_END -> handleEnd(data);
                case OP_STOP -> handleStop(data);
                case OP_PAUSE -> handlePause(data);
                case OP_RESUME -> handleResume(data);
                case OP_VOLUME -> handleVolume(data);
                case OP_SEEK -> handleSeek(data);
                case OP_SYNC -> handleSync(data);
                case OP_RECONNECT_STATUS -> handleReconnectStatus(data);

                default ->
                        System.out.println(
                                "[VibesInDev] Unknown music packet opcode: "
                                        + opcode
                        );
            }

        } catch (Exception e) {
            System.err.println(
                    "[VibesInDev] Failed to process music packet: "
                            + e.getMessage()
            );

            e.printStackTrace();
        }
    }

    private static void handleHelloAck(
            byte[] data
    ) throws IOException {

        if (data.length < 5) {
            return;
        }

        int version =
                ((data[1] & 0xFF) << 24)
                        | ((data[2] & 0xFF) << 16)
                        | ((data[3] & 0xFF) << 8)
                        | (data[4] & 0xFF);

        System.out.println(
                "[VibesInDev] Server HELLO_ACK received. Protocol "
                        + version
        );

        // Ask the server if any music is currently playing in our party
        sendReconnectQuery();
    }

    private static void handleStart(
            byte[] data
    ) throws IOException {

        DataInputStream in =
                new DataInputStream(
                        new ByteArrayInputStream(
                                data,
                                1,
                                data.length - 1
                        )
                );

        UUID streamId =
                new UUID(
                        in.readLong(),
                        in.readLong()
                );

        long totalBytes = in.readLong();
        int chunkSize = in.readInt();
        int totalChunks = in.readInt();
        long durationSeconds = in.readLong();

        // Server sends title/artist as: int(length) + raw UTF-8 bytes
        int titleLen = in.readInt();
        byte[] titleBytes = new byte[titleLen];
        in.readFully(titleBytes);
        String title = new String(titleBytes, java.nio.charset.StandardCharsets.UTF_8);

        int artistLen = in.readInt();
        byte[] artistBytes = new byte[artistLen];
        in.readFully(artistBytes);
        String artist = new String(artistBytes, java.nio.charset.StandardCharsets.UTF_8);

        System.out.println(
                "[VibesInDev] MUSIC START: "
                        + title
                        + " - "
                        + artist
                        + " ("
                        + durationSeconds
                        + "s)"
        );

        System.out.println(
                "[VibesInDev] Stream size: "
                        + totalBytes
                        + " bytes, "
                        + totalChunks
                        + " chunks."
        );

        System.out.println(
                "[VibesInDev] Server chunk size: "
                        + chunkSize
        );

        MusicStreamBuffer.begin(
                streamId,
                totalBytes,
                title,
                artist
        );
    }

    private static UUID readUuid(byte[] data, int offset) {
        long msb = (((long) data[offset] & 0xFF) << 56)
                | (((long) data[offset + 1] & 0xFF) << 48)
                | (((long) data[offset + 2] & 0xFF) << 40)
                | (((long) data[offset + 3] & 0xFF) << 32)
                | (((long) data[offset + 4] & 0xFF) << 24)
                | (((long) data[offset + 5] & 0xFF) << 16)
                | (((long) data[offset + 6] & 0xFF) << 8)
                | ((long) data[offset + 7] & 0xFF);

        long lsb = (((long) data[offset + 8] & 0xFF) << 56)
                | (((long) data[offset + 9] & 0xFF) << 48)
                | (((long) data[offset + 10] & 0xFF) << 40)
                | (((long) data[offset + 11] & 0xFF) << 32)
                | (((long) data[offset + 12] & 0xFF) << 24)
                | (((long) data[offset + 13] & 0xFF) << 16)
                | (((long) data[offset + 14] & 0xFF) << 8)
                | ((long) data[offset + 15] & 0xFF);

        return new UUID(msb, lsb);
    }

    private static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    private static long readLong(byte[] data, int offset) {
        return (((long) data[offset] & 0xFF) << 56)
                | (((long) data[offset + 1] & 0xFF) << 48)
                | (((long) data[offset + 2] & 0xFF) << 40)
                | (((long) data[offset + 3] & 0xFF) << 32)
                | (((long) data[offset + 4] & 0xFF) << 24)
                | (((long) data[offset + 5] & 0xFF) << 16)
                | (((long) data[offset + 6] & 0xFF) << 8)
                | ((long) data[offset + 7] & 0xFF);
    }
    private static void handleData(
            byte[] data
    ) throws IOException {

        if (data.length < 21) {
            return;
        }

        UUID streamId = readUuid(data, 1);
        int sequence = readInt(data, 17);

        int headerSize = 1 + 16 + 4;
        int audioLength = data.length - headerSize;

        if (audioLength <= 0) {
            return;
        }

        MusicStreamBuffer.append(
                streamId,
                sequence,
                data,
                headerSize,
                audioLength
        );

        /*
         * Network callbacks must not manipulate Minecraft's
         * SoundManager directly. Schedule playback on the
         * client thread.
         */
        if (MusicStreamBuffer.hasBufferedAtLeast(
                256 * 1024
        )) {
            Minecraft.getInstance().execute(
                    MusicAudioPlayer::play
            );
        }
    }

    private static void handleEnd(
            byte[] data
    ) throws IOException {

        if (data.length < 17) {
            return;
        }

        UUID streamId = readUuid(data, 1);

        MusicStreamBuffer.end(streamId);

        System.out.println(
                "[VibesInDev] MUSIC END packet received."
        );
    }

    private static void handleStop(
            byte[] data
    ) throws IOException {

        if (data.length < 17) {
            return;
        }

        UUID streamId = readUuid(data, 1);

        Minecraft.getInstance().execute(
                () -> {
                    MusicAudioPlayer.stop();
                    MusicStreamBuffer.stop(streamId);
                }
        );

        System.out.println(
                "[VibesInDev] MUSIC STOP packet received."
        );
    }

    private static void handlePause(
            byte[] data
    ) throws IOException {

        if (data.length < 17) {
            return;
        }

        UUID streamId = readUuid(data, 1);

        MusicStreamBuffer.StreamSession session =
                MusicStreamBuffer.current();

        if (session == null
                || !session.streamId().equals(streamId)) {
            return;
        }

        Minecraft.getInstance().execute(
                MusicAudioPlayer::pause
        );

        System.out.println(
                "[VibesInDev] MUSIC PAUSE packet received."
        );
    }

    private static void handleResume(
            byte[] data
    ) throws IOException {

        if (data.length < 17) {
            return;
        }

        UUID streamId = readUuid(data, 1);

        MusicStreamBuffer.StreamSession session =
                MusicStreamBuffer.current();

        if (session == null
                || !session.streamId().equals(streamId)) {
            return;
        }

        Minecraft.getInstance().execute(
                MusicAudioPlayer::resume
        );

        System.out.println(
                "[VibesInDev] MUSIC RESUME packet received."
        );
    }

    private static void handleVolume(
            byte[] data
    ) throws IOException {

        if (data.length < 5) {
            return;
        }

        int bits = readInt(data, 1);
        float volume = Math.max(
                0.0F,
                Math.min(
                        1.0F,
                        Float.intBitsToFloat(bits)
                )
        );

        Minecraft.getInstance().execute(
                () -> MusicAudioPlayer.setVolume(volume)
        );

        System.out.println(
                "[VibesInDev] Volume changed to "
                        + Math.round(volume * 100.0F)
                        + "%"
        );
    }

    private static void handleSeek(
            byte[] data
    ) throws IOException {

        // OP_SEEK: UUID(16) + positionMs(8)
        if (data.length < 25) {
            return;
        }

        UUID streamId = readUuid(data, 1);
        long positionMs = readLong(data, 17);

        MusicStreamBuffer.StreamSession session =
                MusicStreamBuffer.current();

        if (session == null
                || !session.streamId().equals(streamId)) {
            return;
        }

        // Seek: stop current playback and resume from the requested position
        Minecraft.getInstance().execute(
                () -> MusicAudioPlayer.seekTo(positionMs)
        );

        System.out.println(
                "[VibesInDev] MUSIC SEEK to " + positionMs + "ms received."
        );
    }

    private static void handleSync(
            byte[] data
    ) throws IOException {

        // OP_SYNC: sessionId(16) + trackId(UTF) + state(1) + positionMs(8)
        if (data.length < 26) {
            return;
        }

        DataInputStream in =
                new DataInputStream(
                        new ByteArrayInputStream(
                                data,
                                1,
                                data.length - 1
                        )
                );

        UUID sessionId =
                new UUID(
                        in.readLong(),
                        in.readLong()
                );

        String trackId = in.readUTF();
        byte state = in.readByte();
        long positionMs = in.readLong();

        System.out.println(
                "[VibesInDev] SYNC: session=" + sessionId
                        + " track=" + trackId
                        + " state=" + state
                        + " pos=" + positionMs + "ms"
        );
    }

    /**
     * Sends OP_END to the server, signalling that local playback has
     * finished so the server can advance the queue to the next track.
     */
    public static void sendPlaybackFinished(UUID streamId) {
        if (streamId == null) {
            return;
        }

        byte[] packet = new byte[17];
        packet[0] = OP_END;
        long msb = streamId.getMostSignificantBits();
        packet[1] = (byte) (msb >>> 56);
        packet[2] = (byte) (msb >>> 48);
        packet[3] = (byte) (msb >>> 40);
        packet[4] = (byte) (msb >>> 32);
        packet[5] = (byte) (msb >>> 24);
        packet[6] = (byte) (msb >>> 16);
        packet[7] = (byte) (msb >>> 8);
        packet[8] = (byte) msb;

        long lsb = streamId.getLeastSignificantBits();
        packet[9] = (byte) (lsb >>> 56);
        packet[10] = (byte) (lsb >>> 48);
        packet[11] = (byte) (lsb >>> 40);
        packet[12] = (byte) (lsb >>> 32);
        packet[13] = (byte) (lsb >>> 24);
        packet[14] = (byte) (lsb >>> 16);
        packet[15] = (byte) (lsb >>> 8);
        packet[16] = (byte) lsb;

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() != null) {
            ClientPlayNetworking.send(
                    new MusicPayload(packet)
            );
            System.out.println(
                    "[VibesInDev] Sent playback-finished signal to server for stream " + streamId
            );
        }
    }

    /**
     * Sends OP_RECONNECT_QUERY to the server, asking if any party music is playing.
     */
    public static void sendReconnectQuery() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) {
            ClientPlayNetworking.send(new MusicPayload(new byte[]{OP_RECONNECT_QUERY}));
            System.out.println("[VibesInDev] Sent party music reconnect query to server.");
        }
    }

    private static void handleReconnectStatus(byte[] data) throws IOException {
        if (data.length < 10) {
            return;
        }

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data, 1, data.length - 1));
        byte status = in.readByte();
        long positionMs = in.readLong();
        long durationSeconds = in.readLong();

        if (status == 1 || status == 2) {
            int titleLen = in.readInt();
            byte[] titleBytes = new byte[titleLen];
            in.readFully(titleBytes);
            String title = new String(titleBytes, java.nio.charset.StandardCharsets.UTF_8);

            int artistLen = in.readInt();
            byte[] artistBytes = new byte[artistLen];
            in.readFully(artistBytes);
            String artist = new String(artistBytes, java.nio.charset.StandardCharsets.UTF_8);

            int ownerLen = in.readInt();
            byte[] ownerBytes = new byte[ownerLen];
            in.readFully(ownerBytes);
            String owner = new String(ownerBytes, java.nio.charset.StandardCharsets.UTF_8);

            System.out.println("[VibesInDev] Active party music found on reconnect! Resuming: "
                    + title + " - " + artist + " at " + positionMs + "ms");

        } else {
            System.out.println("[VibesInDev] No active party music on reconnect.");
        }
    }
}

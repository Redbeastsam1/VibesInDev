package dev.vibesindev.vibesmusic.network;

import dev.vibesindev.vibesmusic.VibesInDevPlugin;
import dev.vibesindev.vibesmusic.model.Track;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

final class MusicStreamer {

    private static final long MIN_CHUNK_DELAY_TICKS = 1L;

    private static final int INITIAL_BUFFER_CHUNKS = 32;

    private final MusicNetworkManager network;
    private final VibesInDevPlugin plugin;

    private final Map<UUID, StreamSession> sessions =
            new ConcurrentHashMap<>();

    MusicStreamer(MusicNetworkManager network) {
        this.network = network;
        this.plugin = network.plugin();
    }

    boolean start(
            Player player,
            Track track,
            Path audioFile
    ) {

        if (!network.isSupported(player)) {

            player.sendMessage(
                    ChatColor.RED
                            + "VibesInDev Fabric client not detected."
            );

            return false;
        }

        Path cacheRoot =
                plugin.cache()
                        .audioDirectory()
                        .toAbsolutePath()
                        .normalize();

        Path file =
                audioFile
                        .toAbsolutePath()
                        .normalize();

        if (!file.startsWith(cacheRoot)
                || !Files.isRegularFile(file)) {

            player.sendMessage(
                    ChatColor.RED
                            + "Invalid VibesInDev audio file."
            );

            return false;
        }

        stop(player);

        long totalBytes;

        try {
            totalBytes = Files.size(file);

        } catch (IOException e) {

            player.sendMessage(
                    ChatColor.RED
                            + "Could not read cached audio: "
                            + e.getMessage()
            );

            return false;
        }

        if (totalBytes <= 0) {

            player.sendMessage(
                    ChatColor.RED
                            + "Cached audio file is empty."
            );

            return false;
        }

        int chunkSize =
                plugin.getConfig()
                        .getInt(
                                "network.chunk-size-bytes",
                                4096
                        );

        if (chunkSize < 4096
                || chunkSize > 262144) {

            chunkSize = 4096;
        }

        int totalChunks =
                (int) (
                        (totalBytes + chunkSize - 1)
                                / chunkSize
                );

        UUID streamId =
                UUID.randomUUID();

        StreamSession session =
                new StreamSession(
                        player.getUniqueId(),
                        streamId,
                        track,
                        file,
                        chunkSize,
                        totalChunks,
                        totalBytes
                );

        sessions.put(
                player.getUniqueId(),
                session
        );

        network.sendStart(
                player,
                streamId,
                track,
                totalBytes,
                chunkSize,
                totalChunks
        );

        /*
         * Send a small initial burst so the client can start
         * decoding without immediately waiting for network data.
         */
        sendInitialBuffer(session);

        return true;
    }

    void stop(Player player) {

        StreamSession session =
                sessions.remove(
                        player.getUniqueId()
                );

        if (session == null) {
            return;
        }

        session.cancelled = true;

        if (network.isSupported(player)
                && player.isOnline()) {

            network.sendStop(
                    player,
                    session.streamId
            );
        }
    }

    void stopAll() {

        for (StreamSession session :
                sessions.values()) {

            session.cancelled = true;
        }

        sessions.clear();
    }

    boolean isPlaying(Player player) {

        return sessions.containsKey(
                player.getUniqueId()
        );
    }

    boolean isPaused(Player player) {

        StreamSession session =
                sessions.get(
                        player.getUniqueId()
                );

        return session != null
                && session.paused;
    }

    void pause(Player player) {

        StreamSession session =
                sessions.get(
                        player.getUniqueId()
                );

        if (session == null
                || session.paused) {
            return;
        }

        session.paused = true;

        network.sendPause(
                player,
                session.streamId
        );
    }

    void resume(Player player) {

        StreamSession session =
                sessions.get(
                        player.getUniqueId()
                );

        if (session == null
                || !session.paused) {
            return;
        }

        session.paused = false;

        network.sendResume(
                player,
                session.streamId
        );

        /*
         * Continue sending data after resume.
         */
        scheduleNext(
                session,
                1L
        );
    }

    private void sendInitialBuffer(
            StreamSession session
    ) {

        int count = Math.min(
                INITIAL_BUFFER_CHUNKS,
                session.totalChunks
        );

        for (int i = 0; i < count; i++) {

            sendNextImmediate(session);

            if (session.cancelled) {
                return;
            }
        }

        scheduleNext(
                session,
                MIN_CHUNK_DELAY_TICKS
        );
    }

    private void sendNextImmediate(
            StreamSession session
    ) {

        if (session.cancelled
                || session.paused) {
            return;
        }

        if (session.sequence >= session.totalChunks) {

            finish(session);
            return;
        }

        int sequence =
                session.sequence;

        long offset =
                (long) sequence
                        * session.chunkSize;

        byte[] chunk;

        try {

            chunk = readChunk(
                    session.file,
                    offset,
                    session.chunkSize
            );

        } catch (Exception e) {

            fail(
                    session,
                    e
            );

            return;
        }

        if (chunk.length == 0) {

            finish(session);
            return;
        }

        Player player =
                plugin.getServer()
                        .getPlayer(
                                session.playerId
                        );

        if (player == null
                || !player.isOnline()
                || !network.isSupported(player)) {

            cancel(session);
            return;
        }

        network.sendData(
                player,
                session.streamId,
                sequence,
                chunk,
                chunk.length
        );

        session.sequence++;
    }

    private void scheduleNext(
            StreamSession session,
            long delayTicks
    ) {

        if (session.cancelled) {
            return;
        }

        plugin.getServer()
                .getScheduler()
                .runTaskLater(
                        plugin,
                        () -> sendNextScheduled(session),
                        delayTicks
                );
    }

    private void sendNextScheduled(
            StreamSession session
    ) {

        if (session.cancelled
                || session.paused) {
            return;
        }

        if (session.sequence >= session.totalChunks) {

            finish(session);
            return;
        }

        int sequence =
                session.sequence;

        long offset =
                (long) sequence
                        * session.chunkSize;

        CompletableFuture
                .supplyAsync(
                        () -> {

                            try {

                                return readChunk(
                                        session.file,
                                        offset,
                                        session.chunkSize
                                );

                            } catch (IOException e) {

                                throw new RuntimeException(e);
                            }
                        }
                )
                .whenComplete(
                        (chunk, error) ->

                                plugin.getServer()
                                        .getScheduler()
                                        .runTask(
                                                plugin,
                                                () -> {

                                                    if (session.cancelled
                                                            || session.paused) {
                                                        return;
                                                    }

                                                    if (error != null
                                                            || chunk == null) {

                                                        fail(
                                                                session,
                                                                error
                                                        );

                                                        return;
                                                    }

                                                    Player player =
                                                            plugin.getServer()
                                                                    .getPlayer(
                                                                            session.playerId
                                                                    );

                                                    if (player == null
                                                            || !player.isOnline()
                                                            || !network.isSupported(player)) {

                                                        cancel(session);
                                                        return;
                                                    }

                                                    network.sendData(
                                                            player,
                                                            session.streamId,
                                                            sequence,
                                                            chunk,
                                                            chunk.length
                                                    );

                                                    session.sequence++;

                                                    if (session.sequence
                                                            >= session.totalChunks) {

                                                        finish(session);
                                                        return;
                                                    }

                                                    scheduleNext(
                                                            session,
                                                            calculateDelay(
                                                                    session
                                                            )
                                                    );
                                                }
                                        )
                );
    }

    private long calculateDelay(
            StreamSession session
    ) {

        /*
         * Pace transmission approximately according to
         * the real playback duration.
         *
         * bytes / duration = bytes per second.
         */
        if (session.track.durationSeconds() <= 0) {
            return MIN_CHUNK_DELAY_TICKS;
        }

        double bytesPerSecond =
                (double) session.totalBytes
                        / session.track.durationSeconds();

        double secondsPerChunk =
                session.chunkSize
                        / bytesPerSecond;

        long ticks =
                Math.round(
                        secondsPerChunk * 20.0
                );

        return Math.max(
                MIN_CHUNK_DELAY_TICKS,
                ticks
        );
    }

    private void finish(
            StreamSession session
    ) {

        if (session.cancelled
                || session.finished) {
            return;
        }

        session.finished = true;

        Player player =
                plugin.getServer()
                        .getPlayer(
                                session.playerId
                        );

        if (player == null
                || !player.isOnline()
                || !network.isSupported(player)) {
            return;
        }

        network.sendEnd(
                player,
                session.streamId
        );
    }
    
    void seek(Player player, long positionMs) {
        StreamSession session = sessions.get(player.getUniqueId());
        if (session == null || session.cancelled) {
            return;
        }

        if (network.isSupported(player) && player.isOnline()) {
            network.sendSeek(player, session.streamId, positionMs);
        }
    }
    
    void sync(Player player, UUID sessionId, String trackId, byte state, long positionMs) {
        StreamSession session = sessions.get(player.getUniqueId());
        if (session == null || session.cancelled) {
            return;
        }

        if (network.isSupported(player) && player.isOnline()) {
            network.sendSync(player, sessionId, trackId, state, positionMs);
        }
    }

    void handleClientEnd(Player player, UUID streamId) {
        StreamSession session = sessions.get(player.getUniqueId());
        if (session == null || !session.streamId.equals(streamId)) {
            return;
        }

        sessions.remove(player.getUniqueId(), session);

        network.onStreamFinished(
                player,
                session.streamId,
                session.track
        );
    }

    void handleClientEndLegacy(Player player) {
        StreamSession session = sessions.remove(player.getUniqueId());
        if (session != null) {
            network.onStreamFinished(player, session.streamId, session.track);
        }
    }

    private void fail(
            StreamSession session,
            Throwable error
    ) {

        if (session.cancelled) {
            return;
        }

        sessions.remove(
                session.playerId,
                session
        );

        session.cancelled = true;

        Player player =
                plugin.getServer()
                        .getPlayer(
                                session.playerId
                        );

        plugin.getLogger().warning(
                "Music stream failed for "
                        + session.track.title()
                        + ": "
                        + (
                        error == null
                                ? "unknown error"
                                : error.getMessage()
                )
        );

        if (player != null
                && player.isOnline()) {

            player.sendMessage(
                    ChatColor.RED
                            + "VibesInDev music stream failed."
            );
        }
    }

    private void cancel(
            StreamSession session
    ) {

        session.cancelled = true;

        sessions.remove(
                session.playerId,
                session
        );
    }

    private static byte[] readChunk(
            Path file,
            long offset,
            int chunkSize
    ) throws IOException {

        try (RandomAccessFile input =
                     new RandomAccessFile(
                             file.toFile(),
                             "r"
                     )) {

            input.seek(offset);

            int remaining =
                    (int) Math.min(
                            chunkSize,
                            input.length() - offset
                    );

            if (remaining <= 0) {
                return new byte[0];
            }

            byte[] buffer =
                    new byte[remaining];

            int read =
                    input.read(
                            buffer
                    );

            if (read <= 0) {
                return new byte[0];
            }

            if (read == buffer.length) {
                return buffer;
            }

            byte[] result =
                    new byte[read];

            System.arraycopy(
                    buffer,
                    0,
                    result,
                    0,
                    read
            );

            return result;
        }
    }

    private static final class StreamSession {

        private final UUID playerId;
        private final UUID streamId;
        private final Track track;
        private final Path file;
        private final int chunkSize;
        private final int totalChunks;
        private final long totalBytes;

        private int sequence;

        private volatile boolean cancelled;
        private volatile boolean paused;
        private volatile boolean finished;

        private StreamSession(
                UUID playerId,
                UUID streamId,
                Track track,
                Path file,
                int chunkSize,
                int totalChunks,
                long totalBytes
        ) {

            this.playerId = playerId;
            this.streamId = streamId;
            this.track = track;
            this.file = file;
            this.chunkSize = chunkSize;
            this.totalChunks = totalChunks;
            this.totalBytes = totalBytes;
        }
    }
}
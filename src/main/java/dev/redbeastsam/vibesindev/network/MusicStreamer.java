package dev.redbeastsam.vibesindev.network;

import dev.redbeastsam.vibesindev.VibesInDevPlugin;
import dev.redbeastsam.vibesindev.model.Track;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

final class MusicStreamer {
  private static final long MIN_CHUNK_DELAY_TICKS = 1L;
  private static final int INITIAL_BUFFER_CHUNKS = 32;
  private final MusicNetworkManager network;
  private final VibesInDevPlugin plugin;
  private final Map<UUID, StreamSession> sessions = new ConcurrentHashMap<>();

  MusicStreamer(MusicNetworkManager network) {
    this.network = network;
    this.plugin = network.plugin();
  }

  boolean start(Player player, Track track, Path audioFile) {
    if (!network.isSupported(player)) {
      player.sendMessage(ChatColor.RED + "VibesInDev Fabric client not detected.");

      return false;
    }

    Path cacheRoot = plugin.cache().audioDirectory().toAbsolutePath().normalize();

    Path file = audioFile.toAbsolutePath().normalize();

    if (!file.startsWith(cacheRoot) || !Files.isRegularFile(file)) {
      player.sendMessage(ChatColor.RED + "Invalid VibesInDev audio file.");

      return false;
    }

    stop(player);

    long totalBytes;

    try {
      totalBytes = Files.size(file);
    } catch (IOException e) {
      player.sendMessage(ChatColor.RED + "Could not read cached audio: " + e.getMessage());

      return false;
    }

    if (totalBytes <= 0) {
      player.sendMessage(ChatColor.RED + "Cached audio file is empty.");

      return false;
    }

    int chunkSize = plugin.getConfig().getInt("network.chunk-size-bytes", 4096);

    if (chunkSize < 4096 || chunkSize > 262144) {
      chunkSize = 4096;
    }

    int totalChunks = (int) ((totalBytes + chunkSize - 1) / chunkSize);

    UUID streamId = UUID.randomUUID();

    FileChannel channel;
    try {
      channel = FileChannel.open(file, StandardOpenOption.READ);
    } catch (IOException e) {
      player.sendMessage(ChatColor.RED + "Could not open audio file: " + e.getMessage());
      return false;
    }

    StreamSession session =
        new StreamSession(
            player.getUniqueId(),
            streamId,
            track,
            file,
            channel,
            chunkSize,
            totalChunks,
            totalBytes);

    sessions.put(player.getUniqueId(), session);

    network.sendStart(player, streamId, track, totalBytes, chunkSize, totalChunks);

    /*
     * Send a small initial burst so the client can start
     * decoding without immediately waiting for network data.
     */
    sendInitialBuffer(session);

    return true;
  }

  void stop(Player player) {
    StreamSession session = sessions.remove(player.getUniqueId());

    if (session == null) {
      return;
    }

    session.cancelled = true;
    session.close();

    if (network.isSupported(player) && player.isOnline()) {
      network.sendStop(player, session.streamId);
    }
  }

  void stopAll() {
    for (StreamSession session : sessions.values()) {
      session.cancelled = true;
      session.close();
    }

    sessions.clear();
  }

  boolean isPlaying(Player player) {
    return sessions.containsKey(player.getUniqueId());
  }

  boolean isPaused(Player player) {
    StreamSession session = sessions.get(player.getUniqueId());

    return session != null && session.paused;
  }

  void pause(Player player) {
    StreamSession session = sessions.get(player.getUniqueId());

    if (session == null || session.paused) {
      return;
    }

    session.paused = true;

    network.sendPause(player, session.streamId);
  }

  void resume(Player player) {
    StreamSession session = sessions.get(player.getUniqueId());

    if (session == null || !session.paused) {
      return;
    }

    session.paused = false;

    network.sendResume(player, session.streamId);

    /*
     * Continue sending data after resume.
     */
    scheduleNext(session, 1L);
  }

  private void sendInitialBuffer(StreamSession session) {
    int count = Math.min(INITIAL_BUFFER_CHUNKS, session.totalChunks);

    for (int i = 0; i < count; i++) {
      sendNextImmediate(session);

      if (session.cancelled) {
        return;
      }
    }

    scheduleNext(session, MIN_CHUNK_DELAY_TICKS);
  }

  private void sendNextImmediate(StreamSession session) {
    if (session.cancelled || session.paused) {
      return;
    }

    if (session.sequence >= session.totalChunks) {
      finish(session);
      return;
    }

    int sequence = session.sequence;

    long offset = (long) sequence * session.chunkSize;

    byte[] chunk;

    try {
      chunk = readChunk(session, offset, session.chunkSize);
    } catch (Exception e) {
      fail(session, e);

      return;
    }

    if (chunk.length == 0) {
      finish(session);
      return;
    }

    Player player = plugin.getServer().getPlayer(session.playerId);

    if (player == null || !player.isOnline() || !network.isSupported(player)) {
      cancel(session);
      return;
    }

    network.sendData(player, session.streamId, sequence, chunk, chunk.length);

    session.sequence++;
  }

  private void scheduleNext(StreamSession session, long delayTicks) {
    if (session.cancelled) {
      return;
    }

    plugin
        .getServer()
        .getScheduler()
        .runTaskLater(plugin, () -> sendNextScheduled(session), delayTicks);
  }

  private void sendNextScheduled(StreamSession session) {
    if (session.cancelled || session.paused) {
      return;
    }

    if (session.sequence >= session.totalChunks) {
      finish(session);
      return;
    }

    int sequence = session.sequence;

    long offset = (long) sequence * session.chunkSize;

    CompletableFuture.supplyAsync(
            () -> {
              try {
                return readChunk(session, offset, session.chunkSize);
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            })
        .whenComplete(
            (chunk, error) ->
                plugin
                    .getServer()
                    .getScheduler()
                    .runTask(
                        plugin,
                        () -> {
                          if (session.cancelled || session.paused) {
                            return;
                          }

                          if (error != null) {
                            fail(session, error);

                            return;
                          }

                          if (chunk == null || chunk.length == 0) {
                            finish(session);
                            return;
                          }

                          Player player = plugin.getServer().getPlayer(session.playerId);

                          if (player == null
                              || !player.isOnline()
                              || !network.isSupported(player)) {
                            cancel(session);
                            return;
                          }

                          network.sendData(player, session.streamId, sequence, chunk, chunk.length);

                          session.sequence++;

                          if (session.sequence >= session.totalChunks) {
                            finish(session);
                            return;
                          }

                          scheduleNext(session, calculateDelay(session));
                        }));
  }

  private long calculateDelay(StreamSession session) {
    /*
     * Stream chunks continuously at 1 tick (50ms) per chunk.
     * This rapidly fills the client's audio buffer within a few seconds,
     * completely preventing buffer underruns during playback, seeking, and dimension switches.
     */
    return MIN_CHUNK_DELAY_TICKS;
  }

  private void finish(StreamSession session) {
    if (session.cancelled || session.finished) {
      return;
    }

    session.finished = true;
    session.close();

    Player player = plugin.getServer().getPlayer(session.playerId);

    if (player == null || !player.isOnline() || !network.isSupported(player)) {
      return;
    }

    network.sendEnd(player, session.streamId);
  }

  void seek(Player player, long positionMs) {
    StreamSession session = sessions.get(player.getUniqueId());
    if (session == null || session.cancelled) {
      return;
    }

    /*
     * If the client needs audio chunks up to positionMs to seek forward,
     * burst-send all required chunks immediately so the client decoder
     * has all necessary OGG data without suffering a buffer underrun.
     */
    if (session.track.durationSeconds() > 0 && session.totalBytes > 0) {
      long targetByte =
          (positionMs * session.totalBytes) / (session.track.durationSeconds() * 1000L);
      int targetChunk = (int) (targetByte / session.chunkSize);
      int neededSequence = Math.min(session.totalChunks, targetChunk + 48);
      while (session.sequence < neededSequence && !session.cancelled && !session.paused) {
        sendNextImmediate(session);
      }
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
    session.close();

    network.onStreamFinished(player, session.streamId, session.track);
  }

  void handleClientEndLegacy(Player player) {
    StreamSession session = sessions.remove(player.getUniqueId());
    if (session != null) {
      session.close();
      network.onStreamFinished(player, session.streamId, session.track);
    }
  }

  private void fail(StreamSession session, Throwable error) {
    if (session.cancelled) {
      return;
    }

    sessions.remove(session.playerId, session);

    session.cancelled = true;
    session.close();

    Player player = plugin.getServer().getPlayer(session.playerId);

    plugin
        .getLogger()
        .warning(
            "Music stream failed for "
                + session.track.title()
                + ": "
                + (error == null ? "unknown error" : error.getMessage()));

    if (player != null && player.isOnline()) {
      player.sendMessage(ChatColor.RED + "VibesInDev music stream failed.");
    }
  }

  private void cancel(StreamSession session) {
    session.cancelled = true;
    session.close();

    sessions.remove(session.playerId, session);
  }

  private static byte[] readChunk(StreamSession session, long offset, int chunkSize)
      throws IOException {
    FileChannel channel = session.channel;
    if (channel == null || !channel.isOpen() || session.cancelled) {
      return new byte[0];
    }

    int remaining = (int) Math.min(chunkSize, session.totalBytes - offset);

    if (remaining <= 0) {
      return new byte[0];
    }

    ByteBuffer buffer = ByteBuffer.allocate(remaining);

    while (buffer.hasRemaining()) {
      int read = channel.read(buffer, offset + buffer.position());
      if (read < 0) {
        break;
      }
    }

    if (buffer.position() == remaining) {
      return buffer.array();
    }

    return Arrays.copyOf(buffer.array(), buffer.position());
  }

  private static final class StreamSession implements AutoCloseable {
    private final UUID playerId;
    private final UUID streamId;
    private final Track track;

    @SuppressWarnings("unused")
    private final Path file;
    private final FileChannel channel;
    private final int chunkSize;
    private final int totalChunks;
    private final long totalBytes;
    private int sequence;
    private volatile boolean cancelled;
    private volatile boolean paused;
    private volatile boolean finished;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private StreamSession(
        UUID playerId,
        UUID streamId,
        Track track,
        Path file,
        FileChannel channel,
        int chunkSize,
        int totalChunks,
        long totalBytes) {
      this.playerId = playerId;
      this.streamId = streamId;
      this.track = track;
      this.file = file;
      this.channel = channel;
      this.chunkSize = chunkSize;
      this.totalChunks = totalChunks;
      this.totalBytes = totalBytes;
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        if (channel != null && channel.isOpen()) {
          try {
            channel.close();
          } catch (IOException ignored) {
          }
        }
      }
    }
  }
}

package dev.vibesindev.vibesmusic.queue;

import dev.vibesindev.vibesmusic.VibesInDevPlugin;
import dev.vibesindev.vibesmusic.model.Track;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class MusicQueueManager {
  private final VibesInDevPlugin plugin;
  private final ConcurrentMap<UUID, Deque<Track>> queues = new ConcurrentHashMap<>();
  private final ConcurrentMap<UUID, Deque<Track>> history = new ConcurrentHashMap<>();
  private final ConcurrentMap<UUID, Track> current = new ConcurrentHashMap<>();
  private final ConcurrentMap<UUID, RepeatMode> repeatModes = new ConcurrentHashMap<>();
  private final ConcurrentMap<UUID, Boolean> shuffles = new ConcurrentHashMap<>();

  /** Tracks which players already had their history loaded from the DB this session. */
  private final java.util.Set<UUID> historyLoaded =
      java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

  public MusicQueueManager(VibesInDevPlugin plugin) {
    this.plugin = plugin;
  }

  public void enqueue(UUID player, Track track) {
    Deque<Track> queue = queues.computeIfAbsent(player, ignored -> new ArrayDeque<>());

    int queueLimit = plugin.getConfig().getInt("limits.max-queue-size", 100);

    if (queue.size() >= queueLimit) {
      throw new IllegalStateException("Queue is full (maximum " + queueLimit + " tracks).");
    }

    queue.addLast(track);
  }

  public void pushFront(UUID player, Track track) {
    if (track == null) return;
    Deque<Track> queue = queues.computeIfAbsent(player, ignored -> new ArrayDeque<>());
    queue.addFirst(track);
  }

  public Track advance(UUID player, Track completedTrack, boolean naturalCompletion) {
    Track peeked = peekNext(player, completedTrack, naturalCompletion);
    if (peeked == null) {
      return null;
    }

    RepeatMode repeat = getRepeatMode(player);

    if (naturalCompletion && repeat == RepeatMode.TRACK && completedTrack != null) {
      return completedTrack;
    }

    if (naturalCompletion && repeat == RepeatMode.QUEUE && completedTrack != null) {
      enqueue(player, completedTrack);
    }

    Deque<Track> queue = queues.get(player);
    if (queue != null && !queue.isEmpty()) {
      Track track = queue.pollFirst();
      if (queue.isEmpty()) {
        queues.remove(player);
      }
      return track;
    }
    return null;
  }

  public Track peekNext(UUID player, Track completedTrack, boolean naturalCompletion) {
    RepeatMode repeat = getRepeatMode(player);

    if (naturalCompletion && repeat == RepeatMode.TRACK && completedTrack != null) {
      return completedTrack;
    }

    Deque<Track> queue = queues.get(player);
    if (queue == null || queue.isEmpty()) {
      if (naturalCompletion && repeat == RepeatMode.QUEUE && completedTrack != null) {
        return completedTrack;
      }
      return null;
    }

    return queue.peekFirst();
  }

  public Track dequeue(UUID player) {
    return advance(player, null, false);
  }

  public RepeatMode getRepeatMode(UUID player) {
    return repeatModes.getOrDefault(player, RepeatMode.OFF);
  }

  public void setRepeatMode(UUID player, RepeatMode mode) {
    repeatModes.put(player, mode);
  }

  public boolean isShuffle(UUID player) {
    return shuffles.getOrDefault(player, false);
  }

  public void setShuffle(UUID player, boolean enabled) {
    boolean wasEnabled = isShuffle(player);
    shuffles.put(player, enabled);

    if (enabled && !wasEnabled) {
      Deque<Track> queue = queues.get(player);
      if (queue != null && queue.size() > 1) {
        List<Track> list = new ArrayList<>(queue);
        java.util.Collections.shuffle(list);
        queue.clear();
        queue.addAll(list);
      }
    }
  }

  public void setCurrent(UUID player, Track track) {
    if (track == null) {
      current.remove(player);
    } else {
      current.put(player, track);
    }
  }

  public Track current(UUID player) {
    return current.get(player);
  }

  public void addHistory(UUID player, Track track) {
    if (track == null) {
      return;
    }

    Deque<Track> playerHistory = history.computeIfAbsent(player, ignored -> new ArrayDeque<>());

    playerHistory.addLast(track);

    // Keep in-memory history bounded.
    int historyLimit = plugin.getConfig().getInt("playback.history-limit", 50);
    while (playerHistory.size() > historyLimit) {
      playerHistory.pollFirst();
    }

    // Persist to the SQLite database asynchronously.
    final UUID playerCopy = player;
    final Track trackCopy = track;
    plugin
        .getServer()
        .getScheduler()
        .runTaskAsynchronously(plugin, () -> plugin.cache().persistHistory(playerCopy, trackCopy));
  }

  public Track previous(UUID player) {
    Deque<Track> playerHistory = history.get(player);

    if (playerHistory == null) {
      return null;
    }

    Track track = playerHistory.pollLast();

    if (playerHistory.isEmpty()) {
      history.remove(player);
    }

    return track;
  }

  public List<Track> list(UUID player) {
    Deque<Track> queue = queues.get(player);

    if (queue == null) {
      return List.of();
    }

    return List.copyOf(queue);
  }

  /**
   * Returns the player's history. On the first call per session, loads persisted history from
   * SQLite so it survives server restarts.
   */
  public List<Track> history(UUID player) {
    ensureHistoryLoaded(player);
    Deque<Track> playerHistory = history.get(player);
    if (playerHistory == null) {
      return List.of();
    }
    return List.copyOf(playerHistory);
  }

  /**
   * Loads history from the database into the in-memory deque, but only once per player per server
   * session.
   */
  private void ensureHistoryLoaded(UUID player) {
    if (historyLoaded.contains(player)) {
      return;
    }
    historyLoaded.add(player);

    List<Track> persisted = plugin.cache().loadHistory(player);
    if (persisted.isEmpty()) {
      return;
    }

    Deque<Track> deque = history.computeIfAbsent(player, ignored -> new ArrayDeque<>());
    // persisted is newest-first; we want oldest-first in the deque (tail = most recent)
    // so reverse it before adding
    List<Track> reversed = new ArrayList<>(persisted);
    java.util.Collections.reverse(reversed);
    synchronized (deque) {
      if (deque.isEmpty()) { // Only seed if we have no in-session data yet
        deque.addAll(reversed);
        int historyLimit = plugin.getConfig().getInt("playback.history-limit", 50);
        while (deque.size() > historyLimit) {
          deque.pollFirst();
        }
      }
    }
  }

  public boolean remove(UUID player, int index) {
    Deque<Track> queue = queues.get(player);
    if (queue == null || index < 0 || index >= queue.size()) {
      return false;
    }

    List<Track> list = new ArrayList<>(queue);
    list.remove(index);
    queue.clear();
    queue.addAll(list);
    if (queue.isEmpty()) {
      queues.remove(player);
    }
    return true;
  }

  public boolean move(UUID player, int from, int to) {
    Deque<Track> queue = queues.get(player);
    if (queue == null || from < 0 || from >= queue.size() || to < 0 || to >= queue.size()) {
      return false;
    }

    List<Track> list = new ArrayList<>(queue);
    Track track = list.remove(from);
    list.add(to, track);
    queue.clear();
    queue.addAll(list);
    return true;
  }

  public void clearQueue(UUID player) {
    queues.remove(player);
  }

  public void clearHistory(UUID player) {
    history.remove(player);
    historyLoaded.remove(player);
    plugin
        .getServer()
        .getScheduler()
        .runTaskAsynchronously(plugin, () -> plugin.cache().clearPlayerHistory(player));
  }

  public void clear(UUID player) {
    queues.remove(player);
    history.remove(player);
    current.remove(player);
  }

  public void shutdown() {
    queues.clear();
    history.clear();
    current.clear();
  }
}

package dev.redbeastsam.vibesindev.playback;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import dev.redbeastsam.vibesindev.VibesInDevPlugin;
import dev.redbeastsam.vibesindev.audio.YtDlpManager;
import dev.redbeastsam.vibesindev.cache.TrackCache;
import dev.redbeastsam.vibesindev.metrics.MetricsManager;
import dev.redbeastsam.vibesindev.model.Track;
import dev.redbeastsam.vibesindev.network.MusicNetworkManager;
import dev.redbeastsam.vibesindev.queue.MusicQueueManager;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MusicPlaybackManagerTest {
  @Mock private VibesInDevPlugin plugin;
  @Mock private Server server;
  @Mock private Player player;
  @Mock private MusicNetworkManager networkManager;
  @Mock private MusicQueueManager queueManager;
  @Mock private MetricsManager metricsManager;
  @Mock private TrackCache cache;
  @Mock private YtDlpManager ytDlp;
  @Mock private dev.redbeastsam.vibesindev.audio.FFmpegManager ffmpeg;

  private MusicPlaybackManager playbackManager;
  private final UUID playerId = UUID.randomUUID();

  @BeforeEach
  @SuppressWarnings("null")
  void setUp() {
    // Only lenient stubs or those definitely needed in setup
    lenient().when(plugin.getServer()).thenReturn(server);
    lenient().when(plugin.network()).thenReturn(networkManager);
    lenient().when(plugin.queues()).thenReturn(queueManager);
    lenient().when(plugin.metrics()).thenReturn(metricsManager);
    lenient().when(plugin.cache()).thenReturn(cache);
    lenient().when(plugin.ffmpeg()).thenReturn(ffmpeg);
    lenient().when(ffmpeg.convert(any(), any())).thenReturn(new CompletableFuture<>());
    lenient().when(player.getUniqueId()).thenReturn(playerId);
    lenient().when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));

    org.bukkit.configuration.file.FileConfiguration mockConfig =
        mock(org.bukkit.configuration.file.FileConfiguration.class);
    lenient().when(plugin.getConfig()).thenReturn(mockConfig);
    lenient().when(mockConfig.getBoolean(anyString(), anyBoolean())).thenReturn(false);
    lenient().when(mockConfig.getLong(anyString(), anyLong())).thenReturn(5000L);
    lenient().when(mockConfig.getInt(anyString(), anyInt())).thenReturn(10);

    org.bukkit.scheduler.BukkitScheduler scheduler =
        mock(org.bukkit.scheduler.BukkitScheduler.class);
    lenient().when(server.getScheduler()).thenReturn(scheduler);

    playbackManager = new MusicPlaybackManager(plugin);
  }

  @Test
  void testPlay_WhenAlreadyPlaying_AddsToQueue() {
    Track track = new Track("vid2", "Another Song", "Another Artist", 200L, "");
    when(networkManager.isPlaying(player)).thenReturn(true);

    playbackManager.play(player, track);

    verify(queueManager).enqueue(playerId, track);
    verify(player).sendMessage(anyString());
  }

  @Test
  void testNext_WhenNextExists_PlaysNext() {
    Track nextTrack = new Track("vid3", "Next Song", "Next Artist", 300L, "");
    when(queueManager.advance(eq(playerId), any(), eq(false))).thenReturn(nextTrack);

    // This will attempt to prepare the next track. Let's return a dummy future so it doesn't break
    when(cache.hasAudio(anyString())).thenReturn(false);
    when(plugin.ytDlp()).thenReturn(ytDlp);
    when(ytDlp.downloadAudio(any(Track.class), any())).thenReturn(new CompletableFuture<>());

    playbackManager.next(player);

    verify(networkManager).stop(player);
    verify(queueManager).setCurrent(playerId, nextTrack);
  }

  @Test
  void testNext_WhenNoNext_JustStops() {
    when(queueManager.advance(eq(playerId), any(), eq(false))).thenReturn(null);

    playbackManager.next(player);

    verify(networkManager).stop(player);
    verify(queueManager).setCurrent(playerId, null);
  }

  @Test
  void testPrevious_WhenPreviousExists_PlaysPrevious() {
    Track prevTrack = new Track("vid0", "Prev Song", "Prev Artist", 150L, "");
    when(queueManager.previous(playerId)).thenReturn(prevTrack);

    when(cache.hasAudio(anyString())).thenReturn(false);
    when(plugin.ytDlp()).thenReturn(ytDlp);
    when(ytDlp.downloadAudio(any(Track.class), any())).thenReturn(new CompletableFuture<>());

    playbackManager.previous(player);

    verify(networkManager).stop(player);
    verify(queueManager).setCurrent(playerId, prevTrack);
  }

  @Test
  void testPlay_RapidSpam_HandledCorrectly() {
    Track track = new Track("spam", "Spam Song", "Artist", 100L, "");
    when(networkManager.isPlaying(player)).thenReturn(true);

    for (int i = 0; i < 50; i++) {
      playbackManager.play(player, track);
    }

    verify(queueManager, times(50)).enqueue(playerId, track);
    // Note: activePreparations handles deduplication when not playing.
    // For queueing, it's just a fast synchronous operation.
  }

  @Test
  void testPrepareTrack_WhenCacheCorrupted_InvalidatesCache() {
    // Arrange: cache has audio, but metadata title == artist (old regex bug)
    Track requestedTrack = new Track("spotify1", "Real Title", "Real Artist", 200L, "");
    Track corruptCached =
        new Track(
            "spotify1",
            "Real Artist",
            "Real Artist",
            200L,
            "spotify-lazy:Real Artist - Real Artist::200");

    when(cache.hasAudio("spotify1")).thenReturn(true);
    when(cache.readMetadataQuietly("spotify1")).thenReturn(corruptCached);
    when(plugin.ytDlp()).thenReturn(ytDlp);
    when(ytDlp.downloadAudio(any(Track.class), any())).thenReturn(new CompletableFuture<>());

    // Act: play track
    playbackManager.play(player, requestedTrack);

    // Assert: cache.invalidate was invoked for spotify1
    verify(cache).invalidate("spotify1");
  }

  @Test
  void testStartTrack_StaleCallback_IsIgnored() {
    // Arrange
    Track requestedTrack = new Track("stale", "Stale", "Artist", 100L, "");
    Track newTrack = new Track("new", "New", "Artist", 200L, "");

    // Simulating the scenario where the network callback resolves `stale`,
    // but the user has already skipped to `new`.
    lenient().when(queueManager.current(playerId)).thenReturn(newTrack);

    // Act
    // We will call the private startTrack method via a sneaky workaround, or
    // since it's private, we can't test it directly unless we trigger it.
    // Let's trigger it by calling next() and making the future complete immediately.
    lenient()
        .when(queueManager.advance(eq(playerId), any(), anyBoolean()))
        .thenReturn(requestedTrack);

    CompletableFuture<java.nio.file.Path> future = new CompletableFuture<>();
    lenient().when(cache.hasAudio(anyString())).thenReturn(false);
    lenient().when(plugin.ytDlp()).thenReturn(ytDlp);
    lenient().when(ytDlp.downloadAudio(any(Track.class), any())).thenReturn(future);

    playbackManager.next(player);

    // Now user plays newTrack, changing the current track
    lenient().when(queueManager.current(playerId)).thenReturn(newTrack);

    // Callback completes
    future.complete(null);

    // Verify networkManager.stream is NEVER called because the track is stale
    verify(networkManager, never()).stream(eq(player), eq(requestedTrack), any());
  }
}

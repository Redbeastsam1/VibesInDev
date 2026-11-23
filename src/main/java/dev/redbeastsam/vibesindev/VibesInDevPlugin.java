package dev.redbeastsam.vibesindev;

import dev.redbeastsam.vibesindev.audio.FFmpegManager;
import dev.redbeastsam.vibesindev.audio.YtDlpManager;
import dev.redbeastsam.vibesindev.cache.TrackCache;
import dev.redbeastsam.vibesindev.command.MusicCommand;
import dev.redbeastsam.vibesindev.gui.HistoryMenu;
import dev.redbeastsam.vibesindev.gui.MusicMenu;
import dev.redbeastsam.vibesindev.gui.NowPlayingMenu;
import dev.redbeastsam.vibesindev.gui.PartyInfoMenu;
import dev.redbeastsam.vibesindev.gui.PlaylistsMenu;
import dev.redbeastsam.vibesindev.gui.QueueMenu;
import dev.redbeastsam.vibesindev.metrics.MetricsManager;
import dev.redbeastsam.vibesindev.network.MusicNetworkManager;
import dev.redbeastsam.vibesindev.party.MusicPartyListener;
import dev.redbeastsam.vibesindev.party.MusicPartyManager;
import dev.redbeastsam.vibesindev.playback.MusicPlaybackManager;
import dev.redbeastsam.vibesindev.playlist.MusicPlaylistManager;
import dev.redbeastsam.vibesindev.queue.MusicQueueManager;
import dev.redbeastsam.vibesindev.recommendation.RecommendationManager;
import dev.redbeastsam.vibesindev.source.MusicSourceManager;
import dev.redbeastsam.vibesindev.spotify.SpotifyAuthBookListener;
import dev.redbeastsam.vibesindev.spotify.SpotifyClient;
import org.bukkit.plugin.java.JavaPlugin;

public final class VibesInDevPlugin extends JavaPlugin {
  private YtDlpManager ytDlp;
  private FFmpegManager ffmpeg;
  private TrackCache cache;
  private MusicQueueManager queues;
  private MusicPlaybackManager playback;
  private MusicNetworkManager network;
  private SpotifyClient spotify;
  private MusicSourceManager sources;
  private MusicPartyManager parties;
  private PartyInfoMenu partyInfoMenu;
  private MusicMenu musicMenu;
  private NowPlayingMenu nowPlayingMenu;
  private QueueMenu queueMenu;
  private PlaylistsMenu playlistsMenu;
  private MusicPlaylistManager playlists;
  private MetricsManager metrics;
  private RecommendationManager recommendations;
  private HistoryMenu historyMenu;

  @Override
  public void onEnable() {
    saveDefaultConfig();

    int configVer = getConfig().getInt("config-version", 1);
    if (configVer < 2) {
      getLogger()
          .info(
              "[VibesInDev] Running on config-version "
                  + configVer
                  + ". Active recommendation features upgraded to Recommendation Engine v2"
                  + " standards.");
    }

    cache = new TrackCache(this);
    metrics = new MetricsManager(this);
    ytDlp = new YtDlpManager(this);
    ffmpeg = new FFmpegManager(this);
    queues = new MusicQueueManager(this);
    parties = new MusicPartyManager(this);
    playlists = new MusicPlaylistManager(this);
    partyInfoMenu = new PartyInfoMenu(this);
    musicMenu = new MusicMenu(this);
    nowPlayingMenu = new NowPlayingMenu(this);
    queueMenu = new QueueMenu(this);
    playlistsMenu = new PlaylistsMenu(this);
    historyMenu = new HistoryMenu(this);
    getServer().getPluginManager().registerEvents(historyMenu, this);
    getServer().getPluginManager().registerEvents(partyInfoMenu, this);
    getServer().getPluginManager().registerEvents(musicMenu, this);
    getServer().getPluginManager().registerEvents(nowPlayingMenu, this);
    getServer().getPluginManager().registerEvents(queueMenu, this);
    getServer().getPluginManager().registerEvents(playlistsMenu, this);
    getServer().getPluginManager().registerEvents(new MusicPartyListener(this), this);

    boolean spotifyEnabled = getConfig().getBoolean("spotify.enabled", true);

    if (spotifyEnabled) {
      spotify = new SpotifyClient(this, ytDlp);
      getServer().getPluginManager().registerEvents(new SpotifyAuthBookListener(this), this);
      if (recommendations != null) {
        getServer().getPluginManager().registerEvents(recommendations, this);
      }
    }

    sources = new MusicSourceManager(ytDlp, spotify);
    network = new MusicNetworkManager(this);

    /*
     * Playback must exist before the network starts
     * reporting stream completions.
     */
    playback = new MusicPlaybackManager(this);
    recommendations = new RecommendationManager(this);

    network.setPlaybackManager(playback);

    network.enable();

    MusicCommand command = new MusicCommand(this);

    if (getCommand("music") != null) {
      getCommand("music").setExecutor(command);

      getCommand("music").setTabCompleter(command);
    }

    getLogger().info("VibesInDev Music enabled.");

    getLogger().info("Audio pipeline: sources -> yt-dlp -> FFmpeg -> cache -> Fabric client.");

    if (spotify != null) {
      getLogger()
          .info(
              spotify.isConfigured()
                  ? "Spotify source enabled."
                  : "Spotify source enabled but not configured.");
    }
  }

  @Override
  public void onDisable() {
    if (network != null) {
      network.disable();
    }

    if (playback != null) {
      /*
       * Playback manager has no external executor
       * to shut down; network handles active streams.
       */
    }

    if (parties != null) {
      parties.shutdown();
    }

    if (queues != null) {
      queues.shutdown();
    }

    if (ytDlp != null) {
      ytDlp.shutdown();
    }

    if (ffmpeg != null) {
      ffmpeg.shutdown();
    }

    if (spotify != null) {
      spotify.shutdown();
    }

    if (recommendations != null) {
      recommendations.shutdown();
    }
  }

  public YtDlpManager ytDlp() {
    return ytDlp;
  }

  public FFmpegManager ffmpeg() {
    return ffmpeg;
  }

  public TrackCache cache() {
    return cache;
  }

  public MusicQueueManager queues() {
    return queues;
  }

  public MusicPlaybackManager playback() {
    return playback;
  }

  public MusicNetworkManager network() {
    return network;
  }

  public SpotifyClient spotify() {
    return spotify;
  }

  public MusicSourceManager sources() {
    return sources;
  }

  public PartyInfoMenu partyInfoMenu() {
    return partyInfoMenu;
  }

  public MusicMenu musicMenu() {
    return musicMenu;
  }

  public NowPlayingMenu nowPlayingMenu() {
    return nowPlayingMenu;
  }

  public QueueMenu queueMenu() {
    return queueMenu;
  }

  public PlaylistsMenu playlistsMenu() {
    return playlistsMenu;
  }

  public MusicPartyManager parties() {
    return parties;
  }

  public MetricsManager metrics() {
    return metrics;
  }

  public HistoryMenu historyMenu() {
    return historyMenu;
  }

  public MusicPlaylistManager playlists() {
    return playlists;
  }

  public RecommendationManager recommendations() {
    return recommendations;
  }
}

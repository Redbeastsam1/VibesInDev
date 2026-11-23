package dev.redbeastsam.vibesindev.source;

import dev.redbeastsam.vibesindev.model.Track;
import dev.redbeastsam.vibesindev.spotify.SpotifyClient;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class MusicSourceManager {
  private final List<MusicSource> sources = new ArrayList<>();

  public MusicSourceManager(
      dev.redbeastsam.vibesindev.audio.YtDlpManager ytDlp, SpotifyClient spotify) {
    sources.add(new YouTubeSource(ytDlp));
    sources.add(new SoundCloudSource(ytDlp));

    if (spotify != null) {
      sources.add(new SpotifySource(spotify));
    }
  }

  public CompletableFuture<List<Track>> resolve(String input) {
    for (MusicSource source : sources) {
      if (source.supports(input)) {
        return source.resolve(input);
      }
    }

    return CompletableFuture.failedFuture(
        new IllegalArgumentException(
            "Unsupported music source. Supported: YouTube, SoundCloud and Spotify."));
  }
}

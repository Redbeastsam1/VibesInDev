package dev.redbeastsam.vibesindev.source;

import dev.redbeastsam.vibesindev.audio.YtDlpManager;
import dev.redbeastsam.vibesindev.model.Track;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class YouTubeSource implements MusicSource {
  private final YtDlpManager ytDlp;

  public YouTubeSource(YtDlpManager ytDlp) {
    this.ytDlp = ytDlp;
  }

  @Override
  public boolean supports(String input) {
    String url = input.toLowerCase();
    return url.contains("youtube.com/") || url.contains("youtu.be/");
  }

  @Override
  public CompletableFuture<List<Track>> resolve(String input) {
    return ytDlp.isPlaylistUrl(input)
        ? ytDlp.resolvePlaylist(input)
        : ytDlp.resolve(input).thenApply(List::of);
  }
}

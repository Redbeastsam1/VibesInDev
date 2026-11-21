package dev.vibesindev.vibesmusic.source;

import dev.vibesindev.vibesmusic.model.Track;
import dev.vibesindev.vibesmusic.spotify.SpotifyClient;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class SpotifySource implements MusicSource {
  private final SpotifyClient spotify;

  public SpotifySource(SpotifyClient spotify) {
    this.spotify = spotify;
  }

  @Override
  public boolean supports(String input) {
    if (input == null) return false;
    String lower = input.toLowerCase();
    return lower.contains("open.spotify.com/") || lower.startsWith("spotify:");
  }

  @Override
  public CompletableFuture<List<Track>> resolve(String input) {
    String lower = input.toLowerCase();
    if (lower.contains("/track/") || lower.contains(":track:")) {
      return spotify.resolveTrack(input).thenApply(List::of);
    }

    if (lower.contains("/playlist/") || lower.contains(":playlist:")) {
      return spotify.resolvePlaylist(input);
    }

    if (lower.contains("/album/") || lower.contains(":album:")) {
      return spotify.resolveAlbum(input);
    }

    return CompletableFuture.failedFuture(
        new IllegalArgumentException(
            "Unsupported Spotify URL. Use a track, playlist, or album URL."));
  }
}

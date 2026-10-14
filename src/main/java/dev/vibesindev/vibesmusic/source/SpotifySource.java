package dev.vibesindev.vibesmusic.source;

import dev.vibesindev.vibesmusic.model.Track;
import dev.vibesindev.vibesmusic.spotify.SpotifyClient;
import dev.vibesindev.vibesmusic.spotify.SpotifyTrack;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class SpotifySource implements MusicSource {

    private final SpotifyClient spotify;

    public SpotifySource(SpotifyClient spotify) {
        this.spotify = spotify;
    }

    @Override
    public boolean supports(String input) {
        return input.toLowerCase().contains("open.spotify.com/");
    }

    @Override
    public CompletableFuture<List<Track>> resolve(String input) {
        if (input.toLowerCase().contains("/track/")) {
            return spotify.resolveTrack(input).thenApply(List::of);
        }

        if (input.toLowerCase().contains("/playlist/")) {
            return spotify.resolvePlaylist(input);
        }

        if (input.toLowerCase().contains("/album/")) {
            return spotify.resolveAlbum(input);
        }

        return CompletableFuture.failedFuture(
                new IllegalArgumentException(
                        "Unsupported Spotify URL. Use a track, playlist, or album URL."
                )
        );
    }
}

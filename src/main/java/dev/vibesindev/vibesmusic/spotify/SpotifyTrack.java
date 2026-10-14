package dev.vibesindev.vibesmusic.spotify;

public record SpotifyTrack(
        String id,
        String title,
        String artist,
        long durationSeconds
) {
}

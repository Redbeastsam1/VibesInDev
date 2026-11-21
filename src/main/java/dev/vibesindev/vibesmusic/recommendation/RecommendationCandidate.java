package dev.vibesindev.vibesmusic.recommendation;

import dev.vibesindev.vibesmusic.model.Track;

/**
 * Immutable candidate track evaluated by the recommendation engine.
 *
 * @param track the candidate track metadata
 * @param score the calculated multi-objective ranking score (0.0 to 1.0+)
 * @param source the algorithmic stream that surfaced this candidate
 * @param reason human-readable explanation for debugging / UI
 * @param cachedLocally whether the audio file is already present in TrackCache
 */
public record RecommendationCandidate(
    Track track, double score, Source source, String reason, boolean cachedLocally)
    implements Comparable<RecommendationCandidate> {
  public enum Source {
    /** Stream A: Seed artist top catalog tracks from Spotify. */
    SPOTIFY_ARTIST_TOP,
    /** Stream B: Spotify micro-genre search clustering. */
    SPOTIFY_GENRE,
    /** Stream C: YouTube deep neural Radio Mix (RD{id}). */
    YOUTUBE_RADIO,
    /** Stream D: Server collaborative filtering (co-play graph from SQLite history). */
    SERVER_COLLABORATIVE,
    /** Stream E: Familiar library tracks (playlists, likes, history, cached Spotify tracks). */
    FAMILIAR,
    /** Fallback: Most popular / trending tracks across the server. */
    SERVER_TRENDING,
    /** Epsilon-greedy exploratory candidate outside core favorites. */
    DISCOVERY
  }

  @Override
  public int compareTo(RecommendationCandidate other) {
    // Higher score ranks first
    return Double.compare(other.score, this.score);
  }
}

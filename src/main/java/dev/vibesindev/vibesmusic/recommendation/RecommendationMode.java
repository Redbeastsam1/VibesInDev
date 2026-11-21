package dev.vibesindev.vibesmusic.recommendation;

/** High-level listening mode for candidate pool partitioning and scoring weights. */
public enum RecommendationMode {
  /** Exploits player's personal library (playlists, likes, history, Spotify). */
  FAMILIAR,
  /** Balances familiarity, genre continuity, and collaborative filtering. */
  SIMILAR,
  /** Explores new artists and horizons while maintaining acoustic continuity. */
  EXPLORE
}

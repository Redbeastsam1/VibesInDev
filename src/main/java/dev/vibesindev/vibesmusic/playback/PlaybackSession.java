package dev.vibesindev.vibesmusic.playback;

import dev.vibesindev.vibesmusic.model.Track;
import java.util.UUID;

public class PlaybackSession {
  private final UUID sessionId;
  private final UUID targetId;
  private final Track track;
  private final long generation;
  private PlaybackState state = PlaybackState.IDLE;
  private long startedAt = -1;
  private long pausedAt = -1;
  private long pauseDuration = 0;
  private UUID streamId;

  public PlaybackSession(UUID targetId, Track track, long generation) {
    this.sessionId = UUID.randomUUID();
    this.targetId = targetId;
    this.track = track;
    this.generation = generation;
  }

  public UUID getSessionId() {
    return sessionId;
  }

  public UUID getTargetId() {
    return targetId;
  }

  public Track getTrack() {
    return track;
  }

  public long getGeneration() {
    return generation;
  }

  public PlaybackState getState() {
    return state;
  }

  public UUID getStreamId() {
    return streamId;
  }

  public void setStreamId(UUID streamId) {
    this.streamId = streamId;
  }

  public void setState(PlaybackState state) {
    this.state = state;

    long now = System.currentTimeMillis();
    if (state == PlaybackState.PLAYING) {
      if (startedAt == -1) {
        startedAt = now;
      } else if (pausedAt != -1) {
        pauseDuration += (now - pausedAt);
        pausedAt = -1;
      }
    } else if (state == PlaybackState.PAUSED) {
      if (pausedAt == -1) {
        pausedAt = now;
      }
    }
  }

  public long getPositionMs() {
    if (startedAt == -1) return 0;
    long end = (pausedAt != -1) ? pausedAt : System.currentTimeMillis();
    return end - startedAt - pauseDuration;
  }

  public void seek(long positionMs) {
    long now = System.currentTimeMillis();
    if (pausedAt != -1) {
      startedAt = pausedAt - positionMs;
      pauseDuration = 0;
    } else {
      startedAt = now - positionMs;
      pauseDuration = 0;
    }
  }
}

package dev.redbeastsam.vibesindev.metrics;

import dev.redbeastsam.vibesindev.VibesInDevPlugin;
import java.util.concurrent.atomic.AtomicLong;

public final class MetricsManager {
  @SuppressWarnings("unused")
  private final VibesInDevPlugin plugin;
  private final AtomicLong tracksRequested = new AtomicLong();
  private final AtomicLong tracksPlayed = new AtomicLong();
  private final AtomicLong tracksCompleted = new AtomicLong();
  private final AtomicLong tracksFailed = new AtomicLong();
  private final AtomicLong cacheHits = new AtomicLong();
  private final AtomicLong cacheMisses = new AtomicLong();
  private final AtomicLong ytDlpFailures = new AtomicLong();
  private final AtomicLong ffmpegFailures = new AtomicLong();
  private final AtomicLong disconnects = new AtomicLong();

  public MetricsManager(VibesInDevPlugin plugin) {
    this.plugin = plugin;
  }

  public void incrementTracksRequested() {
    tracksRequested.incrementAndGet();
  }

  public void incrementTracksPlayed() {
    tracksPlayed.incrementAndGet();
  }

  public void incrementTracksCompleted() {
    tracksCompleted.incrementAndGet();
  }

  public void incrementTracksFailed() {
    tracksFailed.incrementAndGet();
  }

  public void incrementCacheHits() {
    cacheHits.incrementAndGet();
  }

  public void incrementCacheMisses() {
    cacheMisses.incrementAndGet();
  }

  public void incrementYtDlpFailures() {
    ytDlpFailures.incrementAndGet();
  }

  public void incrementFfmpegFailures() {
    ffmpegFailures.incrementAndGet();
  }

  public void incrementDisconnects() {
    disconnects.incrementAndGet();
  }

  public long getTracksRequested() {
    return tracksRequested.get();
  }

  public long getTracksPlayed() {
    return tracksPlayed.get();
  }

  public long getTracksCompleted() {
    return tracksCompleted.get();
  }

  public long getTracksFailed() {
    return tracksFailed.get();
  }

  public long getCacheHits() {
    return cacheHits.get();
  }

  public long getCacheMisses() {
    return cacheMisses.get();
  }

  public long getYtDlpFailures() {
    return ytDlpFailures.get();
  }

  public long getFfmpegFailures() {
    return ffmpegFailures.get();
  }

  public long getDisconnects() {
    return disconnects.get();
  }
}

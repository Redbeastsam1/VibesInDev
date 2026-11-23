package dev.redbeastsam.vibesindev.model;

public record Track(
    String id, String title, String artist, long durationSeconds, String sourceUrl) {
  public long durationMs() {
    return durationSeconds * 1000L;
  }

  public String durationText() {
    if (durationSeconds <= 0) {
      return "Unknown";
    }

    long h = durationSeconds / 3600;
    long m = (durationSeconds % 3600) / 60;
    long s = durationSeconds % 60;

    return h > 0 ? "%02d:%02d:%02d".formatted(h, m, s) : "%02d:%02d".formatted(m, s);
  }
}

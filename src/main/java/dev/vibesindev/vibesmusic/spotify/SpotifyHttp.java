package dev.vibesindev.vibesmusic.spotify;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Resilient HTTP execution helper for Spotify Web API with circuit breaker, transparent 401 token
 * refresh, 429 rate-limiting backoff, and 404 feature deprecation.
 */
public final class SpotifyHttp {
  public enum Feature {
    USER_TOP_ARTISTS,
    USER_TOP_TRACKS,
    USER_RECENTLY_PLAYED,
    USER_SAVED_TRACKS,
    USER_PLAYLISTS,
    ARTIST_LOOKUP,
    ARTIST_TOP_TRACKS,
    RELATED_ARTISTS,
    GENRE_SEARCH,
    PLAYLIST_SEARCH
  }

  private final Set<Feature> disabledFeatures = ConcurrentHashMap.newKeySet();
  private final AtomicLong rateLimitResetTimeMs = new AtomicLong(0L);

  public SpotifyHttp() {
    // RELATED_ARTISTS is globally deprecated and returns 404 on Spotify; disable by default
    disabledFeatures.add(Feature.RELATED_ARTISTS);
  }

  public boolean isFeatureDisabled(Feature feature) {
    return feature != null && disabledFeatures.contains(feature);
  }

  public void disableFeature(Feature feature) {
    if (feature != null) {
      disabledFeatures.add(feature);
    }
  }

  public boolean isRateLimited() {
    return System.currentTimeMillis() < rateLimitResetTimeMs.get();
  }

  public long getRateLimitRemainingMs() {
    long diff = rateLimitResetTimeMs.get() - System.currentTimeMillis();
    return Math.max(0L, diff);
  }

  /**
   * Executes an HTTP request with retry logic: - Checks rate limit circuit breaker before network
   * call - On 401, invokes tokenRefresher and retries with new token - On 429, parses Retry-After
   * and trips rate-limit circuit breaker - On 403, 404, or 410, marks feature as disabled to
   * prevent further spam - On 5xx, retries once after short delay
   */
  public CompletableFuture<HttpResponse<String>> executeWithRetry(
      HttpClient client,
      HttpRequest request,
      Feature feature,
      Function<String, HttpRequest> requestWithNewToken,
      CompletableFuture<String> tokenRefreshFuture) {
    if (feature != null && disabledFeatures.contains(feature)) {
      return CompletableFuture.failedFuture(
          new FeatureDisabledException("Feature disabled: " + feature));
    }

    if (isRateLimited()) {
      return CompletableFuture.failedFuture(
          new RateLimitException(
              "Rate limit breaker open for " + getRateLimitRemainingMs() + "ms"));
    }

    return client
        .sendAsync(request, HttpResponse.BodyHandlers.ofString())
        .thenCompose(
            response -> {
              int status = response.statusCode();

              // 1. Success
              if (status >= 200 && status < 300) {
                return CompletableFuture.completedFuture(response);
              }

              // 2. 401 Unauthorized -> transparent refresh
              if (status == 401 && tokenRefreshFuture != null && requestWithNewToken != null) {
                return tokenRefreshFuture.thenCompose(
                    newToken -> {
                      if (newToken == null || newToken.isBlank()) {
                        return CompletableFuture.completedFuture(response);
                      }
                      HttpRequest retried = requestWithNewToken.apply(newToken);
                      return client.sendAsync(retried, HttpResponse.BodyHandlers.ofString());
                    });
              }

              // 3. 429 Rate Limited -> trip circuit breaker
              if (status == 429) {
                long retryAfterSec = 5; // default 5 seconds
                Optional<String> retryHeader = response.headers().firstValue("Retry-After");
                if (retryHeader.isPresent()) {
                  try {
                    retryAfterSec = Long.parseLong(retryHeader.get().trim());
                  } catch (NumberFormatException ignored) {
                  }
                }
                rateLimitResetTimeMs.set(System.currentTimeMillis() + retryAfterSec * 1000L);
                return CompletableFuture.completedFuture(response);
              }

              // 4. 403, 404, or 410 -> disable feature for session
              if (status == 403 || status == 404 || status == 410) {
                if (feature != null) {
                  disabledFeatures.add(feature);
                }
                return CompletableFuture.completedFuture(response);
              }

              // 5. 5xx Server Error -> retry once
              if (status >= 500 && status < 600) {
                return client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
              }

              return CompletableFuture.completedFuture(response);
            });
  }

  public static class FeatureDisabledException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public FeatureDisabledException(String message) {
      super(message);
    }
  }

  public static class RateLimitException extends IOException {
    private static final long serialVersionUID = 1L;

    public RateLimitException(String message) {
      super(message);
    }
  }
}

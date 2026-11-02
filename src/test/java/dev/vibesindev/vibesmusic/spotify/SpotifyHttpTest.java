package dev.vibesindev.vibesmusic.spotify;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class SpotifyHttpTest {

    private HttpServer server;
    private int port;
    private HttpClient client;
    private SpotifyHttp spotifyHttp;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        port = server.getAddress().getPort();
        client = HttpClient.newHttpClient();
        spotifyHttp = new SpotifyHttp();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("401 Unauthorized triggers transparent token refresh and succeeds on retry")
    void test401TransparentTokenRefresh() throws Exception {
        AtomicInteger attempts = new AtomicInteger(0);

        server.createContext("/test-refresh", exchange -> {
            int attempt = attempts.incrementAndGet();
            String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
            if (attempt == 1) {
                // First call: unauthorized
                exchange.sendResponseHeaders(401, -1);
            } else {
                // Second call: verify refreshed token
                if ("Bearer refreshed_token_123".equals(authHeader)) {
                    byte[] resp = "{\"status\":\"ok\"}".getBytes();
                    exchange.sendResponseHeaders(200, resp.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(resp);
                    }
                } else {
                    exchange.sendResponseHeaders(403, -1);
                }
            }
        });

        HttpRequest initialRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/test-refresh"))
                .header("Authorization", "Bearer stale_token")
                .GET()
                .build();

        CompletableFuture<HttpResponse<String>> future = spotifyHttp.executeWithRetry(
                client,
                initialRequest,
                SpotifyHttp.Feature.USER_TOP_ARTISTS,
                newToken -> HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/test-refresh"))
                        .header("Authorization", "Bearer " + newToken)
                        .GET()
                        .build(),
                CompletableFuture.completedFuture("refreshed_token_123")
        );

        HttpResponse<String> response = future.get();
        assertEquals(200, response.statusCode());
        assertEquals("{\"status\":\"ok\"}", response.body());
        assertEquals(2, attempts.get(), "Should have called server twice (initial + refreshed)");
    }

    @Test
    @DisplayName("429 with Retry-After trips circuit breaker and rejects immediate subsequent call")
    void test429RateLimitCircuitBreaker() throws Exception {
        AtomicInteger serverHits = new AtomicInteger(0);

        server.createContext("/test-429", exchange -> {
            serverHits.incrementAndGet();
            exchange.getResponseHeaders().add("Retry-After", "3");
            exchange.sendResponseHeaders(429, -1);
        });

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/test-429"))
                .GET()
                .build();

        // 1. Initial call receives 429 and trips breaker
        HttpResponse<String> firstResp = spotifyHttp.executeWithRetry(
                client, request, SpotifyHttp.Feature.GENRE_SEARCH, null, null
        ).get();
        assertEquals(429, firstResp.statusCode());
        assertTrue(spotifyHttp.isRateLimited(), "Breaker must be tripped after 429");
        assertTrue(spotifyHttp.getRateLimitRemainingMs() > 1000L);

        // 2. Immediate second call is rejected locally by breaker without hitting server
        CompletableFuture<HttpResponse<String>> secondCall = spotifyHttp.executeWithRetry(
                client, request, SpotifyHttp.Feature.GENRE_SEARCH, null, null
        );

        ExecutionException ex = assertThrows(ExecutionException.class, secondCall::get);
        assertInstanceOf(SpotifyHttp.RateLimitException.class, ex.getCause());
        assertEquals(1, serverHits.get(), "Server must NOT be hit when rate limit breaker is open");
    }

    @Test
    @DisplayName("404 endpoint response disables feature for session and skips subsequent calls")
    void test404DisablesFeatureForSession() throws Exception {
        AtomicInteger serverHits = new AtomicInteger(0);

        server.createContext("/test-404", exchange -> {
            serverHits.incrementAndGet();
            exchange.sendResponseHeaders(404, -1);
        });

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/test-404"))
                .GET()
                .build();

        assertFalse(spotifyHttp.isFeatureDisabled(SpotifyHttp.Feature.USER_SAVED_TRACKS));

        // 1. First call returns 404
        HttpResponse<String> firstResp = spotifyHttp.executeWithRetry(
                client, request, SpotifyHttp.Feature.USER_SAVED_TRACKS, null, null
        ).get();
        assertEquals(404, firstResp.statusCode());
        assertTrue(spotifyHttp.isFeatureDisabled(SpotifyHttp.Feature.USER_SAVED_TRACKS), "Feature must be disabled on 404");

        // 2. Subsequent call is rejected locally without sending HTTP request
        CompletableFuture<HttpResponse<String>> secondCall = spotifyHttp.executeWithRetry(
                client, request, SpotifyHttp.Feature.USER_SAVED_TRACKS, null, null
        );

        ExecutionException ex = assertThrows(ExecutionException.class, secondCall::get);
        assertInstanceOf(SpotifyHttp.FeatureDisabledException.class, ex.getCause());
        assertEquals(1, serverHits.get(), "Server must not be contacted when feature is disabled");
    }

    @Test
    @DisplayName("Related artists feature is disabled by default")
    void testRelatedArtistsDisabledByDefault() {
        assertTrue(spotifyHttp.isFeatureDisabled(SpotifyHttp.Feature.RELATED_ARTISTS));
    }
}

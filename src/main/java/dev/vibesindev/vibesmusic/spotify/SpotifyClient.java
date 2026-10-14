package dev.vibesindev.vibesmusic.spotify;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.vibesindev.vibesmusic.VibesInDevPlugin;
import dev.vibesindev.vibesmusic.audio.YtDlpManager;
import dev.vibesindev.vibesmusic.model.Track;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SpotifyClient {

    private static final String ACCOUNTS_URL =
            "https://accounts.spotify.com";

    private static final String API_URL =
            "https://api.spotify.com/v1";

    private static final Pattern STRING =
            Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

    private static final Pattern NUMBER =
            Pattern.compile("\"([^\"]+)\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)");

    private static final Pattern ARRAY_OBJECT =
            Pattern.compile(
                    "\"item\"\\s*:\\s*\\{(.*?)\\}",
                    Pattern.DOTALL
            );

    private final VibesInDevPlugin plugin;
    private final YtDlpManager ytDlp;
    private final HttpClient http;

    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;
    private final String market;

    private volatile String clientToken;
    private volatile long clientTokenExpiresAt;

    private volatile String userAccessToken;
    private volatile String userRefreshToken;
    private volatile long userTokenExpiresAt;

    private HttpServer callbackServer;

    public SpotifyClient(
            VibesInDevPlugin plugin,
            YtDlpManager ytDlp
    ) {
        this.plugin = plugin;
        this.ytDlp = ytDlp;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        clientId = plugin.getConfig().getString(
                "spotify.client-id",
                ""
        );

        clientSecret = plugin.getConfig().getString(
                "spotify.client-secret",
                ""
        );

        redirectUri = plugin.getConfig().getString(
                "spotify.redirect-uri",
                "http://127.0.0.1:8888/callback"
        );

        market = plugin.getConfig().getString(
                "spotify.market",
                "IN"
        );
    }

    public boolean isConfigured() {
        return !clientId.isBlank() && !clientSecret.isBlank();
    }

    public CompletableFuture<Track> resolveTrack(String input) {
        return getTrackId(input)
                .thenCompose(this::getTrack)
                .thenApply(track -> new Track(
                        track.id(),
                        track.title(),
                        track.artist(),
                        track.durationSeconds(),
                        "spotify-lazy:" + track.artist() + " - " + track.title() + "::" + track.durationSeconds()
                ));
    }

    public CompletableFuture<List<Track>> resolvePlaylist(String input) {
        if (!isConfigured()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException(
                            "Spotify is not configured. Add client-id and client-secret to config.yml."
                    )
            );
        }

        String playlistId = extractId(input, "/playlist/");

        return getPlaylistItems(playlistId)
                .thenCompose(items -> {
                    if (items.isEmpty()) {
                        return CompletableFuture.completedFuture(List.of());
                    }

                    int maxTracks = plugin.getConfig().getInt("spotify.playlists.max-tracks", 500);
                    if (items.size() > maxTracks) {
                        items = items.subList(0, maxTracks);
                    }

                    List<Track> lazyTracks = new ArrayList<>();
                    for (SpotifyTrack item : items) {
                        lazyTracks.add(new Track(
                                item.id(),
                                item.title(),
                                item.artist(),
                                item.durationSeconds(),
                                "spotify-lazy:" + item.artist() + " - " + item.title() + "::" + item.durationSeconds()
                        ));
                    }
                    return CompletableFuture.completedFuture(lazyTracks);
                });
    }

    public CompletableFuture<List<Track>> resolveAlbum(String input) {
        if (!isConfigured()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException(
                            "Spotify is not configured. Add client-id and client-secret to config.yml."
                    )
            );
        }

        String albumId = extractId(input, "/album/");

        return getAlbumItems(albumId)
                .thenCompose(items -> {
                    if (items.isEmpty()) {
                        return CompletableFuture.completedFuture(List.of());
                    }

                    int maxTracks = plugin.getConfig().getInt("spotify.playlists.max-tracks", 500);
                    if (items.size() > maxTracks) {
                        items = items.subList(0, maxTracks);
                    }

                    List<Track> lazyTracks = new ArrayList<>();
                    for (SpotifyTrack item : items) {
                        lazyTracks.add(new Track(
                                item.id(),
                                item.title(),
                                item.artist(),
                                item.durationSeconds(),
                                "spotify-lazy:" + item.artist() + " - " + item.title() + "::" + item.durationSeconds()
                        ));
                    }
                    return CompletableFuture.completedFuture(lazyTracks);
                });
    }

    public synchronized String beginLogin() {
        if (!isConfigured()) {
            throw new IllegalStateException(
                    "Spotify client-id and client-secret are not configured."
            );
        }

        try {
            if (callbackServer != null) {
                callbackServer.stop(0);
            }

            URI redirect = URI.create(redirectUri);

            if (!"127.0.0.1".equalsIgnoreCase(redirect.getHost())) {
                throw new IllegalStateException(
                        "For the built-in login flow, spotify.redirect-uri "
                                + "must use 127.0.0.1."
                );
            }

            String state = randomUrlSafe(32);

            callbackServer = HttpServer.create(
                    new InetSocketAddress(
                            redirect.getHost(),
                            redirect.getPort() > 0
                                    ? redirect.getPort()
                                    : 80
                    ),
                    0
            );

            callbackServer.createContext(
                    redirect.getPath(),
                    exchange -> handleCallback(exchange, state)
            );

            callbackServer.start();

            String scope =
                    "playlist-read-private playlist-read-collaborative";

            String url =
                    ACCOUNTS_URL
                            + "/authorize?client_id="
                            + enc(clientId)
                            + "&response_type=code"
                            + "&redirect_uri="
                            + enc(redirectUri)
                            + "&scope="
                            + enc(scope)
                            + "&state="
                            + enc(state);

            return url;

        } catch (IOException e) {
            throw new IllegalStateException(
                    "Could not start Spotify callback server: "
                            + e.getMessage(),
                    e
            );
        }
    }

    private void handleCallback(
            HttpExchange exchange,
            String expectedState
    ) throws IOException {

        String query = exchange.getRequestURI().getRawQuery();

        String code = queryValue(query, "code");
        String state = queryValue(query, "state");
        String error = queryValue(query, "error");

        String response;

        if (error != null) {
            response = "Spotify authorization failed: " + error;
        } else if (code == null || !expectedState.equals(state)) {
            response = "Spotify authorization failed: invalid callback.";
        } else {
            try {
                exchangeToken(code);
                response =
                        "VibesInDev Spotify authorization complete. "
                                + "You can close this window.";
            } catch (Exception e) {
                response =
                        "Spotify authorization failed: "
                                + e.getMessage();
            }
        }

        byte[] body = response.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set(
                "Content-Type",
                "text/plain; charset=UTF-8"
        );

        exchange.sendResponseHeaders(
                200,
                body.length
        );

        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }

        if (callbackServer != null) {
            callbackServer.stop(0);
            callbackServer = null;
        }
    }

    private synchronized void exchangeToken(String code)
            throws IOException, InterruptedException {

        String body =
                "grant_type=authorization_code"
                        + "&code=" + enc(code)
                        + "&redirect_uri=" + enc(redirectUri);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ACCOUNTS_URL + "/api/token"))
                .timeout(Duration.ofSeconds(15))
                .header(
                        "Authorization",
                        "Basic " + Base64.getEncoder().encodeToString(
                                (clientId + ":" + clientSecret)
                                        .getBytes(StandardCharsets.UTF_8)
                        )
                )
                .header(
                        "Content-Type",
                        "application/x-www-form-urlencoded"
                )
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response =
                http.send(
                        request,
                        HttpResponse.BodyHandlers.ofString()
                );

        if (response.statusCode() != 200) {
            throw new IOException(
                    "Spotify token request returned HTTP "
                            + response.statusCode()
            );
        }

        userAccessToken = string(
                response.body(),
                "access_token"
        );

        userRefreshToken = string(
                response.body(),
                "refresh_token"
        );

        long expiresIn = numberLong(
                response.body(),
                "expires_in",
                3600
        );

        userTokenExpiresAt =
                System.currentTimeMillis()
                        + Math.max(60, expiresIn - 60) * 1000L;

        plugin.getLogger().info(
                "Spotify authorization completed."
        );
    }

    private CompletableFuture<String> getClientToken() {
        if (!isConfigured()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException(
                            "Spotify is not configured. Set client-id and client-secret in config.yml."
                    )
            );
        }
        if (clientToken != null
                && System.currentTimeMillis() < clientTokenExpiresAt) {
            return CompletableFuture.completedFuture(clientToken);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                String body = "grant_type=client_credentials";

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(ACCOUNTS_URL + "/api/token"))
                        .timeout(Duration.ofSeconds(15))
                        .header(
                                "Authorization",
                                "Basic " + Base64.getEncoder().encodeToString(
                                        (clientId + ":" + clientSecret)
                                                .getBytes(StandardCharsets.UTF_8)
                                )
                        )
                        .header(
                                "Content-Type",
                                "application/x-www-form-urlencoded"
                        )
                        .POST(
                                HttpRequest.BodyPublishers.ofString(body)
                        )
                        .build();

                HttpResponse<String> response =
                        http.send(
                                request,
                                HttpResponse.BodyHandlers.ofString()
                        );

                if (response.statusCode() != 200) {
                    plugin.getLogger().severe("[VibesInDev] Spotify client credentials failed (HTTP "
                            + response.statusCode() + "). Check client-id and client-secret in config.yml. Body: "
                            + response.body().substring(0, Math.min(200, response.body().length())));
                    throw new IOException(
                            "Spotify token request returned HTTP " + response.statusCode()
                                    + " — check client-id and client-secret in config.yml"
                    );
                }

                clientToken = string(
                        response.body(),
                        "access_token"
                );

                if (clientToken == null || clientToken.isBlank()) {
                    plugin.getLogger().severe("[VibesInDev] Spotify returned no access_token. Body: " + response.body().substring(0, Math.min(200, response.body().length())));
                    throw new IOException("Spotify returned no access token — check client-id and client-secret in config.yml");
                }

                long expiresIn = numberLong(
                        response.body(),
                        "expires_in",
                        3600
                );

                clientTokenExpiresAt =
                        System.currentTimeMillis()
                                + Math.max(60, expiresIn - 60) * 1000L;

                return clientToken;

            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(
                        "Could not authenticate with Spotify",
                        e
                );
            }
        });
    }

    private CompletableFuture<String> getTrackId(String input) {
        String id = extractId(input, "/track/");

        if (id == null || id.isBlank()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException(
                            "Could not read Spotify track ID."
                    )
            );
        }

        return CompletableFuture.completedFuture(id);
    }

    private CompletableFuture<SpotifyTrack> getTrack(String id) {
        return getClientToken().thenCompose(token -> {
            String url =
                    API_URL
                            + "/tracks/"
                            + enc(id)
                            + "?market="
                            + enc(market);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header(
                            "Authorization",
                            "Bearer " + token
                    )
                    .GET()
                    .build();

            return http.sendAsync(
                            request,
                            HttpResponse.BodyHandlers.ofString()
                    )
                    .thenApply(response -> {
                        if (response.statusCode() != 200) {
                            throw new IllegalStateException(
                                    "Spotify track request returned HTTP "
                                            + response.statusCode()
                            );
                        }

                        String json = response.body();
                        com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();

                        String title = root.has("name") && !root.get("name").isJsonNull()
                                ? root.get("name").getAsString() : "Unknown Title";

                        long durationMs = root.has("duration_ms") && !root.get("duration_ms").isJsonNull()
                                ? root.get("duration_ms").getAsLong() : 0L;

                        String artist = "Unknown Artist";
                        if (root.has("artists") && root.get("artists").isJsonArray()) {
                            com.google.gson.JsonArray artists = root.getAsJsonArray("artists");
                            java.util.List<String> artistList = new java.util.ArrayList<>();
                            for (com.google.gson.JsonElement el : artists) {
                                if (el.isJsonObject()) {
                                    com.google.gson.JsonObject a = el.getAsJsonObject();
                                    if (a.has("name") && !a.get("name").isJsonNull()) {
                                        artistList.add(a.get("name").getAsString());
                                    }
                                }
                            }
                            if (!artistList.isEmpty()) {
                                artist = String.join(", ", artistList);
                            }
                        }

                        plugin.getLogger().info("[VibesInDev] Resolved Spotify track: " + artist + " - " + title + " (" + (durationMs / 1000) + "s)");

                        return new SpotifyTrack(
                                id,
                                title,
                                artist,
                                durationMs / 1000
                        );
                    });
        });
    }

    private CompletableFuture<List<SpotifyTrack>> getPlaylistItems(
            String playlistId
    ) {
        return getClientToken().thenCompose(
                token -> getPlaylistPage(
                        playlistId,
                        token,
                        0,
                        new ArrayList<>()
                )
        );
    }

    private CompletableFuture<List<SpotifyTrack>> getPlaylistPage(
            String playlistId,
            String token,
            int offset,
            List<SpotifyTrack> result
    ) {
        String url =
                API_URL
                        + "/playlists/"
                        + enc(playlistId)
                        + "/items?limit=50&offset="
                        + offset
                        + "&market="
                        + enc(market);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header(
                        "Authorization",
                        "Bearer " + token
                )
                .GET()
                .build();

        return http.sendAsync(
                        request,
                        HttpResponse.BodyHandlers.ofString()
                )
                .thenCompose(response -> {
                    if (response.statusCode() == 401) {
                        plugin.getLogger().warning("[VibesInDev] Spotify 401 — client token may be invalid. Check client-id/client-secret in config.yml. Body: " + response.body());
                        return CompletableFuture.failedFuture(
                                new IllegalStateException(
                                        "Spotify authorization failed (401). Check your client-id and client-secret in config.yml."
                                )
                        );
                    }

                    if (response.statusCode() == 403) {
                        plugin.getLogger().warning("[VibesInDev] Spotify 403 for playlist " + playlistId + " — may be private.");
                        return CompletableFuture.failedFuture(
                                new IllegalStateException(
                                        "Cannot access this playlist. It may be private. "
                                                + "Only public playlists are supported without login."
                                )
                        );
                    }

                    if (response.statusCode() != 200) {
                        plugin.getLogger().warning("[VibesInDev] Spotify playlist HTTP " + response.statusCode() + " body: " + response.body().substring(0, Math.min(300, response.body().length())));
                        return CompletableFuture.failedFuture(
                                new IllegalStateException(
                                        "Spotify playlist request returned HTTP "
                                                + response.statusCode()
                                )
                        );
                    }

                    String json = response.body();
                    List<SpotifyTrack> page =
                            parsePlaylistItems(json);

                    result.addAll(page);

                    int total = (int) numberLong(
                            json,
                            "total",
                            result.size()
                    );

                    if (result.size() >= total
                            || page.isEmpty()) {
                        return CompletableFuture.completedFuture(
                                result
                        );
                    }

                    return getPlaylistPage(
                            playlistId,
                            token,
                            offset + page.size(),
                            result
                    );
                });
    }

    private CompletableFuture<String> getUserToken() {
        if (hasUserAuthorization()
                && System.currentTimeMillis() < userTokenExpiresAt) {
            return CompletableFuture.completedFuture(
                    userAccessToken
            );
        }

        if (userRefreshToken == null
                || userRefreshToken.isBlank()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException(
                            "Spotify login required. Run /music spotify login."
                    )
            );
        }

        return CompletableFuture.failedFuture(
                new IllegalStateException(
                        "Spotify access token expired. Run /music spotify login again."
                )
        );
    }

    private boolean hasUserAuthorization() {
        return userAccessToken != null
                && !userAccessToken.isBlank();
    }

    private CompletableFuture<List<SpotifyTrack>> getAlbumItems(
            String albumId
    ) {
        return getClientToken().thenCompose(
                token -> getAlbumPage(
                        albumId,
                        token,
                        0,
                        new ArrayList<>()
                )
        );
    }

    private CompletableFuture<List<SpotifyTrack>> getAlbumPage(
            String albumId,
            String token,
            int offset,
            List<SpotifyTrack> result
    ) {
        String url =
                API_URL
                        + "/albums/"
                        + enc(albumId)
                        + "/tracks?limit=50&offset="
                        + offset
                        + "&market="
                        + enc(market);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header(
                        "Authorization",
                        "Bearer " + token
                )
                .GET()
                .build();

        return http.sendAsync(
                        request,
                        HttpResponse.BodyHandlers.ofString()
                )
                .thenCompose(response -> {
                    if (response.statusCode() != 200) {
                        return CompletableFuture.failedFuture(
                                new IllegalStateException(
                                        "Spotify album request returned HTTP "
                                                + response.statusCode()
                                )
                        );
                    }

                    String json = response.body();
                    List<SpotifyTrack> page =
                            parseAlbumItems(json);

                    result.addAll(page);

                    int total = (int) numberLong(
                            json,
                            "total",
                            result.size()
                    );

                    if (result.size() >= total
                            || page.isEmpty()) {
                        return CompletableFuture.completedFuture(
                                result
                        );
                    }

                    return getAlbumPage(
                            albumId,
                            token,
                            offset + page.size(),
                            result
                    );
                });
    }

    private static List<SpotifyTrack> parseAlbumItems(String json) {
        List<SpotifyTrack> result = new ArrayList<>();
        com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        if (!obj.has("items") || !obj.get("items").isJsonArray()) {
            return result;
        }
        com.google.gson.JsonArray items = obj.getAsJsonArray("items");
        for (com.google.gson.JsonElement el : items) {
            if (!el.isJsonObject()) continue;
            com.google.gson.JsonObject track = el.getAsJsonObject();
            
            String title = track.has("name") && !track.get("name").isJsonNull() ? track.get("name").getAsString() : null;
            String id = track.has("id") && !track.get("id").isJsonNull() ? track.get("id").getAsString() : null;
            long durationMs = track.has("duration_ms") && !track.get("duration_ms").isJsonNull() ? track.get("duration_ms").getAsLong() : 0;
            
            String artist = "Unknown";
            if (track.has("artists") && track.get("artists").isJsonArray() && track.getAsJsonArray("artists").size() > 0) {
                com.google.gson.JsonObject firstArtist = track.getAsJsonArray("artists").get(0).getAsJsonObject();
                if (firstArtist.has("name") && !firstArtist.get("name").isJsonNull()) {
                    artist = firstArtist.get("name").getAsString();
                }
            }
            
            if (id != null && title != null && !id.isBlank()) {
                result.add(new SpotifyTrack(id, title, artist, durationMs / 1000));
            }
        }
        return result;
    }

    private static List<SpotifyTrack> parsePlaylistItems(String json) {
        List<SpotifyTrack> result = new ArrayList<>();
        try {
            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            com.google.gson.JsonArray items = root.has("items") && root.get("items").isJsonArray()
                    ? root.getAsJsonArray("items") : null;
            if (items == null) return result;

            for (com.google.gson.JsonElement el : items) {
                if (!el.isJsonObject()) continue;
                com.google.gson.JsonObject item = el.getAsJsonObject();

                // Each playlist item wraps the actual track in a "track" field
                if (!item.has("track") || item.get("track").isJsonNull()) continue;
                com.google.gson.JsonObject track = item.getAsJsonObject("track");

                // Skip local files (no Spotify ID)
                if (!track.has("id") || track.get("id").isJsonNull()) continue;
                String id = track.get("id").getAsString();
                if (id.isBlank()) continue;

                String title = track.has("name") && !track.get("name").isJsonNull()
                        ? track.get("name").getAsString() : null;
                long durationMs = track.has("duration_ms") && !track.get("duration_ms").isJsonNull()
                        ? track.get("duration_ms").getAsLong() : 0L;

                String artist = "Unknown";
                if (track.has("artists") && track.get("artists").isJsonArray()) {
                    com.google.gson.JsonArray artists = track.getAsJsonArray("artists");
                    if (!artists.isEmpty() && artists.get(0).isJsonObject()) {
                        com.google.gson.JsonObject first = artists.get(0).getAsJsonObject();
                        if (first.has("name") && !first.get("name").isJsonNull()) {
                            artist = first.get("name").getAsString();
                        }
                    }
                }

                if (title != null && !title.isBlank()) {
                    result.add(new SpotifyTrack(id, title, artist, durationMs / 1000));
                }
            }
        } catch (Exception e) {
            // Malformed JSON — return whatever we collected so far
        }
        return result;
    }

    private static String firstArtistName(String json) {
        int artistsIndex = json.indexOf("\"artists\"");

        if (artistsIndex < 0) {
            return "Unknown";
        }

        String tail = json.substring(artistsIndex);

        String name = string(tail, "name");

        return name == null || name.isBlank()
                ? "Unknown"
                : name;
    }

    private static String extractId(
            String input,
            String marker
    ) {
        if (input == null || marker == null) {
            return null;
        }

        int index = input.indexOf(marker);
        if (index < 0) {
            // Also support Spotify URI syntax (e.g. spotify:track:...)
            if (marker.startsWith("/") && marker.endsWith("/")) {
                String altMarker = ":" + marker.substring(1);
                index = input.indexOf(altMarker);
                if (index >= 0) {
                    marker = altMarker;
                } else {
                    return null;
                }
            } else {
                return null;
            }
        }

        String rest = input.substring(index + marker.length());
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '?' || c == '&' || c == '/' || c == '#' || Character.isWhitespace(c)) {
                end = i;
                break;
            }
        }

        String id = rest.substring(0, end);
        return id.isBlank() ? null : id;
    }

    private static String string(
            String json,
            String key
    ) {
        Matcher matcher = STRING.matcher(json);

        while (matcher.find()) {
            if (matcher.group(1).equals(key)) {
                return unescape(matcher.group(2));
            }
        }

        return null;
    }

    private static long numberLong(
            String json,
            String key,
            long fallback
    ) {
        Matcher matcher = NUMBER.matcher(json);

        while (matcher.find()) {
            if (matcher.group(1).equals(key)) {
                try {
                    return Math.round(
                            Double.parseDouble(
                                    matcher.group(2)
                            )
                    );
                } catch (NumberFormatException ignored) {
                    return fallback;
                }
            }
        }

        return fallback;
    }

    private static String unescape(String value) {
        return value
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\/", "/")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t");
    }

    private static String queryValue(
            String query,
            String key
    ) {
        if (query == null) {
            return null;
        }

        for (String part : query.split("&")) {
            String[] pieces = part.split("=", 2);

            if (pieces.length == 2
                    && pieces[0].equals(key)) {
                return java.net.URLDecoder.decode(
                        pieces[1],
                        StandardCharsets.UTF_8
                );
            }
        }

        return null;
    }

    private static String enc(String value) {
        return URLEncoder.encode(
                value,
                StandardCharsets.UTF_8
        );
    }

    private static String randomUrlSafe(int bytes) {
        byte[] data = new byte[bytes];
        new SecureRandom().nextBytes(data);

        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(data);
    }
}

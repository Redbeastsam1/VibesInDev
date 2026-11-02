package dev.vibesindev.vibesmusic.spotify;



import com.sun.net.httpserver.HttpExchange;

import com.sun.net.httpserver.HttpServer;

import dev.vibesindev.vibesmusic.VibesInDevPlugin;

import dev.vibesindev.vibesmusic.audio.YtDlpManager;

import dev.vibesindev.vibesmusic.model.Track;

import dev.vibesindev.vibesmusic.recommendation.PlayerTasteProfile;

import org.bukkit.ChatColor;

import org.bukkit.entity.Player;



import java.io.File;

import java.io.FileInputStream;

import java.io.FileOutputStream;

import java.io.IOException;

import java.io.OutputStream;

import java.net.InetSocketAddress;

import java.net.URI;

import java.net.URLEncoder;

import java.net.http.HttpClient;

import java.net.http.HttpRequest;

import java.net.http.HttpResponse;

import java.nio.charset.StandardCharsets;

import java.security.SecureRandom;

import java.time.Duration;

import java.util.ArrayList;

import java.util.Base64;

import java.util.Collections;

import java.util.List;

import java.util.Map;

import java.util.Properties;

import java.util.UUID;

import java.util.concurrent.CompletableFuture;

import java.util.concurrent.ConcurrentHashMap;

import java.util.regex.Matcher;

import java.util.regex.Pattern;

public final class SpotifyClient {



    public record UserSession(String accessToken, String refreshToken, long expiresAt) {}

    public record PendingState(UUID playerUuid, long expiresAt) {}
    public record SpotifyArtistSummary(String id, String name, List<String> genres) {}



    private final Map<UUID, UserSession> userSessions = new ConcurrentHashMap<>();

    private final Map<String, PendingState> pendingStates = new ConcurrentHashMap<>();
    private record PendingFragment(String fragment, long expireTimestamp) {}
    private final Map<UUID, PendingFragment> pendingCodeFragments = new ConcurrentHashMap<>();

    private final File usersDir;





    private static final String ACCOUNTS_URL =

            "https://accounts.spotify.com";



    private static final String API_URL =

            "https://api.spotify.com/v1";



    private static final Pattern STRING =

            Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");



    private static final Pattern NUMBER =

            Pattern.compile("\"([^\"]+)\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)");



    private final VibesInDevPlugin plugin;

    @SuppressWarnings("unused")

    private final YtDlpManager ytDlp;

    private final HttpClient http;
    private final SpotifyHttp spotifyHttp = new SpotifyHttp();

    private static final Map<String, String> KNOWN_ARTIST_IDS = new ConcurrentHashMap<>();

    public static void registerKnownArtist(String artistName, String artistId) {
        if (artistName != null && artistId != null && !artistName.isBlank() && !artistId.isBlank()) {
            KNOWN_ARTIST_IDS.put(dev.vibesindev.vibesmusic.recommendation.ArtistNames.normalize(artistName), artistId);
        }
    }

    public String getKnownArtistId(String artistName) {
        if (artistName == null || artistName.isBlank()) return null;
        return KNOWN_ARTIST_IDS.get(dev.vibesindev.vibesmusic.recommendation.ArtistNames.normalize(artistName));
    }

    public static String getKnownArtistIdStatic(String artistName) {
        if (artistName == null || artistName.isBlank()) return null;
        return KNOWN_ARTIST_IDS.get(dev.vibesindev.vibesmusic.recommendation.ArtistNames.normalize(artistName));
    }

    public static void clearKnownArtistIds() {
        KNOWN_ARTIST_IDS.clear();
    }



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

        this.usersDir = new File(plugin.getDataFolder(), "spotify_users");

        if (!usersDir.exists()) {

            usersDir.mkdirs();

        }

        loadAllUserSessions();

    }



    private static String escapeHtml(String input) {
        if (input == null) return "";
        return input.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&#x27;");
    }

    public boolean isConfigured() {

        return !clientId.isBlank() && !clientSecret.isBlank();

    }



    public synchronized void loadAllUserSessions() {

        // 1. Load per-player sessions from spotify_users/<uuid>.properties

        if (usersDir.isDirectory()) {

            File[] files = usersDir.listFiles((dir, name) -> name.endsWith(".properties"));

            if (files != null) {

                for (File f : files) {

                    try {

                        String name = f.getName().replace(".properties", "");

                        UUID uuid = UUID.fromString(name);

                        Properties props = new Properties();

                        try (FileInputStream in = new FileInputStream(f)) {

                            props.load(in);

                        }

                        String access = props.getProperty("access_token");

                        String refresh = props.getProperty("refresh_token");

                        long expires = Long.parseLong(props.getProperty("expires_at", "0"));

                        if (access != null && !access.isBlank()) {

                            userSessions.put(uuid, new UserSession(access, refresh, expires));

                        }

                    } catch (Exception ignored) {}

                }

            }

        }



        // 2. Load legacy single-token fallback if present

        try {

            File legacy = new File(plugin.getDataFolder(), ".spotify_tokens");

            if (legacy.exists()) {

                Properties props = new Properties();

                try (FileInputStream in = new FileInputStream(legacy)) {

                    props.load(in);

                }

                String access = props.getProperty("access_token");

                String refresh = props.getProperty("refresh_token");

                String expires = props.getProperty("expires_at");

                if (access != null && !access.isBlank()) {

                    this.userAccessToken = access;

                }

                if (refresh != null && !refresh.isBlank()) {

                    this.userRefreshToken = refresh;

                }

                if (expires != null) {

                    try {

                        this.userTokenExpiresAt = Long.parseLong(expires);

                    } catch (NumberFormatException ignored) {}

                }

            }

        } catch (Exception ignored) {}



        if (!userSessions.isEmpty()) {

            plugin.getLogger().info("[VibesInDev] Loaded " + userSessions.size() + " authenticated Spotify user session(s).");

        }

    }



    public synchronized void saveUserSession(UUID playerUuid, UserSession session) {

        if (playerUuid == null || session == null) return;

        userSessions.put(playerUuid, session);

        try {

            if (!usersDir.exists()) usersDir.mkdirs();

            File file = new File(usersDir, playerUuid.toString() + ".properties");

            Properties props = new Properties();

            if (session.accessToken() != null) props.setProperty("access_token", session.accessToken());

            if (session.refreshToken() != null) props.setProperty("refresh_token", session.refreshToken());

            props.setProperty("expires_at", String.valueOf(session.expiresAt()));

            props.setProperty("updated_at", String.valueOf(System.currentTimeMillis()));

            try (FileOutputStream out = new FileOutputStream(file)) {

                props.store(out, "Spotify User Session: " + playerUuid);

            }

        } catch (Exception e) {

            plugin.getLogger().warning("[VibesInDev] Could not save user Spotify session: " + e.getMessage());

        }

    }



    public synchronized boolean logoutPlayer(UUID playerUuid) {

        if (playerUuid == null) return false;

        UserSession removed = userSessions.remove(playerUuid);

        File file = new File(usersDir, playerUuid.toString() + ".properties");

        if (file.exists()) {

            file.delete();

        }

        return removed != null || file.exists();

    }



    private synchronized void saveTokens() {

        try {

            File dir = plugin.getDataFolder();

            if (!dir.exists()) dir.mkdirs();

            File file = new File(dir, ".spotify_tokens");

            Properties props = new Properties();

            if (userAccessToken != null) props.setProperty("access_token", userAccessToken);

            if (userRefreshToken != null) props.setProperty("refresh_token", userRefreshToken);

            props.setProperty("expires_at", String.valueOf(userTokenExpiresAt));

            try (FileOutputStream out = new FileOutputStream(file)) {

                props.store(out, "Spotify OAuth Tokens");

            }

        } catch (Exception ignored) {}

    }



    public boolean hasUserAuthorization(UUID playerUuid) {

        if (playerUuid != null && userSessions.containsKey(playerUuid)) {

            return true;

        }

        return (userAccessToken != null && !userAccessToken.isBlank())

                || (userRefreshToken != null && !userRefreshToken.isBlank());

    }





    @SuppressWarnings("null")

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

        return beginLogin(null);

    }



    public synchronized String beginLogin(Player player) {

        if (!isConfigured()) {

            throw new IllegalStateException(

                    "Spotify client-id and client-secret are not configured."

            );

        }



        try {

            if (callbackServer != null) {

                callbackServer.stop(0);

                callbackServer = null;

            }



            URI redirect = URI.create(redirectUri);

            String nonce = randomUrlSafe(32);

            String state = nonce + "_" + (player != null ? player.getUniqueId().toString() : "host");



            // Cache state for anti-CSRF validation (valid for 10 minutes)

            pendingStates.put(state, new PendingState(player != null ? player.getUniqueId() : null, System.currentTimeMillis() + 600_000L));



            // Dual-mode webserver: only start local HttpServer if enabled in config

            if (plugin.getConfig().getBoolean("spotify.webserver.enabled", true)) {

                try {

                    int port = redirect.getPort() > 0 ? redirect.getPort() : 8888;
                    String bindAddress = plugin.getConfig().getString("spotify.webserver.bind-address", "127.0.0.1");
                    callbackServer = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);

                    callbackServer.createContext(redirect.getPath(), exchange -> handleCallback(exchange, state, player));

                    callbackServer.start();



                    // Auto-shutdown callback server after 120 seconds to prevent lingering open sockets

                    long timeoutSec = plugin.getConfig().getLong("spotify.webserver.timeout-seconds", 120);

                    plugin.getServer().getScheduler().runTaskLater(plugin, () -> {

                        synchronized (SpotifyClient.this) {

                            if (callbackServer != null) {

                                callbackServer.stop(0);

                                callbackServer = null;

                            }

                        }

                    }, timeoutSec * 20L);

                } catch (Exception e) {

                    plugin.getLogger().warning("[VibesInDev] Could not start local Spotify HTTP callback server: " + e.getMessage() + ". Fallback: use /music spotify code <paste>.");

                }

            }



            // Expanded read-only scopes for playlists and taste profile ingestion

            String scope = "playlist-read-private playlist-read-collaborative user-top-read user-read-recently-played user-library-read";



            return ACCOUNTS_URL

                    + "/authorize?client_id="

                    + enc(clientId)

                    + "&response_type=code"

                    + "&redirect_uri="

                    + enc(redirectUri)

                    + "&scope="

                    + enc(scope)

                    + "&state="

                    + enc(state);



        } catch (Exception e) {

            throw new IllegalStateException("Could not generate Spotify authorization URL: " + e.getMessage(), e);

        }

    }





    private void handleCallback(

            HttpExchange exchange,

            String expectedState,

            Player player

    ) throws IOException {



        String query = exchange.getRequestURI().getRawQuery();



        String code = queryValue(query, "code");

        String state = queryValue(query, "state");

        String error = queryValue(query, "error");



        String response;



        if (error != null) {

            response = "Spotify authorization failed: " + escapeHtml(error);

        } else if (code == null || !expectedState.equals(state)) {

            response = "Spotify authorization failed: invalid callback.";

        } else {

            try {

                exchangeTokenForState(code, state, player);

                response =

                        "<!DOCTYPE html><html><body style='font-family:sans-serif;text-align:center;padding:50px;background:#121212;color:#1DB954;'>"

                                + "<h2>Spotify Connected Successfully!</h2>"

                                + "<p style='color:#ffffff;'>You can close this browser tab and return to Minecraft.</p>"

                                + "</body></html>";

            } catch (Exception e) {

                response =

                        "Spotify authorization failed: "

                                + e.getMessage();

            }

        }



        byte[] body = response.getBytes(StandardCharsets.UTF_8);



        exchange.getResponseHeaders().set(

                "Content-Type",

                "text/html; charset=UTF-8"

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





    /**

     * Universal zero-port-forwarding token exchange.

     * Accepts either a raw code string or the full redirect URL pasted from the player's browser.

     */

    public synchronized CompletableFuture<String> exchangeCodeForPlayer(Player player, String rawCodeOrUrl) {

        if (!isConfigured()) {

            return CompletableFuture.failedFuture(new IllegalStateException("Spotify is not configured."));

        }

        if (rawCodeOrUrl == null || rawCodeOrUrl.isBlank()) {

            return CompletableFuture.failedFuture(new IllegalArgumentException("Authorization code cannot be empty."));

        }



        String input = rawCodeOrUrl.trim();

        UUID pId = player != null ? player.getUniqueId() : null;

        if (pId != null && (input.startsWith("append ") || input.startsWith("+"))) {

            String fragment = input.startsWith("append ") ? input.substring(7).trim() : input.substring(1).trim();

            PendingFragment pf = pendingCodeFragments.get(pId);

            if (pf != null) {

                if (System.currentTimeMillis() < pf.expireTimestamp()) {
                    input = pf.fragment() + fragment;
                }

                pendingCodeFragments.remove(pId);

            }

        }

        final String savedInput = input;
        String code = input;

        String state = null;



        if (input.contains("code=")) {

            code = queryValue(input.contains("?") ? input.substring(input.indexOf('?') + 1) : input, "code");

            state = queryValue(input.contains("?") ? input.substring(input.indexOf('?') + 1) : input, "state");

        }



        if (code == null || code.isBlank()) {

            return CompletableFuture.failedFuture(new IllegalArgumentException("Could not extract authorization code from input."));

        }



        // Anti-CSRF verification if state is present

        if (state != null) {

            PendingState ps = pendingStates.remove(state);

            if (ps != null && ps.playerUuid() != null && player != null) {

                if (!ps.playerUuid().equals(player.getUniqueId())) {

                    return CompletableFuture.failedFuture(new SecurityException("Anti-CSRF validation failed: this code belongs to a different player!"));

                }

            }

        }



        final String finalCode = code;

        return CompletableFuture.supplyAsync(() -> {

            try {

                String body = "grant_type=authorization_code"

                        + "&code=" + enc(finalCode)

                        + "&redirect_uri=" + enc(redirectUri);



                HttpRequest request = HttpRequest.newBuilder()

                        .uri(URI.create(ACCOUNTS_URL + "/api/token"))

                        .timeout(Duration.ofSeconds(15))

                        .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(

                                (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8)

                        ))

                        .header("Content-Type", "application/x-www-form-urlencoded")

                        .POST(HttpRequest.BodyPublishers.ofString(body))

                        .build();



                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());



                if (response.statusCode() != 200) {

                    if (pId != null) {

                        pendingCodeFragments.put(pId, new PendingFragment(savedInput, System.currentTimeMillis() + 600_000L));

                    }

                    throw new IOException("Spotify token exchange failed (HTTP " + response.statusCode() + "): " + response.body());

                }

                if (pId != null) {

                    pendingCodeFragments.remove(pId);

                }



                String access = string(response.body(), "access_token");

                String refresh = string(response.body(), "refresh_token");

                long expiresIn = numberLong(response.body(), "expires_in", 3600);

                long expiresAt = System.currentTimeMillis() + Math.max(60, expiresIn - 60) * 1000L;



                if (access == null || access.isBlank()) {

                    throw new IOException("Spotify response contained no access_token.");

                }



                UserSession session = new UserSession(access, refresh, expiresAt);

                if (player != null) {

                    saveUserSession(player.getUniqueId(), session);

                    // Also set legacy fallback

                    this.userAccessToken = access;

                    this.userRefreshToken = refresh;

                    this.userTokenExpiresAt = expiresAt;



                    // Automatically sync taste profile in background

                    if (plugin.getConfig().getBoolean("recommendations.spotify-sync-on-login", true)) {

                        syncTasteProfile(player);

                    }

                }



                return "Spotify account successfully linked!";

            } catch (Exception e) {

                throw new RuntimeException("Failed to exchange Spotify authorization code: " + e.getMessage(), e);

            }

        });

    }



    private void exchangeTokenForState(String code, String state, Player player) throws IOException, InterruptedException {

        UUID targetUuid = player != null ? player.getUniqueId() : null;

        if (state != null) {

            PendingState ps = pendingStates.remove(state);

            if (ps != null && ps.playerUuid() != null) {

                targetUuid = ps.playerUuid();

            }

        }



        String body = "grant_type=authorization_code"

                + "&code=" + enc(code)

                + "&redirect_uri=" + enc(redirectUri);



        HttpRequest request = HttpRequest.newBuilder()

                .uri(URI.create(ACCOUNTS_URL + "/api/token"))

                .timeout(Duration.ofSeconds(15))

                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(

                        (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8)

                ))

                .header("Content-Type", "application/x-www-form-urlencoded")

                .POST(HttpRequest.BodyPublishers.ofString(body))

                .build();



        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {

            throw new IOException("Spotify token request returned HTTP " + response.statusCode());

        }



        String access = string(response.body(), "access_token");

        String refresh = string(response.body(), "refresh_token");

        long expiresIn = numberLong(response.body(), "expires_in", 3600);

        long expiresAt = System.currentTimeMillis() + Math.max(60, expiresIn - 60) * 1000L;



        UserSession session = new UserSession(access, refresh, expiresAt);

        if (targetUuid != null) {

            saveUserSession(targetUuid, session);

            Player targetPlayer = player != null ? player : plugin.getServer().getPlayer(targetUuid);

            if (targetPlayer != null && targetPlayer.isOnline()) {

                final Player p = targetPlayer;

                plugin.getServer().getScheduler().runTask(plugin, () -> {

                    p.sendMessage(ChatColor.GREEN + "✔ " + ChatColor.WHITE + "Spotify account successfully linked!");

                    if (plugin.getConfig().getBoolean("recommendations.spotify-sync-on-login", true)) {

                        syncTasteProfile(p);

                    }

                });

            }

        }

        this.userAccessToken = access;

        this.userRefreshToken = refresh;

        this.userTokenExpiresAt = expiresAt;

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



    @SuppressWarnings("null")

    private CompletableFuture<SpotifyTrack> getTrack(String id) {

        CompletableFuture<String> tokenFuture = hasUserAuthorization()

                ? getUserToken()

                : getClientToken();



        return tokenFuture.thenCompose(token -> {

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

        CompletableFuture<String> tokenFuture = hasUserAuthorization()

                ? getUserToken()

                : getClientToken();



        return tokenFuture.thenCompose(

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

                    if (response.statusCode() == 401) {

                        plugin.getLogger().warning("[VibesInDev] Spotify 401 — client token may be invalid. Check client-id/client-secret in config.yml. Body: " + response.body());

                        return CompletableFuture.failedFuture(

                                new IllegalStateException(

                                        "Spotify authorization failed (401). Check your client-id and client-secret in config.yml."

                                )

                        );

                    }



                    if (response.statusCode() == 403) {

                        plugin.getLogger().warning("[VibesInDev] Spotify 403 for playlist " + playlistId);

                        return CompletableFuture.failedFuture(

                                new IllegalStateException(

                                        "Cannot access this playlist (403 Forbidden). Run /music spotify login."

                                )

                        );

                    }



                    if (response.statusCode() == 404) {

                        plugin.getLogger().warning("[VibesInDev] Spotify playlist HTTP 404 for " + playlistId + " (userAuthorized=" + hasUserAuthorization() + ")");

                        if (!hasUserAuthorization()) {

                            return CompletableFuture.failedFuture(

                                    new IllegalStateException(

                                            "Spotify playlist not found or private. Run '/music spotify login' to access private/collaborative playlists, or make the playlist public in Spotify."

                                    )

                            );

                        } else {

                            return CompletableFuture.failedFuture(

                                    new IllegalStateException(

                                            "Spotify playlist not found (404). Verify the link or check playlist permissions in Spotify."

                                    )

                            );

                        }

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





    // =========================================================

    /**
     * Unified Track parser from Spotify track JsonObject (fixes F15).
     */
    public static dev.vibesindev.vibesmusic.model.Track parseTrack(com.google.gson.JsonObject tr) {
        if (tr == null) return null;
        String id = tr.has("id") && !tr.get("id").isJsonNull() ? tr.get("id").getAsString() : java.util.UUID.randomUUID().toString();
        String title = tr.has("name") && !tr.get("name").isJsonNull() ? tr.get("name").getAsString() : "Unknown Title";

        long dur = 0;
        if (tr.has("duration_ms") && !tr.get("duration_ms").isJsonNull()) {
            dur = tr.get("duration_ms").getAsLong() / 1000L;
        }

        String artist = "Unknown";
        if (tr.has("artists") && tr.get("artists").isJsonArray()) {
            com.google.gson.JsonArray arr = tr.getAsJsonArray("artists");
            java.util.List<String> artistNames = new java.util.ArrayList<>();
            for (com.google.gson.JsonElement aEl : arr) {
                if (aEl != null && aEl.isJsonObject()) {
                    com.google.gson.JsonObject aObj = aEl.getAsJsonObject();
                    if (aObj.has("name") && !aObj.get("name").isJsonNull()) {
                        String aName = aObj.get("name").getAsString();
                        artistNames.add(aName);
                        if (aObj.has("id") && !aObj.get("id").isJsonNull()) {
                            registerKnownArtist(aName, aObj.get("id").getAsString());
                        }
                    }
                }
            }
            if (!artistNames.isEmpty()) {
                artist = String.join(", ", artistNames);
            }
        }

        String sourceUrl = "spotify-lazy:" + artist + " - " + title + "::" + dur;
        if (tr.has("external_urls") && tr.get("external_urls").isJsonObject()) {
            com.google.gson.JsonObject ext = tr.getAsJsonObject("external_urls");
            if (ext.has("spotify") && !ext.get("spotify").isJsonNull()) {
                sourceUrl = ext.get("spotify").getAsString();
            }
        }

        return new dev.vibesindev.vibesmusic.model.Track(id, title, artist, dur, sourceUrl);
    }

    // PER-PLAYER TOKEN & TASTE PROFILE INGESTION

    // =========================================================



    public CompletableFuture<String> getUserToken(UUID playerUuid) {

        if (playerUuid != null) {

            UserSession session = userSessions.get(playerUuid);

            if (session != null) {

                if (session.accessToken() != null && !session.accessToken().isBlank()

                        && System.currentTimeMillis() < session.expiresAt()) {

                    return CompletableFuture.completedFuture(session.accessToken());

                }

                if (session.refreshToken() != null && !session.refreshToken().isBlank()) {

                    return refreshUserTokenForPlayer(playerUuid, session);

                }

            }

        }

        // Strict per-player token isolation (fixes F13): NEVER fall back to global server token!
        return CompletableFuture.completedFuture(null);

    }



    private CompletableFuture<String> refreshUserTokenForPlayer(UUID playerUuid, UserSession session) {

        String body = "grant_type=refresh_token&refresh_token=" + enc(session.refreshToken());



        HttpRequest request = HttpRequest.newBuilder()

                .uri(URI.create(ACCOUNTS_URL + "/api/token"))

                .timeout(Duration.ofSeconds(15))

                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(

                        (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8)

                ))

                .header("Content-Type", "application/x-www-form-urlencoded")

                .POST(HttpRequest.BodyPublishers.ofString(body))

                .build();



        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())

                .thenCompose(response -> {

                    if (response.statusCode() != 200) {

                        return CompletableFuture.failedFuture(

                                new IllegalStateException("Spotify session expired for player. Run /music spotify login again.")

                        );

                    }

                    String newAccess = string(response.body(), "access_token");

                    if (newAccess == null || newAccess.isBlank()) {

                        return CompletableFuture.failedFuture(

                                new IllegalStateException("Refreshed Spotify response contained no access_token.")

                        );

                    }

                    String newRefresh = string(response.body(), "refresh_token");

                    if (newRefresh == null || newRefresh.isBlank()) {

                        newRefresh = session.refreshToken();

                    }

                    long expiresIn = numberLong(response.body(), "expires_in", 3600);

                    long expiresAt = System.currentTimeMillis() + Math.max(60, expiresIn - 60) * 1000L;



                    UserSession updated = new UserSession(newAccess, newRefresh, expiresAt);

                    saveUserSession(playerUuid, updated);

                    return CompletableFuture.completedFuture(newAccess);

                });

    }



    /**

     * Fetches user's top artists from Spotify: time_range = short_term (4 weeks) or medium_term (6 months).

     */

    public CompletableFuture<List<String>> fetchUserTopArtists(UUID playerUuid, String timeRange) {

        return getUserToken(playerUuid).thenCompose(token -> {
            if (token == null) {
                return CompletableFuture.completedFuture(Collections.<String>emptyList());
            }

            String url = API_URL + "/me/top/artists?time_range=" + enc(timeRange != null ? timeRange : "medium_term") + "&limit=50";

            HttpRequest request = HttpRequest.newBuilder()

                    .uri(URI.create(url))

                    .timeout(Duration.ofSeconds(15))

                    .header("Authorization", "Bearer " + token)

                    .GET()

                    .build();



            return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())

                    .thenApply(response -> {

                        List<String> artists = new ArrayList<>();

                        if (response.statusCode() == 200) {

                            try {

                                com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(response.body()).getAsJsonObject();

                                if (root.has("items") && root.get("items").isJsonArray()) {

                                    for (com.google.gson.JsonElement el : root.getAsJsonArray("items")) {

                                        if (el.isJsonObject()) {

                                            com.google.gson.JsonObject obj = el.getAsJsonObject();

                                            if (obj.has("name") && !obj.get("name").isJsonNull()) {

                                                artists.add(obj.get("name").getAsString());

                                            }

                                        }

                                    }

                                }

                            } catch (Exception ignored) {}

                        }

                        return artists;

                    });

        });

    }



    /**

     * Fetches micro-genres for an artist from GET /v1/artists/{id}.

     */

    public CompletableFuture<List<String>> fetchArtistGenres(String artistId) {

        return getClientToken().thenCompose(token -> {

            String url = API_URL + "/artists/" + enc(artistId);

            HttpRequest request = HttpRequest.newBuilder()

                    .uri(URI.create(url))

                    .timeout(Duration.ofSeconds(15))

                    .header("Authorization", "Bearer " + token)

                    .GET()

                    .build();



            return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())

                    .thenApply(response -> {

                        List<String> genres = new ArrayList<>();

                        if (response.statusCode() == 200) {

                            try {

                                com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(response.body()).getAsJsonObject();

                                if (root.has("genres") && root.get("genres").isJsonArray()) {

                                    for (com.google.gson.JsonElement el : root.getAsJsonArray("genres")) {

                                        genres.add(el.getAsString());

                                    }

                                }

                            } catch (Exception ignored) {}

                        }

                        return genres;

                    });

        });

    }

    public boolean isFeatureAvailable(SpotifyHttp.Feature feature) {
        return spotifyHttp == null || !spotifyHttp.isFeatureDisabled(feature);
    }

    public CompletableFuture<List<String>> searchArtistGenres(String artistName) {
        if (artistName == null || artistName.isBlank() || !isFeatureAvailable(SpotifyHttp.Feature.ARTIST_LOOKUP)) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        return getClientToken().thenCompose(token -> {
            String url = API_URL + "/search?q=" + enc(artistName) + "&type=artist&limit=1";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();
            return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(res -> {
                        List<String> genres = new ArrayList<>();
                        if (res.statusCode() == 200 && res.body() != null) {
                            try {
                                com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(res.body()).getAsJsonObject();
                                if (root.has("artists") && root.getAsJsonObject("artists").has("items")) {
                                    com.google.gson.JsonArray items = root.getAsJsonObject("artists").getAsJsonArray("items");
                                    if (items.size() > 0) {
                                        com.google.gson.JsonObject artistObj = items.get(0).getAsJsonObject();
                                        if (artistObj.has("id") && !artistObj.get("id").isJsonNull()) {
                                            String foundId = artistObj.get("id").getAsString();
                                            registerKnownArtist(artistName, foundId);
                                            if (artistObj.has("name") && !artistObj.get("name").isJsonNull()) {
                                                registerKnownArtist(artistObj.get("name").getAsString(), foundId);
                                            }
                                        }
                                        if (artistObj.has("genres")) {
                                            for (com.google.gson.JsonElement el : artistObj.getAsJsonArray("genres")) {
                                                genres.add(el.getAsString());
                                            }
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                plugin.getLogger().warning("[VibesInDev] Failed to parse artist genres search: " + e.getMessage());
                            }
                        } else if (res.statusCode() == 404 || res.statusCode() == 403 || res.statusCode() == 410) {
                            spotifyHttp.disableFeature(SpotifyHttp.Feature.ARTIST_LOOKUP);
                        }
                        return genres;
                    }).exceptionally(ex -> Collections.emptyList());
        });
    }

    /**

     * Fetches artist top tracks from GET /v1/artists/{id}/top-tracks.

     */

    public CompletableFuture<List<Track>> getArtistTopTracks(String artistId) {
        if (artistId == null || artistId.isBlank() || !isFeatureAvailable(SpotifyHttp.Feature.ARTIST_TOP_TRACKS)) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        return getClientToken().thenCompose(token -> {
            String url = API_URL + "/artists/" + enc(artistId) + "/top-tracks?market=" + enc(market);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();

            return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        List<Track> tracks = new ArrayList<>();
                        if (response.statusCode() == 200 && response.body() != null) {
                            try {
                                com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(response.body()).getAsJsonObject();
                                if (root.has("tracks") && root.get("tracks").isJsonArray()) {
                                    for (com.google.gson.JsonElement el : root.getAsJsonArray("tracks")) {
                                        if (el.isJsonObject()) {
                                            dev.vibesindev.vibesmusic.model.Track parsed = parseTrack(el.getAsJsonObject());
                                            if (parsed != null) {
                                                tracks.add(parsed);
                                            }
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                if (plugin != null && plugin.getLogger() != null) {
                                    plugin.getLogger().warning("[VibesInDev] Failed to parse artist top tracks: " + e.getMessage());
                                }
                            }
                        } else if (response.statusCode() == 404 || response.statusCode() == 403 || response.statusCode() == 410) {
                            spotifyHttp.disableFeature(SpotifyHttp.Feature.ARTIST_TOP_TRACKS);
                        }
                        return tracks;
                    }).exceptionally(ex -> Collections.emptyList());
        });
    }



    /**

     * Searches for tracks in Spotify by micro-genre: GET /v1/search?q=genre:"{genre}"&type=track.

     */

    public CompletableFuture<List<Track>> searchGenreTracks(String genre, int limit) {
        if (genre == null || genre.isBlank() || !isFeatureAvailable(SpotifyHttp.Feature.GENRE_SEARCH)) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        return getClientToken().thenCompose(token -> executeGenreSearch(genre, Math.min(50, limit), token, true));
    }

    private CompletableFuture<List<Track>> executeGenreSearch(String genre, int limit, String token, boolean allowRetryOn400) {
        String url = API_URL + "/search?q=genre:" + enc("\"" + genre + "\"") + "&type=track&limit=" + limit + "&market=" + enc(market);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();

        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenCompose(response -> {
                    int status = response.statusCode();
                    if (status == 200 && response.body() != null) {
                        List<Track> tracks = new ArrayList<>();
                        try {
                            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(response.body()).getAsJsonObject();
                            if (root.has("tracks") && root.getAsJsonObject("tracks").has("items")) {
                                for (com.google.gson.JsonElement el : root.getAsJsonObject("tracks").getAsJsonArray("items")) {
                                    if (el.isJsonObject()) {
                                        Track parsed = parseTrack(el.getAsJsonObject());
                                        if (parsed != null) {
                                            tracks.add(parsed);
                                        }
                                    }
                                }
                            }
                        } catch (Exception e) {
                            plugin.getLogger().warning("[VibesInDev] Failed to parse genre search tracks: " + e.getMessage());
                        }
                        return CompletableFuture.completedFuture(tracks);
                    } else if (status == 400 && allowRetryOn400 && limit > 4) {
                        // Handle 400 on limit gracefully by retrying once with smaller limit
                        return executeGenreSearch(genre, Math.max(1, limit / 2), token, false);
                    } else if (status == 403 || status == 404 || status == 410) {
                        spotifyHttp.disableFeature(SpotifyHttp.Feature.GENRE_SEARCH);
                    }
                    return CompletableFuture.completedFuture(Collections.emptyList());
                }).exceptionally(ex -> Collections.emptyList());
    }



    /**

     * Ingests real Spotify taste profile data for a player and feeds it to the RecommendationManager.

     */

        public CompletableFuture<List<SpotifyArtistSummary>> fetchUserTopArtistSummaries(UUID playerUuid, String timeRange) {
        return getUserToken(playerUuid).thenCompose(token -> {
            String url = API_URL + "/me/top/artists?time_range=" + enc(timeRange != null ? timeRange : "medium_term") + "&limit=50";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();

            return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        List<SpotifyArtistSummary> list = new ArrayList<>();
                        if (response.statusCode() == 200) {
                            try {
                                com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(response.body()).getAsJsonObject();
                                if (root.has("items") && root.get("items").isJsonArray()) {
                                    for (com.google.gson.JsonElement el : root.getAsJsonArray("items")) {
                                        if (el.isJsonObject()) {
                                            com.google.gson.JsonObject obj = el.getAsJsonObject();
                                            String id = obj.has("id") && !obj.get("id").isJsonNull() ? obj.get("id").getAsString() : "";
                                            String name = obj.has("name") && !obj.get("name").isJsonNull() ? obj.get("name").getAsString() : "";
                                            List<String> genres = new ArrayList<>();
                                            if (obj.has("genres") && obj.get("genres").isJsonArray()) {
                                                for (com.google.gson.JsonElement g : obj.getAsJsonArray("genres")) {
                                                    if (!g.isJsonNull()) genres.add(g.getAsString());
                                                }
                                            }
                                            if (!name.isBlank()) {
                                                list.add(new SpotifyArtistSummary(id, name, genres));
                                                if (!id.isBlank()) {
                                                    registerKnownArtist(name, id);
                                                }
                                            }
                                        }
                                    }
                                }
                            } catch (Exception ignored) {}
                        }
                        return list;
                    });
        });
    }

    public CompletableFuture<List<SpotifyArtistSummary>> fetchRelatedArtists(UUID playerUuid, String artistId) {
        if (artistId == null || artistId.isBlank()) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        return (hasUserAuthorization(playerUuid) ? getUserToken(playerUuid) : getClientToken())
                .thenCompose(token -> {
                    String url = API_URL + "/artists/" + enc(artistId) + "/related-artists";
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(Duration.ofSeconds(10))
                            .header("Authorization", "Bearer " + token)
                            .GET()
                            .build();

                    return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                            .thenApply(response -> {
                                List<SpotifyArtistSummary> list = new ArrayList<>();
                                if (response.statusCode() == 200) {
                                    try {
                                        com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(response.body()).getAsJsonObject();
                                        if (root.has("artists") && root.get("artists").isJsonArray()) {
                                            for (com.google.gson.JsonElement el : root.getAsJsonArray("artists")) {
                                                if (el.isJsonObject()) {
                                                    com.google.gson.JsonObject obj = el.getAsJsonObject();
                                                    String id = obj.has("id") && !obj.get("id").isJsonNull() ? obj.get("id").getAsString() : "";
                                                    String name = obj.has("name") && !obj.get("name").isJsonNull() ? obj.get("name").getAsString() : "";
                                                    List<String> genres = new ArrayList<>();
                                                    if (obj.has("genres") && obj.get("genres").isJsonArray()) {
                                                        for (com.google.gson.JsonElement g : obj.getAsJsonArray("genres")) {
                                                            if (!g.isJsonNull()) genres.add(g.getAsString());
                                                        }
                                                    }
                                                    if (!name.isBlank()) {
                                                        list.add(new SpotifyArtistSummary(id, name, genres));
                                                    }
                                                }
                                            }
                                        }
                                    } catch (Exception ignored) {}
                                }
                                return list;
                            })
                            .exceptionally(err -> Collections.emptyList());
                });
    }

    public CompletableFuture<Void> syncTasteProfile(Player player) {
        if (player == null) return CompletableFuture.completedFuture(null);
        UUID uuid = player.getUniqueId();
        if (!hasUserAuthorization(uuid)) {
            return CompletableFuture.completedFuture(null);
        }

        plugin.getLogger().info("[VibesInDev] Synchronizing Spotify taste profile for " + player.getName() + "...");

        CompletableFuture<List<SpotifyArtistSummary>> shortTerm = fetchUserTopArtistSummaries(uuid, "short_term");
        CompletableFuture<List<SpotifyArtistSummary>> mediumTerm = fetchUserTopArtistSummaries(uuid, "medium_term");

        return CompletableFuture.allOf(shortTerm, mediumTerm).thenRun(() -> {
            try {
                List<SpotifyArtistSummary> st = shortTerm.getNow(Collections.emptyList());
                List<SpotifyArtistSummary> mt = mediumTerm.getNow(Collections.emptyList());

                List<String> stNames = new ArrayList<>();
                for (SpotifyArtistSummary a : st) stNames.add(a.name());

                List<String> mtNames = new ArrayList<>();
                for (SpotifyArtistSummary a : mt) mtNames.add(a.name());

                if (plugin.recommendations() != null) {
                    PlayerTasteProfile profile = plugin.recommendations().getOrCreateProfile(uuid);
                    profile.ingestSpotifyArtists(stNames, mtNames);

                    // Ingest real Spotify micro-genres from top artists (idempotent, rank-weighted)
                    profile.clearSpotifyGenreMass();
                    int rank = 1;
                    int stTotal = Math.max(1, st.size());
                    for (SpotifyArtistSummary a : st) {
                        double weight = 1.0 * (1.0 - ((double) (rank - 1) / stTotal) * 0.5); // 1.0 down to 0.5
                        profile.ingestSpotifyArtistGenres(a.genres(), weight);
                        rank++;
                    }
                    rank = 1;
                    int mtTotal = Math.max(1, mt.size());
                    for (SpotifyArtistSummary a : mt) {
                        double weight = 0.7 * (1.0 - ((double) (rank - 1) / mtTotal) * 0.5); // 0.7 down to 0.35
                        profile.ingestSpotifyArtistGenres(a.genres(), weight);
                        rank++;
                    }

                    // Related artists dynamic expansion removed (fixes F17: deprecated 404 endpoint & stops genre pollution)

                    if (plugin.recommendations() != null) {
                        plugin.recommendations().saveProfile(uuid);
                        if (plugin.recommendations().genreResolver() != null) {
                            plugin.recommendations().genreResolver().warmTopArtists(profile, 50);
                        }
                    }

                    plugin.getLogger().info("[VibesInDev] Ingested " + (st.size() + mt.size()) + " Spotify top artists and expanded related artist lineage for " + player.getName() + ".");
                    player.sendMessage(ChatColor.GREEN + "✔ " + ChatColor.WHITE + "Spotify taste profile synchronized (" + st.size() + " recent favorite artists + band/solo lineage).");
                }

                // Also sync familiar tracks (top tracks, saved tracks, recently played)
                syncFamiliarTracks(uuid);
            } catch (Exception e) {
                plugin.getLogger().warning("[VibesInDev] Failed to ingest Spotify taste profile: " + e.getMessage());
            }
        });
    }



    public CompletableFuture<List<Track>> fetchUserTopTracks(UUID playerUuid, String timeRange, int limit) {
        if (!isFeatureAvailable(SpotifyHttp.Feature.USER_TOP_TRACKS)) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        return getUserToken(playerUuid).thenCompose(token -> {
            if (token == null || token.isBlank()) {
                return CompletableFuture.completedFuture(Collections.emptyList());
            }
            int lim = Math.max(1, Math.min(limit, 50));
            String url = API_URL + "/me/top/tracks?time_range=" + enc(timeRange != null ? timeRange : "medium_term") + "&limit=" + lim;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();

            return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(res -> {
                        List<Track> list = new ArrayList<>();
                        if (res.statusCode() == 200 && res.body() != null) {
                            try {
                                com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(res.body()).getAsJsonObject();
                                if (root.has("items") && root.get("items").isJsonArray()) {
                                    for (com.google.gson.JsonElement el : root.getAsJsonArray("items")) {
                                        if (el.isJsonObject()) {
                                            Track t = parseTrack(el.getAsJsonObject());
                                            if (t != null) list.add(t);
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                if (plugin != null && plugin.getLogger() != null) {
                                    plugin.getLogger().warning("[VibesInDev] Failed to parse user top tracks: " + e.getMessage());
                                }
                            }
                        } else if (res.statusCode() == 403 || res.statusCode() == 404 || res.statusCode() == 410) {
                            spotifyHttp.disableFeature(SpotifyHttp.Feature.USER_TOP_TRACKS);
                        }
                        return list;
                    }).exceptionally(ex -> Collections.emptyList());
        });
    }

    public CompletableFuture<List<Track>> fetchUserSavedTracks(UUID playerUuid, int limit) {
        if (!isFeatureAvailable(SpotifyHttp.Feature.USER_SAVED_TRACKS)) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        return getUserToken(playerUuid).thenCompose(token -> {
            if (token == null || token.isBlank()) {
                return CompletableFuture.completedFuture(Collections.emptyList());
            }
            int lim = Math.max(1, Math.min(limit, 50));
            String url = API_URL + "/me/tracks?limit=" + lim;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();

            return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(res -> {
                        List<Track> list = new ArrayList<>();
                        if (res.statusCode() == 200 && res.body() != null) {
                            try {
                                com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(res.body()).getAsJsonObject();
                                if (root.has("items") && root.get("items").isJsonArray()) {
                                    for (com.google.gson.JsonElement el : root.getAsJsonArray("items")) {
                                        if (el.isJsonObject()) {
                                            com.google.gson.JsonObject item = el.getAsJsonObject();
                                            if (item.has("track") && item.get("track").isJsonObject()) {
                                                Track t = parseTrack(item.getAsJsonObject("track"));
                                                if (t != null) list.add(t);
                                            }
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                if (plugin != null && plugin.getLogger() != null) {
                                    plugin.getLogger().warning("[VibesInDev] Failed to parse user saved tracks: " + e.getMessage());
                                }
                            }
                        } else if (res.statusCode() == 403 || res.statusCode() == 404 || res.statusCode() == 410) {
                            spotifyHttp.disableFeature(SpotifyHttp.Feature.USER_SAVED_TRACKS);
                        }
                        return list;
                    }).exceptionally(ex -> Collections.emptyList());
        });
    }

    public CompletableFuture<List<Track>> fetchUserRecentlyPlayed(UUID playerUuid, int limit) {
        if (!isFeatureAvailable(SpotifyHttp.Feature.USER_RECENTLY_PLAYED)) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        return getUserToken(playerUuid).thenCompose(token -> {
            if (token == null || token.isBlank()) {
                return CompletableFuture.completedFuture(Collections.emptyList());
            }
            int lim = Math.max(1, Math.min(limit, 50));
            String url = API_URL + "/me/player/recently-played?limit=" + lim;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();

            return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(res -> {
                        List<Track> list = new ArrayList<>();
                        if (res.statusCode() == 200 && res.body() != null) {
                            try {
                                com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(res.body()).getAsJsonObject();
                                if (root.has("items") && root.get("items").isJsonArray()) {
                                    for (com.google.gson.JsonElement el : root.getAsJsonArray("items")) {
                                        if (el.isJsonObject()) {
                                            com.google.gson.JsonObject item = el.getAsJsonObject();
                                            if (item.has("track") && item.get("track").isJsonObject()) {
                                                Track t = parseTrack(item.getAsJsonObject("track"));
                                                if (t != null) list.add(t);
                                            }
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                if (plugin != null && plugin.getLogger() != null) {
                                    plugin.getLogger().warning("[VibesInDev] Failed to parse user recently played: " + e.getMessage());
                                }
                            }
                        } else if (res.statusCode() == 403 || res.statusCode() == 404 || res.statusCode() == 410) {
                            spotifyHttp.disableFeature(SpotifyHttp.Feature.USER_RECENTLY_PLAYED);
                        }
                        return list;
                    }).exceptionally(ex -> Collections.emptyList());
        });
    }

    public CompletableFuture<Void> syncFamiliarTracks(UUID playerUuid) {
        if (playerUuid == null || plugin.cache() == null) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<List<Track>> stTop = fetchUserTopTracks(playerUuid, "short_term", 50);
        CompletableFuture<List<Track>> mtTop = fetchUserTopTracks(playerUuid, "medium_term", 50);
        CompletableFuture<List<Track>> saved = fetchUserSavedTracks(playerUuid, 50);
        CompletableFuture<List<Track>> recent = fetchUserRecentlyPlayed(playerUuid, 50);

        return CompletableFuture.allOf(stTop, mtTop, saved, recent).thenRun(() -> {
            try {
                List<Track> st = stTop.getNow(Collections.emptyList());
                List<Track> mt = mtTop.getNow(Collections.emptyList());
                List<Track> sv = saved.getNow(Collections.emptyList());
                List<Track> rc = recent.getNow(Collections.emptyList());

                if (!st.isEmpty()) plugin.cache().savePlayerSpotifyTracks(playerUuid, "top_short", st);
                if (!mt.isEmpty()) plugin.cache().savePlayerSpotifyTracks(playerUuid, "top_medium", mt);
                if (!sv.isEmpty()) plugin.cache().savePlayerSpotifyTracks(playerUuid, "saved", sv);
                if (!rc.isEmpty()) plugin.cache().savePlayerSpotifyTracks(playerUuid, "recent", rc);
            } catch (Exception e) {
                if (plugin != null && plugin.getLogger() != null) {
                    plugin.getLogger().warning("[VibesInDev] Failed to save familiar Spotify tracks: " + e.getMessage());
                }
            }
        });
    }

    private CompletableFuture<String> getUserToken() {

        if (userAccessToken != null

                && !userAccessToken.isBlank()

                && System.currentTimeMillis() < userTokenExpiresAt) {

            return CompletableFuture.completedFuture(

                    userAccessToken

            );

        }



        if (userRefreshToken != null && !userRefreshToken.isBlank()) {

            return refreshUserToken();

        }



        return CompletableFuture.failedFuture(

                new IllegalStateException(

                        "Spotify login required. Run /music spotify login."

                )

        );

    }



    private CompletableFuture<String> refreshUserToken() {

        String body =

                "grant_type=refresh_token"

                        + "&refresh_token=" + enc(userRefreshToken);



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



        return http.sendAsync(

                        request,

                        HttpResponse.BodyHandlers.ofString()

                )

                .thenCompose(response -> {

                    if (response.statusCode() != 200) {

                        plugin.getLogger().warning("[VibesInDev] Spotify user token refresh failed (HTTP "

                                + response.statusCode() + "). Body: " + response.body());

                        return CompletableFuture.failedFuture(

                                new IllegalStateException(

                                        "Spotify session expired. Run /music spotify login again."

                                )

                        );

                    }



                    String newAccess = string(

                            response.body(),

                            "access_token"

                    );



                    if (newAccess == null || newAccess.isBlank()) {

                        return CompletableFuture.failedFuture(

                                new IllegalStateException("Refreshed Spotify response contained no access_token.")

                        );

                    }



                    userAccessToken = newAccess;



                    String newRefresh = string(

                            response.body(),

                            "refresh_token"

                    );

                    if (newRefresh != null && !newRefresh.isBlank()) {

                        userRefreshToken = newRefresh;

                    }



                    long expiresIn = numberLong(

                            response.body(),

                            "expires_in",

                            3600

                    );



                    userTokenExpiresAt =

                            System.currentTimeMillis()

                                    + Math.max(60, expiresIn - 60) * 1000L;



                    saveTokens();



                    plugin.getLogger().info("[VibesInDev] Refreshed Spotify user access token successfully.");

                    return CompletableFuture.completedFuture(userAccessToken);

                });

    }



    public boolean hasUserAuthorization() {

        return (userAccessToken != null && !userAccessToken.isBlank() && System.currentTimeMillis() < userTokenExpiresAt)

                || (userRefreshToken != null && !userRefreshToken.isBlank());

    }



    private CompletableFuture<List<SpotifyTrack>> getAlbumItems(

            String albumId

    ) {

        CompletableFuture<String> tokenFuture = hasUserAuthorization()

                ? getUserToken()

                : getClientToken();



        return tokenFuture.thenCompose(

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

    public synchronized void shutdown() {
        if (callbackServer != null) {
            try {
                callbackServer.stop(0);
            } catch (Exception ignored) {
            }
            callbackServer = null;
        }
    }
}

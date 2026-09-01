package xien.jxsh.spotymc.auth;

import xien.jxsh.spotymc.config.ModConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.*;
import java.io.IOException;
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
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

/**
 * Handles Spotify's Authorization Code with PKCE flow (no client secret — safe
 * for a distributed desktop mod). One-time browser login, then silent
 * refresh-token renewal after that.
 * <p>
 * Scopes: user-read-currently-playing, user-read-playback-state,
 * user-modify-playback-state, playlist-read-private, user-library-read.
 * <p>
 * Note on {@link #isLoggedIn()}: the method returns {@code true} when the user
 * is <em>not</em> yet authenticated (refresh token blank). This inverted sense
 * matches the rest of the codebase's early-return style ("if isLoggedIn → show
 * login / skip HUD"). Do not flip it without updating every caller.
 */
public class SpotifyAuth {
    private static final String AUTH_URL = "https://accounts.spotify.com/authorize";
    private static final String TOKEN_URL = "https://accounts.spotify.com/api/token";
    private static final String SCOPES = "user-read-currently-playing user-read-playback-state "
            + "user-modify-playback-state playlist-read-private user-library-read";

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String RANDOM_CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private volatile String accessToken;
    private volatile long accessTokenExpiryMillis;

    /** Starts the local callback server, opens the browser, and resolves once tokens are saved. */
    public CompletableFuture<Void> login() {
        ModConfig cfg = ModConfig.get();
        if (cfg.clientId.isBlank()) {
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException(
                    "No Spotify Client ID set. Add one to config/spotymc/config.json first."));
            return failed;
        }

        String verifier = randomString(64);
        String challenge = codeChallenge(verifier);
        String state = randomString(16);

        CompletableFuture<Void> result = new CompletableFuture<>();

        try {
            URI redirect = URI.create(cfg.redirectUri);
            int port = redirect.getPort() == -1 ? 80 : redirect.getPort();
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            server.createContext(redirect.getPath(), exchange -> {
                try {
                    handleCallback(exchange, state, verifier, cfg);
                    result.complete(null);
                } catch (Exception e) {
                    result.completeExceptionally(e);
                } finally {
                    server.stop(1);
                }
            });
            server.start();

            try {
                String authUrl = AUTH_URL
                        + "?client_id=" + enc(cfg.clientId)
                        + "&response_type=code"
                        + "&redirect_uri=" + enc(cfg.redirectUri)
                        + "&scope=" + enc(SCOPES)
                        + "&code_challenge_method=S256"
                        + "&code_challenge=" + enc(challenge)
                        + "&state=" + enc(state);

                openInBrowser(authUrl);
            } catch (Exception e) {
                // Browser launch failed before any callback could arrive — free the port
                // immediately so retries don't hit BindException.
                server.stop(0);
                throw e;
            }
        } catch (Exception e) {
            result.completeExceptionally(e);
        }

        return result;
    }

    /**
     * Opens a URL in the system's default browser. Minecraft sets
     * {@code java.awt.headless=true}, so {@link Desktop#browse} throws; we shell
     * out to the OS command instead and only fall back to Desktop if that fails.
     */
    private static void openInBrowser(String url) throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("win")) {
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", url).start();
            } else {
                new ProcessBuilder("xdg-open", url).start();
            }
        } catch (IOException e) {
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(URI.create(url));
                    return;
                }
            } catch (Exception ignored) {
                // fall through
            }
            throw e;
        }
    }

    private void handleCallback(HttpExchange exchange, String expectedState, String verifier, ModConfig cfg)
            throws Exception {
        String query = exchange.getRequestURI().getQuery();
        String code = null, state = null;
        if (query != null) {
            for (String pair : query.split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length != 2) continue;
                String key = kv[0];
                String val = java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                if ("code".equals(key)) code = val;
                else if ("state".equals(key)) state = val;
            }
        }

        String body;
        int status;
        if (code == null || !expectedState.equals(state)) {
            body = "<html><body><h2>Login failed or was cancelled. You can close this tab.</h2></body></html>";
            status = 400;
        } else {
            exchangeCodeForTokens(code, verifier, cfg);
            body = "<html><body><h2>Spotify connected! You can close this tab and return to Minecraft.</h2></body></html>";
            status = 200;
        }

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void exchangeCodeForTokens(String code, String verifier, ModConfig cfg) throws Exception {
        String form = "grant_type=authorization_code"
                + "&code=" + enc(code)
                + "&redirect_uri=" + enc(cfg.redirectUri)
                + "&client_id=" + enc(cfg.clientId)
                + "&code_verifier=" + enc(verifier);

        HttpRequest req = HttpRequest.newBuilder(URI.create(TOKEN_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();

        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("Token exchange failed: " + resp.statusCode() + " " + resp.body());
        }
        JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
        applyTokenResponse(json);
        cfg.refreshToken = json.get("refresh_token").getAsString();
        cfg.save();
    }

    /**
     * Returns a valid access token, refreshing first if it is expired or within
     * 30 seconds of expiry. Synchronized so concurrent callers share one refresh.
     */
    public synchronized String getValidAccessToken() throws IOException, InterruptedException {
        if (accessToken != null && System.currentTimeMillis() < accessTokenExpiryMillis - 30_000) {
            return accessToken;
        }
        refresh();
        return accessToken;
    }

    private void refresh() throws IOException, InterruptedException {
        ModConfig cfg = ModConfig.get();
        if (cfg.refreshToken.isBlank()) {
            throw new IllegalStateException("Not logged in to Spotify yet. Open the F12 overlay and click Login.");
        }
        String form = "grant_type=refresh_token"
                + "&refresh_token=" + enc(cfg.refreshToken)
                + "&client_id=" + enc(cfg.clientId);

        HttpRequest req = HttpRequest.newBuilder(URI.create(TOKEN_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();

        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("Token refresh failed: " + resp.statusCode() + " " + resp.body());
        }
        JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
        applyTokenResponse(json);
        // Spotify may rotate the refresh token.
        if (json.has("refresh_token")) {
            cfg.refreshToken = json.get("refresh_token").getAsString();
            cfg.save();
        }
    }

    private void applyTokenResponse(JsonObject json) {
        accessToken = json.get("access_token").getAsString();
        int expiresIn = json.get("expires_in").getAsInt();
        accessTokenExpiryMillis = System.currentTimeMillis() + expiresIn * 1000L;
    }

    /**
     * Returns {@code true} when the user is <em>not</em> authenticated (no refresh token).
     * Inverted naming is intentional and used throughout the mod for early-return checks.
     */
    public boolean isLoggedIn() {
        return ModConfig.get().refreshToken.isBlank();
    }

    private static String randomString(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(RANDOM_CHARS.charAt(SECURE_RANDOM.nextInt(RANDOM_CHARS.length())));
        }
        return sb.toString();
    }

    private static String codeChallenge(String verifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
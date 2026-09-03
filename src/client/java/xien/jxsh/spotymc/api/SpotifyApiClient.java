package xien.jxsh.spotymc.api;

import xien.jxsh.spotymc.auth.SpotifyAuth;
import xien.jxsh.spotymc.config.ModConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Thin wrapper over the parts of the Spotify Web API this mod needs.
 * All calls are blocking — run them off the render thread (see PlaybackPoller).
 * <p>
 * Device-id resolution is cached briefly so play/seek/volume calls don't pay an
 * extra /me/player/devices round-trip every time. Library paging is hard-capped
 * so an enormous collection can't turn one click into thousands of sequential requests.
 */
public class SpotifyApiClient {
	private static final String BASE = "https://api.spotify.com/v1";
	private static final HttpClient HTTP = HttpClient.newHttpClient();

	private final SpotifyAuth auth;

	// Cached id of the librespot ("in-game") Spotify Connect device.
	// Re-resolved periodically in case librespot restarts and gets a new id.
	private volatile String cachedDeviceId;
	private volatile long deviceCacheExpiresAtMillis;
	private static final long DEVICE_CACHE_TTL_MS = 15_000;

	// Upper bound on how many liked songs / playlists we'll page through in one call.
	private static final int MAX_LIBRARY_FETCH = 5000;

	public SpotifyApiClient(SpotifyAuth auth) {
		this.auth = auth;
	}

	/** Looks up the Spotify Connect device id matching ModConfig's librespotDeviceName, caching briefly. */
	private String resolveDeviceId() throws IOException, InterruptedException {
		ModConfig cfg = ModConfig.get();
		if (!cfg.librespotEnabled || cfg.librespotDeviceName == null || cfg.librespotDeviceName.isBlank()) {
			return null;
		}
		long now = System.currentTimeMillis();
		if (cachedDeviceId != null && now < deviceCacheExpiresAtMillis) {
			return cachedDeviceId;
		}
		HttpResponse<String> resp = send("GET", "/me/player/devices", null);
		if (resp.statusCode() != 200) return cachedDeviceId; // fall back to stale cache if present
		JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
		JsonArray devices = json.getAsJsonArray("devices");
		for (JsonElement el : devices) {
			JsonObject d = el.getAsJsonObject();
			if (cfg.librespotDeviceName.equalsIgnoreCase(d.get("name").getAsString())) {
				cachedDeviceId = d.get("id").getAsString();
				deviceCacheExpiresAtMillis = now + DEVICE_CACHE_TTL_MS;
				return cachedDeviceId;
			}
		}
		// librespot isn't visible to Spotify yet
		return null;
	}

	/** Appends ?device_id=... (or &device_id=...) when a target device is resolvable. */
	private String withDeviceId(String path) throws IOException, InterruptedException {
		String deviceId = resolveDeviceId();
		if (deviceId == null) return path;
		return appendDeviceId(path, deviceId);
	}

	/** Appends ?device_id=... (or &device_id=...) for an already-resolved device id. */
	private static String appendDeviceId(String path, String deviceId) {
		if (deviceId == null) return path;
		String sep = path.contains("?") ? "&" : "?";
		return path + sep + "device_id=" + URLEncoder.encode(deviceId, StandardCharsets.UTF_8);
	}

	/**
	 * Explicitly makes {@code deviceId} the active Spotify Connect device, without starting
	 * playback yet ({@code play: false}). Spotify's {@code /me/player/play} endpoint is
	 * unreliable about actually switching to a device that isn't already active when there's
	 * currently *no* active device at all (a cold start, e.g. nothing has played since Minecraft
	 * launched) -- it can silently no-op or 404 instead of transferring. Calling the transfer
	 * endpoint first is the documented workaround, and is what actually makes clicking a track
	 * for the first time in a session reliably start audio in-game.
	 */
	private void transferPlayback(String deviceId) throws IOException, InterruptedException {
		String body = "{\"device_ids\":[\"" + deviceId + "\"],\"play\":false}";
		HttpResponse<String> resp = send("PUT", "/me/player", body);
		if (resp.statusCode() >= 300) {
			throw new IOException("Couldn't switch Spotify playback to \"" + ModConfig.get().librespotDeviceName
					+ "\" (" + resp.statusCode() + "): " + resp.body());
		}
	}

	/**
	 * Resolves the in-game (librespot) device and makes sure it's active before a play call
	 * targets it, so starting a track/playlist reliably lands in Minecraft instead of silently
	 * failing or landing on whatever device happened to already be active. Returns null (no
	 * transfer performed) when In-Game Audio is off, so those callers keep the old,
	 * device-agnostic behaviour. Throws with a clear, user-facing message when In-Game Audio is
	 * on but librespot hasn't registered itself with Spotify yet -- sending the play call anyway
	 * would just silently miss the in-game device.
	 */
	private String resolveAndPrepareDevice() throws IOException, InterruptedException {
		ModConfig cfg = ModConfig.get();
		if (!cfg.librespotEnabled) return null;
		String deviceId = resolveDeviceId();
		if (deviceId == null) {
			throw new IOException("Connect to \"" + cfg.librespotDeviceName + "\" in your Spotify app first.");
		}
		transferPlayback(deviceId);
		return deviceId;
	}

	public PlaybackState getCurrentPlayback() throws IOException, InterruptedException {
		HttpResponse<String> resp = send("GET", "/me/player", null);
		if (resp.statusCode() == 204 || resp.body() == null || resp.body().isBlank()) {
			return PlaybackState.NOTHING_PLAYING;
		}
		if (resp.statusCode() != 200) {
			throw new IOException("Spotify API error " + resp.statusCode() + ": " + resp.body());
		}
		JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
		if (json.get("item").isJsonNull()) return PlaybackState.NOTHING_PLAYING;

		JsonObject item = json.getAsJsonObject("item");
		String title = item.get("name").getAsString();
		String artists = joinArtists(item.getAsJsonArray("artists"));
		String album = item.has("album") ? item.getAsJsonObject("album").get("name").getAsString() : "";
		boolean playing = json.get("is_playing").getAsBoolean();
		int progress = json.has("progress_ms") && !json.get("progress_ms").isJsonNull()
				? json.get("progress_ms").getAsInt() : 0;
		int duration = item.get("duration_ms").getAsInt();

		int volume = 50;
		String deviceId = null;
		String deviceName = null;
		if (json.has("device") && !json.get("device").isJsonNull()) {
			JsonObject device = json.getAsJsonObject("device");
			if (device.has("volume_percent") && !device.get("volume_percent").isJsonNull()) {
				volume = device.get("volume_percent").getAsInt();
			}
			if (device.has("id") && !device.get("id").isJsonNull()) deviceId = device.get("id").getAsString();
			if (device.has("name") && !device.get("name").isJsonNull()) deviceName = device.get("name").getAsString();
		}

		return new PlaybackState(playing, item.get("id").getAsString(), title, artists, album,
				progress, duration, volume, deviceId, deviceName, System.currentTimeMillis());
	}

	public void play() throws IOException, InterruptedException {
		send("PUT", withDeviceId("/me/player/play"), "");
	}

	public void pause() throws IOException, InterruptedException {
		send("PUT", "/me/player/pause", "");
	}

	public void next() throws IOException, InterruptedException {
		send("POST", "/me/player/next", "");
	}

	public void previous() throws IOException, InterruptedException {
		send("POST", "/me/player/previous", "");
	}

	/** Jumps playback to the given position within the current track. */
	public void seek(int positionMs) throws IOException, InterruptedException {
		send("PUT", withDeviceId("/me/player/seek?position_ms=" + Math.max(0, positionMs)), "");
	}

	public void setVolume(int percent) throws IOException, InterruptedException {
		send("PUT", "/me/player/volume?volume_percent=" + Math.clamp(percent, 0, 100), "");
	}

	public void playPlaylist(String contextUri) throws IOException, InterruptedException {
		String deviceId = resolveAndPrepareDevice();
		String body = "{\"context_uri\":\"" + contextUri + "\"}";
		send("PUT", appendDeviceId("/me/player/play", deviceId), body);
	}

	/**
	 * Skips forward through the current queue to land on the track at {@code index}.
	 * Advances the existing context instead of replacing it (which would clear the rest of the queue).
	 */
	public void skipToQueueIndex(int index) throws IOException, InterruptedException {
		for (int i = 0; i <= index; i++) {
			next();
		}
	}

	public List<PlaybackState.QueueItem> getQueue() throws IOException, InterruptedException {
		HttpResponse<String> resp = send("GET", "/me/player/queue", null);
		if (resp.statusCode() != 200) return List.of();
		JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
		JsonArray queue = json.getAsJsonArray("queue");
		// Typical queues are short; pre-size to avoid a couple of internal resizes.
		List<PlaybackState.QueueItem> items = new ArrayList<>(Math.min(queue.size(), 64));
		for (JsonElement el : queue) {
			JsonObject t = el.getAsJsonObject();
			items.add(new PlaybackState.QueueItem(t.get("id").getAsString(), t.get("name").getAsString(),
					joinArtists(t.getAsJsonArray("artists"))));
		}
		return items;
	}

	public List<PlaybackState.Playlist> getPlaylists() throws IOException, InterruptedException {
		List<PlaybackState.Playlist> playlists = new ArrayList<>(64);
		int limit = 50, offset = 0;
		while (true) {
			HttpResponse<String> resp = send("GET", "/me/playlists?limit=" + limit + "&offset=" + offset, null);
			if (resp.statusCode() != 200) break;
			JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
			JsonArray items = json.getAsJsonArray("items");
			if (items.isEmpty()) break;
			for (JsonElement el : items) {
				// Spotify can pad this endpoint with null entries.
				if (el == null || el.isJsonNull()) continue;
				JsonObject p = el.getAsJsonObject();
				if (!p.has("id") || p.get("id").isJsonNull() || !p.has("uri") || p.get("uri").isJsonNull()) continue;

				int trackCount = extractTrackCount(p);
				String name = p.has("name") && !p.get("name").isJsonNull()
						? p.get("name").getAsString() : "(untitled playlist)";
				playlists.add(new PlaybackState.Playlist(p.get("id").getAsString(),
						p.get("uri").getAsString(), name, trackCount));
			}
			if (items.size() < limit) break;
			offset += limit;
			if (offset >= MAX_LIBRARY_FETCH) break;
		}
		return playlists;
	}

	public List<PlaybackState.Track> searchTracks(String query) throws IOException, InterruptedException {
		if (query == null || query.isBlank()) return List.of();
		String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
		HttpResponse<String> resp = send("GET", "/search?q=" + encoded + "&type=track&limit=10", null);
		if (resp.statusCode() != 200) return List.of();
		JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
		JsonArray items = json.getAsJsonObject("tracks").getAsJsonArray("items");
		List<PlaybackState.Track> tracks = new ArrayList<>(items.size());
		for (JsonElement el : items) {
			JsonObject t = el.getAsJsonObject();
			tracks.add(new PlaybackState.Track(t.get("id").getAsString(), t.get("uri").getAsString(),
					t.get("name").getAsString(), joinArtists(t.getAsJsonArray("artists"))));
		}
		return tracks;
	}

	public void playTrack(String trackUri) throws IOException, InterruptedException {
		String deviceId = resolveAndPrepareDevice();
		String body = "{\"uris\":[\"" + trackUri + "\"]}";
		send("PUT", appendDeviceId("/me/player/play", deviceId), body);
	}

	public void playTracks(List<String> trackUris) throws IOException, InterruptedException {
		if (trackUris.isEmpty()) return;
		String deviceId = resolveAndPrepareDevice();
		StringBuilder sb = new StringBuilder(32 + trackUris.size() * 40);
		sb.append("{\"uris\":[");
		for (int i = 0; i < trackUris.size(); i++) {
			if (i > 0) sb.append(',');
			sb.append('"').append(trackUris.get(i)).append('"');
		}
		sb.append("]}");
		send("PUT", appendDeviceId("/me/player/play", deviceId), sb.toString());
	}

	public List<PlaybackState.Playlist> searchPlaylists(String query) throws IOException, InterruptedException {
		if (query == null || query.isBlank()) return List.of();
		String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
		HttpResponse<String> resp = send("GET", "/search?q=" + encoded + "&type=playlist&limit=10", null);
		if (resp.statusCode() != 200) return List.of();
		JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
		if (!json.has("playlists") || json.get("playlists").isJsonNull()) return List.of();
		JsonArray items = json.getAsJsonObject("playlists").getAsJsonArray("items");
		List<PlaybackState.Playlist> playlists = new ArrayList<>(items.size());
		for (JsonElement el : items) {
			if (el == null || el.isJsonNull()) continue;
			JsonObject p = el.getAsJsonObject();
			int trackCount = extractTrackCount(p);
			playlists.add(new PlaybackState.Playlist(p.get("id").getAsString(), p.get("uri").getAsString(),
					p.get("name").getAsString(), trackCount));
		}
		return playlists;
	}

	/**
	 * The user's saved "Liked Songs", most recently added first.
	 * Pages until exhausted or the safety cap is hit.
	 */
	public List<PlaybackState.Track> getLikedSongs() throws IOException, InterruptedException {
		List<PlaybackState.Track> tracks = new ArrayList<>(128);
		int limit = 50, offset = 0;
		while (true) {
			HttpResponse<String> resp = send("GET", "/me/tracks?limit=" + limit + "&offset=" + offset, null);
			if (resp.statusCode() != 200) break;
			JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
			JsonArray items = json.getAsJsonArray("items");
			if (items.isEmpty()) break;
			for (JsonElement el : items) {
				JsonObject t = el.getAsJsonObject().getAsJsonObject("track");
				tracks.add(new PlaybackState.Track(t.get("id").getAsString(), t.get("uri").getAsString(),
						t.get("name").getAsString(), joinArtists(t.getAsJsonArray("artists"))));
			}
			if (items.size() < limit) break;
			offset += limit;
			if (offset >= MAX_LIBRARY_FETCH) break;
		}
		return tracks;
	}

	/**
	 * Spotify renamed the summary field from the deprecated {@code tracks} to {@code items}
	 * (same {href, total} shape). Accept both so older and newer responses both work.
	 */
	private static int extractTrackCount(JsonObject p) {
		if (p.has("items") && !p.get("items").isJsonNull()) {
			JsonObject summary = p.getAsJsonObject("items");
			if (summary.has("total") && !summary.get("total").isJsonNull()) {
				return summary.get("total").getAsInt();
			}
		} else if (p.has("tracks") && !p.get("tracks").isJsonNull()) {
			JsonObject summary = p.getAsJsonObject("tracks");
			if (summary.has("total") && !summary.get("total").isJsonNull()) {
				return summary.get("total").getAsInt();
			}
		}
		return 0;
	}

	/** Joins artist names with ", " using a single StringBuilder (no intermediate List). */
	private static String joinArtists(JsonArray artists) {
		if (artists == null || artists.isEmpty()) return "";
		if (artists.size() == 1) {
			return artists.get(0).getAsJsonObject().get("name").getAsString();
		}
		StringBuilder sb = new StringBuilder(32 * artists.size());
		for (int i = 0; i < artists.size(); i++) {
			if (i > 0) sb.append(", ");
			sb.append(artists.get(i).getAsJsonObject().get("name").getAsString());
		}
		return sb.toString();
	}

	private HttpResponse<String> send(String method, String path, String body)
			throws IOException, InterruptedException {
		String token = auth.getValidAccessToken();
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(BASE + path))
				.header("Authorization", "Bearer " + token);
		if (body == null) {
			builder.GET();
		} else if (body.isEmpty()) {
			builder.method(method, HttpRequest.BodyPublishers.noBody());
		} else {
			builder.header("Content-Type", "application/json");
			builder.method(method, HttpRequest.BodyPublishers.ofString(body));
		}
		return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}
}
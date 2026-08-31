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
 * All calls are blocking -- run them off the render thread (see PlaybackPoller).
 */
public class SpotifyApiClient {
	private static final String BASE = "https://api.spotify.com/v1";
	private static final HttpClient HTTP = HttpClient.newHttpClient();

	private final SpotifyAuth auth;

	// Cached id of the librespot ("in-game") Spotify Connect device, so play/playTrack/playPlaylist
	// can target it directly instead of relying on whatever device happens to be currently active.
	// Re-resolved periodically in case librespot restarts and gets a new device id.
	private volatile String cachedDeviceId;
	private volatile long deviceCacheExpiresAtMillis;
	private static final long DEVICE_CACHE_TTL_MS = 15_000;

	// Upper bound on how many liked songs / playlists we'll page through in one call, so an
	// account with an enormous library can't turn a single button click into thousands of
	// sequential HTTP requests. 5000 comfortably covers the "few thousand" range real users hit.
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
		if (resp.statusCode() != 200) return cachedDeviceId; // fall back to stale cache, if we have one
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
		// librespot isn't visible to Spotify yet (still starting up, or not running at all)
		return null;
	}

	/** Appends ?device_id=... (or &device_id=... if the path already has a query string) when resolvable. */
	private String withDeviceId(String path) throws IOException, InterruptedException {
		String deviceId = resolveDeviceId();
		if (deviceId == null) return path;
		String sep = path.contains("?") ? "&" : "?";
		return path + sep + "device_id=" + URLEncoder.encode(deviceId, StandardCharsets.UTF_8);
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

	public void play() throws IOException, InterruptedException { send("PUT", withDeviceId("/me/player/play"), ""); }
	public void pause() throws IOException, InterruptedException { send("PUT", "/me/player/pause", ""); }
	public void next() throws IOException, InterruptedException { send("POST", "/me/player/next", ""); }
	public void previous() throws IOException, InterruptedException { send("POST", "/me/player/previous", ""); }

	/** Jumps playback to the given position within the current track. */
	public void seek(int positionMs) throws IOException, InterruptedException {
		send("PUT", withDeviceId("/me/player/seek?position_ms=" + Math.max(0, positionMs)), "");
	}

	public void setVolume(int percent) throws IOException, InterruptedException {
		send("PUT", "/me/player/volume?volume_percent=" + Math.clamp(percent, 0, 100), "");
	}

	public void playPlaylist(String contextUri) throws IOException, InterruptedException {
		String body = "{\"context_uri\":\"" + contextUri + "\"}";
		send("PUT", withDeviceId("/me/player/play"), body);
	}

	/**
	 * Skips forward through the current queue to land on the track at {@code index} (0-based,
	 * matching the order returned by getQueue()). Unlike playTrack(), this advances through the
	 * existing playback context/queue instead of replacing it with a brand-new single-track
	 * context -- which is what was causing the rest of the queue to appear to "clear" when a
	 * queued song was clicked.
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
		List<PlaybackState.QueueItem> items = new ArrayList<>();
		for (JsonElement el : queue) {
			JsonObject t = el.getAsJsonObject();
			items.add(new PlaybackState.QueueItem(t.get("id").getAsString(), t.get("name").getAsString(),
					joinArtists(t.getAsJsonArray("artists"))));
		}
		return items;
	}

	public List<PlaybackState.Playlist> getPlaylists() throws IOException, InterruptedException {
		List<PlaybackState.Playlist> playlists = new ArrayList<>();
		int limit = 50, offset = 0;
		while (true) {
			HttpResponse<String> resp = send("GET", "/me/playlists?limit=" + limit + "&offset=" + offset, null);
			if (resp.statusCode() != 200) break;
			JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
			JsonArray items = json.getAsJsonArray("items");
			if (items.isEmpty()) break;
			for (JsonElement el : items) {
				// Spotify can pad this endpoint with null entries -- e.g. a followed collaborative
				// playlist that got deleted, or a Blend that's no longer available. One bad entry
				// used to throw a NullPointerException that aborted the whole fetch, silently
				// leaving the Library tab with zero playlists even though most were fine.
				if (el == null || el.isJsonNull()) continue;
				JsonObject p = el.getAsJsonObject();
				if (!p.has("id") || p.get("id").isJsonNull() || !p.has("uri") || p.get("uri").isJsonNull()) continue;
				// Spotify replaced the (now-deprecated) `tracks` summary field with `items`, same
				// {href, total} shape -- that's why this was always coming back empty: we were
				// reading a field name Spotify no longer populates on this endpoint.
				int trackCount = 0;
				if (p.has("items") && !p.get("items").isJsonNull()) {
					JsonObject itemsSummary = p.getAsJsonObject("items");
					trackCount = itemsSummary.has("total") && !itemsSummary.get("total").isJsonNull()
							? itemsSummary.get("total").getAsInt() : 0;
				} else if (p.has("tracks") && !p.get("tracks").isJsonNull()) {
					// Fall back to the deprecated field, in case an older account/response still has it.
					JsonObject tracksSummary = p.getAsJsonObject("tracks");
					trackCount = tracksSummary.has("total") && !tracksSummary.get("total").isJsonNull()
							? tracksSummary.get("total").getAsInt() : 0;
				}
				String name = p.has("name") && !p.get("name").isJsonNull() ? p.get("name").getAsString() : "(untitled playlist)";
				String id = p.get("id").getAsString();
				playlists.add(new PlaybackState.Playlist(id, p.get("uri").getAsString(), name, trackCount));
			}
			if (items.size() < limit) break; // last page
			offset += limit;
			if (offset >= MAX_LIBRARY_FETCH) break; // safety cap against pathological account sizes
		}
		return playlists;
	}

	/** Searches Spotify's catalog for tracks matching the query. Requires an active/registered device to play. */
	public List<PlaybackState.Track> searchTracks(String query) throws IOException, InterruptedException {
		if (query == null || query.isBlank()) return List.of();
		String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
		HttpResponse<String> resp = send("GET", "/search?q=" + encoded + "&type=track&limit=10", null);
		if (resp.statusCode() != 200) return List.of();
		JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
		JsonArray items = json.getAsJsonObject("tracks").getAsJsonArray("items");
		List<PlaybackState.Track> tracks = new ArrayList<>();
		for (JsonElement el : items) {
			JsonObject t = el.getAsJsonObject();
			tracks.add(new PlaybackState.Track(t.get("id").getAsString(), t.get("uri").getAsString(),
					t.get("name").getAsString(), joinArtists(t.getAsJsonArray("artists"))));
		}
		return tracks;
	}

	/** Starts playback of a single track (e.g. from a search result) on the active device. */
	public void playTrack(String trackUri) throws IOException, InterruptedException {
		String body = "{\"Uris\":[\"" + trackUri + "\"]}";
		send("PUT", withDeviceId("/me/player/play"), body);
	}

	/** Starts playback of an explicit list of track URIs (e.g. a Liked Songs page) on the active device. */
	public void playTracks(List<String> trackUris) throws IOException, InterruptedException {
		if (trackUris.isEmpty()) return;
		StringBuilder sb = new StringBuilder("{\"Uris\":[");
		for (int i = 0; i < trackUris.size(); i++) {
			if (i > 0) sb.append(',');
			sb.append('"').append(trackUris.get(i)).append('"');
		}
		sb.append("]}");
		send("PUT", withDeviceId("/me/player/play"), sb.toString());
	}

	/**
	 * Searches Spotify's catalog for playlists matching the query -- includes both
	 * user-created and Spotify-curated/official playlists (e.g. "Today's Top Hits"),
	 * as long as they're public. Purely personalized playlists (like a user's own
	 * Discover Weekly) won't show up here since they aren't publicly searchable.
	 */
	public List<PlaybackState.Playlist> searchPlaylists(String query) throws IOException, InterruptedException {
		if (query == null || query.isBlank()) return List.of();
		String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
		HttpResponse<String> resp = send("GET", "/search?q=" + encoded + "&type=playlist&limit=10", null);
		if (resp.statusCode() != 200) return List.of();
		JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
		if (!json.has("playlists") || json.get("playlists").isJsonNull()) return List.of();
		JsonArray items = json.getAsJsonObject("playlists").getAsJsonArray("items");
		List<PlaybackState.Playlist> playlists = new ArrayList<>();
		for (JsonElement el : items) {
			if (el == null || el.isJsonNull()) continue; // Spotify sometimes pads results with nulls
			JsonObject p = el.getAsJsonObject();
			// Same field rename as getPlaylists() below -- Spotify's `tracks` summary is
			// deprecated in favor of `items` (identical {href, total} shape).
			int trackCount = 0;
			if (p.has("items") && !p.get("items").isJsonNull()) {
				JsonObject itemsSummary = p.getAsJsonObject("items");
				trackCount = itemsSummary.has("total") && !itemsSummary.get("total").isJsonNull()
						? itemsSummary.get("total").getAsInt() : 0;
			} else if (p.has("tracks") && !p.get("tracks").isJsonNull()) {
				JsonObject tracksSummary = p.getAsJsonObject("tracks");
				trackCount = tracksSummary.has("total") && !tracksSummary.get("total").isJsonNull()
						? tracksSummary.get("total").getAsInt() : 0;
			}
			playlists.add(new PlaybackState.Playlist(p.get("id").getAsString(), p.get("uri").getAsString(),
					p.get("name").getAsString(), trackCount));
		}
		return playlists;
	}

	/**
	 * The user's saved "Liked Songs", most recently added first. Requires the user-library-read
	 * scope. Spotify only returns up to {@code limit} (max 50) tracks per request, so this pages
	 * through with {@code offset} until it runs out of items or hits the safety cap below --
	 * otherwise anyone with more than 50 liked songs would silently only ever see the first page.
	 */
	public List<PlaybackState.Track> getLikedSongs() throws IOException, InterruptedException {
		List<PlaybackState.Track> tracks = new ArrayList<>();
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
			if (items.size() < limit) break; // last page
			offset += limit;
			if (offset >= MAX_LIBRARY_FETCH) break; // safety cap against pathological account sizes
		}
		return tracks;
	}

	private String joinArtists(JsonArray artists) {
		List<String> names = new ArrayList<>();
		for (JsonElement a : artists) names.add(a.getAsJsonObject().get("name").getAsString());
		return String.join(", ", names);
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
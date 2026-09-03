package xien.jxsh.spotymc;

import xien.jxsh.spotymc.api.PlaybackState;
import xien.jxsh.spotymc.api.SpotifyApiClient;
import xien.jxsh.spotymc.audio.AudioPlayer;
import xien.jxsh.spotymc.audio.LibrespotProcess;
import xien.jxsh.spotymc.auth.SpotifyAuth;
import xien.jxsh.spotymc.config.ModConfig;
import xien.jxsh.spotymc.lyrics.LyricLine;
import xien.jxsh.spotymc.lyrics.LyricsService;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns the shared, thread-safe view of "what's currently playing" that the
 * HUD and the F12 GUI both read from. Polls Spotify every 2 s (well within
 * rate limits) and re-resolves lyrics only when the track actually changes.
 * <p>
 * Also owns the optional librespot subprocess that provides actual in-game
 * audio. That process is launched/kept alive from the same poll loop, with a
 * 10 s backoff on failure so a missing/bad librespotPath doesn't spam retries.
 * <p>
 * Design notes for the hot paths:
 * <ul>
 *   <li>All cross-thread state lives in {@link AtomicReference}s — lock-free reads from
 *       the render thread and the client tick.</li>
 *   <li>Lyrics + queue side-fetches run on a dedicated {@code ioPool} so they never
 *       serialise behind each other or behind {@code maintainAudio()}.</li>
 *   <li>{@link #getCurrentLyricLine()} uses the binary-search implementation in
 *       {@link LyricsService#currentLine}, keeping the per-frame cost O(log n).</li>
 * </ul>
 */
public class PlaybackPoller {
	public final SpotifyAuth auth = new SpotifyAuth();
	public final SpotifyApiClient api = new SpotifyApiClient(auth);
	public final LyricsService lyrics = new LyricsService();

	private final AtomicReference<PlaybackState> state = new AtomicReference<>(PlaybackState.NOTHING_PLAYING);
	private final AtomicReference<List<LyricLine>> currentLyrics = new AtomicReference<>(List.of());
	private final AtomicReference<List<PlaybackState.QueueItem>> queue = new AtomicReference<>(List.of());
	private volatile String lastTrackId = null;
	private volatile String lastError = null;
	/**
	 * Non-null when the Web API tier is unusable (no/invalid Client ID) while the audio tier
	 * (librespot) is still allowed to run independently. Read by the renderers so Search/
	 * Library/Queue/Now-Playing show one honest explanation instead of silently staying empty.
	 */
	private volatile String webApiDisabledReason = null;
	/**
	 * Detailed, multi-line breakdown of *why* the Web API tier is disabled -- e.g. which specific
	 * config.json field(s) are blank. Kept separate from {@link #webApiDisabledReason} (which is
	 * a short label suitable for the center/queue panels) so the full explanation only has to be
	 * rendered once, by the left panel, instead of being repeated verbatim in three places.
	 */
	private volatile List<String> webApiDiagnosticLines = List.of();

	// --- In-game audio (librespot) ---
	private LibrespotProcess librespot;
	private final AudioPlayer audioPlayer = new AudioPlayer();
	private volatile String audioError = null;
	private volatile long audioNextRetryAtMillis = 0L;
	private volatile long audioStartedAtMillis = 0L;
	private static final long AUDIO_START_GRACE_MS = 3000L;
	// Whether the player is actually in a world right now (vs. the title screen).
	// Starts false: the poller itself starts at client launch, before any world is joined.
	private volatile boolean inWorld = false;

	private ScheduledExecutorService executor;
	// Lyrics + queue side-fetches run here so they don't serialise behind the poll loop
	// or behind maintainAudio().
	private ExecutorService ioPool;

	// Cached linear gain derived from ModConfig#librespotOutputCapDb. Recomputed only
	// when the config value actually changes, avoiding a Math.pow every poll tick.
	private float lastCapDb = Float.NaN;
	private float lastGain = 1.0f;

	public void start() {
		executor = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "spotymc-poller");
			t.setDaemon(true);
			return t;
		});
		ioPool = Executors.newFixedThreadPool(2, r -> {
			Thread t = new Thread(r, "spotymc-io");
			t.setDaemon(true);
			return t;
		});
		executor.scheduleWithFixedDelay(this::poll, 0, 2, TimeUnit.SECONDS);
	}

	public void stop() {
		if (executor != null) executor.shutdownNow();
		if (ioPool != null) ioPool.shutdownNow();
		stopAudio();
	}

	/**
	 * Tells the poller whether the player is currently in a world.
	 * maintainAudio() uses this to tear librespot down at the title screen and bring
	 * it back automatically on the next JOIN.
	 */
	public void setInWorld(boolean inWorld) {
		this.inWorld = inWorld;
	}

	/**
	 * Forces extra playback-state refreshes outside the normal 2 s cadence.
	 * Staggered because Spotify needs a moment to register the change; an instant poll
	 * would often just re-read the stale state.
	 */
	public void pollSoonBurst() {
		if (executor == null || executor.isShutdown()) return;
		executor.schedule(this::poll, 300, TimeUnit.MILLISECONDS);
		executor.schedule(this::poll, 800, TimeUnit.MILLISECONDS);
		executor.schedule(this::poll, 1600, TimeUnit.MILLISECONDS);
	}

	/**
	 * Immediately drops the first {@code count} tracks from the displayed queue.
	 * Used when the user clicks a queue row so the UI updates before the next real poll.
	 */
	public void optimisticAdvanceQueue(int count) {
		queue.updateAndGet(list -> {
			if (count <= 0 || list.isEmpty()) return list;
			int drop = Math.min(count, list.size());
			return List.copyOf(list.subList(drop, list.size()));
		});
	}

	/** Skips to the previous track. Fire-and-forget (e.g. global hotkey). */
	public void previousTrack() {
		runOnIoPool(api::previous);
	}

	/** Skips to the next track. Fire-and-forget (e.g. global hotkey). */
	public void nextTrack() {
		runOnIoPool(api::next);
	}

	/** Pauses Spotify playback. Fire-and-forget. */
	public void pausePlayback() {
		runOnIoPool(api::pause);
	}

	/** Resumes Spotify playback. Fire-and-forget. */
	public void resumePlayback() {
		runOnIoPool(api::play);
	}

	/**
	 * Nudges Spotify Connect volume by {@code deltaPercent}, clamped to 0-100.
	 * Applies the change optimistically to the shared state first so rapid/held presses
	 * compound correctly instead of all reading the same stale polled value.
	 */
	public void adjustVolume(int deltaPercent) {
		int target = state.updateAndGet(s -> s.withOptimisticVolume(s.volumePercent + deltaPercent)).volumePercent;
		runOnIoPool(() -> api.setVolume(target));
	}

	private interface ThrowingRunnable {
		void run() throws Exception;
	}

	private void runOnIoPool(ThrowingRunnable action) {
		if (ioPool == null || ioPool.isShutdown()) return;
		ioPool.execute(() -> {
			try {
				action.run();
				pollSoonBurst();
			} catch (Exception e) {
				lastError = e.getMessage();
			}
		});
	}

	private void poll() {
		if (auth.isLoggedIn()) {
			// Never logged in at all -- the login screen handles this case, and there's
			// nothing to poll yet. Audio can still run independently either way.
			maintainAudio();
			return;
		}

		if (!auth.hasWebApiCredentials()) {
			// A refreshToken exists from an earlier login, but clientId has since been
			// removed from config.json. Don't retry every 2s against a call that's
			// guaranteed to fail (Spotify rejects a blank client_id on refresh) -- just
			// surface why the Web-API-backed UI isn't updating, and keep going with audio.
			SpotifyAuth.CredentialStatus credStatus = SpotifyAuth.checkCredentials();
			webApiDisabledReason = "Web features unavailable";
			webApiDiagnosticLines = buildMissingCredentialLines(credStatus);
			state.set(PlaybackState.NOTHING_PLAYING);
			currentLyrics.set(List.of());
			queue.set(List.of());
			lastTrackId = null;
			maintainAudio();
			return;
		}

		try {
			PlaybackState newState = api.getCurrentPlayback();
			state.set(newState);
			lastError = null;
			webApiDisabledReason = null;
			webApiDiagnosticLines = List.of();

			if (newState.trackId != null && !newState.trackId.equals(lastTrackId)) {
				lastTrackId = newState.trackId;
				// Side-fetches run on ioPool so they never block the next poll tick
				// or each other.
				CompletableFuture.runAsync(() -> {
					try {
						List<LyricLine> lines = lyrics.fetch(newState.title, newState.artists, newState.durationMs);
						currentLyrics.set(lines);
					} catch (Exception e) {
						currentLyrics.set(List.of());
					}
				}, ioPool);
				CompletableFuture.runAsync(() -> {
					try {
						queue.set(api.getQueue());
					} catch (Exception e) {
						queue.set(List.of());
					}
				}, ioPool);
			} else if (newState.trackId == null) {
				lastTrackId = null;
				currentLyrics.set(List.of());
				queue.set(List.of());
			}
		} catch (Exception e) {
			lastError = e.getMessage();
			// Spotify rejecting the client_id itself (as opposed to a transient network/5xx
			// blip) means the Web API tier is unusable until the user fixes it -- surface that
			// distinctly rather than a generic error that repeats every 2s.
			if (e.getMessage() != null && e.getMessage().contains("invalid_client")) {
				webApiDisabledReason = "Web features unavailable";
				webApiDiagnosticLines = List.of(
						"⚠ Client ID: Invalid",
						"Please check config.json");
			} else if (e.getMessage() != null && e.getMessage().contains("invalid_grant")) {
				// The refresh token itself was revoked/expired (e.g. access pulled from the
				// user's Spotify account page) -- no amount of retrying fixes this, it needs a
				// fresh browser login via the "Re-authenticate" button in Settings.
				webApiDisabledReason = "Spotify login expired";
				webApiDiagnosticLines = List.of(
						"⚠ Refresh Token: Invalid",
						"Re-authenticate in Settings");
			}
		}

		maintainAudio();
	}

	// --- In-game audio lifecycle ---

	private void maintainAudio() {
		if (!inWorld) {
			if (librespot != null) stopAudio();
			return;
		}

		ModConfig cfg = ModConfig.get();
		if (!cfg.librespotEnabled) {
			if (librespot != null) stopAudio();
			return;
		}

		// Re-assert the output cap every tick so a freshly-started process picks it up
		// immediately and config changes are applied without a restart.
		applyOutputCap(cfg);

		long now = System.currentTimeMillis();
		boolean processAlive = librespot != null && librespot.isRunning();
		boolean withinStartupGrace = (now - audioStartedAtMillis) < AUDIO_START_GRACE_MS;

		// Healthy = process alive AND (line open or still inside the startup grace window).
		if (processAlive && (audioPlayer.isActive() || withinStartupGrace)) return;
		if (now < audioNextRetryAtMillis) return; // cooling down after a failure

		stopAudio(); // clear half-broken state before retrying
		try {
			if (cfg.librespotPath == null || cfg.librespotPath.isBlank()) {
				throw new IllegalStateException("librespot isn't installed -- press F12 -> Settings -> Install librespot");
			}
			librespot = new LibrespotProcess(cfg.librespotPath, cfg.librespotDeviceName, cfg.librespotBitrate,
					cfg.librespotInitialVolume);
			librespot.start();
			audioPlayer.start(librespot.audioStream());
			audioStartedAtMillis = now;
			audioError = null;
		} catch (Exception e) {
			audioError = e.getMessage();
			audioNextRetryAtMillis = now + 10_000; // 10 s backoff
		}
	}

	/**
	 * Applies the configured dB attenuation to the in-game audio output.
	 * Gain is recomputed only when the config value changes (avoids Math.pow every poll).
	 */
	private void applyOutputCap(ModConfig cfg) {
		float db = cfg.librespotOutputCapDb;
		if (db != lastCapDb) {
			float gain = (float) Math.pow(10.0, db / 20.0);
			lastGain = Math.clamp(gain, 0f, 1f);
			lastCapDb = db;
			System.out.println("[spotymc] Applying librespot output cap: " + db
					+ "dB (linear gain " + String.format("%.2f", lastGain) + ")");
		}
		audioPlayer.setVolume(lastGain);
	}

	private void stopAudio() {
		audioPlayer.stop();
		if (librespot != null) {
			librespot.stop();
			librespot = null;
		}
	}

	/**
	 * Stops any running librespot process and returns once it's actually down.
	 * Required before deleting the binary (especially on Windows where a running exe is locked).
	 * Runs on the poller's own executor thread to avoid racing maintainAudio().
	 */
	public CompletableFuture<Void> stopAudioAndWait() {
		if (executor == null || executor.isShutdown()) {
			stopAudio();
			return CompletableFuture.completedFuture(null);
		}
		CompletableFuture<Void> future = new CompletableFuture<>();
		executor.execute(() -> {
			stopAudio();
			future.complete(null);
		});
		return future;
	}

	/** True once Spotify Connect's active device matches our librespot instance. */
	public boolean isPlayingThroughMinecraft() {
		PlaybackState s = state.get();
		return s.deviceName != null && s.deviceName.equalsIgnoreCase(ModConfig.get().librespotDeviceName);
	}

	public boolean isAudioRunning() {
		return librespot != null && librespot.isRunning() && audioPlayer.isActive();
	}

	public String getAudioError() {
		if (audioError != null) return audioError;
		return audioPlayer.getLastError();
	}

	public PlaybackState getState() {
		return state.get();
	}

	/**
	 * Non-null when the Web API tier (search, library, queue, lyrics, now-playing) is unusable
	 * because no Client ID is configured or Spotify rejected it -- while librespot audio keeps
	 * running independently. Renderers use this to show one honest message instead of leaving
	 * their panels silently empty.
	 */
	public String getWebApiDisabledReason() {
		return webApiDisabledReason;
	}

	/**
	 * Full explanation behind {@link #getWebApiDisabledReason()} -- e.g. exactly which
	 * config.json field(s) are blank or invalid, plus what to do about it. Rendered by the left
	 * panel only, so this level of detail doesn't need to be repeated in the center/queue panels.
	 */
	public List<String> getWebApiDiagnosticLines() {
		return webApiDiagnosticLines;
	}

	/**
	 * Builds a human-friendly explanation of exactly which required Spotify config.json field(s)
	 * are blank -- e.g. after someone hand-edits the file and drops a value by accident.
	 */
	private static List<String> buildMissingCredentialLines(SpotifyAuth.CredentialStatus status) {
		List<String> missingNames = new ArrayList<>(3);
		if (status.clientIdMissing()) missingNames.add("Client ID");
		if (status.redirectUriMissing()) missingNames.add("Redirect URI");
		if (status.refreshTokenMissing()) missingNames.add("Refresh Token");

		List<String> lines = new ArrayList<>(missingNames.size() + 2);
		if (missingNames.size() == 1) {
			lines.add("Looks like your Spotify " + missingNames.get(0) + " is missing from config.json.");
		} else {
			lines.add("Looks like some required Spotify settings are missing from config.json.");
		}
		for (String name : missingNames) {
			lines.add("⚠ " + name + ": Missing");
		}
		lines.add("Please check config.json");
		return lines;
	}

	/**
	 * Immediately reflects a seek in the shared state so the progress bar jumps instantly
	 * instead of waiting for the round-trip to Spotify.
	 */
	public void optimisticSeek(int positionMs) {
		state.updateAndGet(s -> s.trackId != null ? s.withOptimisticProgress(positionMs) : s);
	}

	/**
	 * Line to display right now, or null if there's no synced lyric for this instant.
	 * Delegates to the binary-search implementation in LyricsService (O(log n)).
	 */
	public LyricLine getCurrentLyricLine() {
		PlaybackState s = state.get();
		if (s.trackId == null) return null;
		List<LyricLine> lines = currentLyrics.get();
		if (lines.isEmpty()) return null;
		return LyricsService.currentLine(lines, s.estimatedProgressMs());
	}

	public boolean hasLyricsForCurrentTrack() {
		return !currentLyrics.get().isEmpty();
	}

	/** Upcoming queue (excludes the currently playing track). */
	public List<PlaybackState.QueueItem> getQueue() {
		return queue.get();
	}
}
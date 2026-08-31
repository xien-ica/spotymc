package xien.jxsh.spotymc;

import xien.jxsh.spotymc.api.PlaybackState;
import xien.jxsh.spotymc.api.SpotifyApiClient;
import xien.jxsh.spotymc.audio.AudioPlayer;
import xien.jxsh.spotymc.audio.LibrespotProcess;
import xien.jxsh.spotymc.auth.SpotifyAuth;
import xien.jxsh.spotymc.config.ModConfig;
import xien.jxsh.spotymc.lyrics.LyricLine;
import xien.jxsh.spotymc.lyrics.LyricsService;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns the shared, thread-safe view of "what's currently playing" that the
 * HUD and the F12 GUI both read from. Polls Spotify every 2s (well within
 * rate limits) and re-resolves lyrics only when the track actually changes.
 * <p>
 * Also owns the optional librespot subprocess that provides actual in-game
 * audio (see xien.jxsh.spotymc.audio). That process is launched/kept alive
 * from the same poll loop, with a 10s backoff on failure so a missing/bad
 * librespotPath doesn't spam retries every 2 seconds.
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

	// --- In-game audio (librespot) ---
	private LibrespotProcess librespot;
	private final AudioPlayer audioPlayer = new AudioPlayer();
	private volatile String audioError = null;
	private volatile long audioNextRetryAtMillis = 0L;
	private volatile long audioStartedAtMillis = 0L;
	private static final long AUDIO_START_GRACE_MS = 3000L;
	// Whether the player is actually in a world right now (vs. the title screen, a server
	// list, etc.) -- librespot has no concept of this on its own, so without tracking it
	// explicitly, "Save and Quit to Title" would just leave it streaming into nothing.
	// Starts false: the poller itself starts at client launch, before any world is joined.
	private volatile boolean inWorld = false;

	private ScheduledExecutorService executor;
	// Lyrics + queue side-fetches run here, off the poll loop's own thread, so they don't
	// serialize behind each other (or behind maintainAudio()) and stack up per-cycle latency.
	private ExecutorService ioPool;

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
	 * Tells the poller whether the player is currently in a world (true right after
	 * ClientPlayConnectionEvents. JOIN, false after DISCONNECT -- e.g. "Save and Quit to Title",
	 * a disconnect/kick, or a server switch). maintainAudio() uses this to actually tear down
	 * librespot at the title screen instead of leaving it running with no one listening, and to
	 * let it come back on its own the next time a world is joined.
	 */
	public void setInWorld(boolean inWorld) {
		this.inWorld = inWorld;
	}

	/**
	 * Forces an extra playback-state refresh outside the normal 2s cadence. Call this right
	 * after a user-initiated action (play/pause/skip/queue click) so the UI catches up quickly
	 * instead of waiting for the next scheduled tick. Staggered rather than a single immediate
	 * call because Spotify's own API needs a moment to register the change on their end -- an
	 * instant poll would often just re-read the stale state.
	 */
	public void pollSoonBurst() {
		if (executor == null || executor.isShutdown()) return;
		executor.schedule(this::poll, 300, TimeUnit.MILLISECONDS);
		executor.schedule(this::poll, 800, TimeUnit.MILLISECONDS);
		executor.schedule(this::poll, 1600, TimeUnit.MILLISECONDS);
	}

	/**
	 * Immediately drops the first {@code count} tracks from the displayed queue, ahead of the
	 * next real poll. Used when the user clicks a queue row to skip straight to it -- skipping
	 * past N tracks should remove all N from view, not just the one that was clicked, since
	 * skipToQueueIndex() advances past all of them too.
	 */
	public void optimisticAdvanceQueue(int count) {
		queue.updateAndGet(list -> {
			if (count <= 0 || list.isEmpty()) return list;
			int drop = Math.min(count, list.size());
			return List.copyOf(list.subList(drop, list.size()));
		});
	}

	/** Skips to the previous track. Fire-and-forget -- e.g. from the global hotkey. */
	public void previousTrack() {
		runOnIoPool(api::previous);
	}

	/** Skips to the next track. Fire-and-forget -- e.g. from the global hotkey. */
	public void nextTrack() {
		runOnIoPool(api::next);
	}

	/**
	 * Nudges Spotify Connect's own volume by {@code deltaPercent} (positive or negative),
	 * clamped to 0-100 and based on the last polled volume rather than round-tripping to
	 * Spotify first -- good enough for a hotkey that's typically pressed several times in a
	 * row, and any drift is corrected by the next regular poll.
	 */
	public void adjustVolume(int deltaPercent) {
		runOnIoPool(() -> {
			int current = state.get().volumePercent;
			int target = Math.clamp(current + deltaPercent, 0, 100);
			api.setVolume(target);
		});
	}

	private interface ThrowingRunnable {
		void run() throws Exception;
	}

	/** Runs a one-off API action on the shared io pool, then nudges the poller to resync quickly. */
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
		if (auth.isLoggedIn()) return;
		try {
			PlaybackState newState = api.getCurrentPlayback();
			state.set(newState);
			lastError = null;

			if (newState.trackId != null && !newState.trackId.equals(lastTrackId)) {
				lastTrackId = newState.trackId;
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
						// Track advanced -- the song that just started is no longer "up next",
						// so pull the fresh queue (Spotify's own /queue endpoint already excludes
						// whatever is currently playing).
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
		}

		maintainAudio();
	}

	// --- In-game audio lifecycle ---

	private void maintainAudio() {
		if (!inWorld) {
			// At the title screen (or between worlds/servers) -- nothing should be listening,
			// so tear librespot down instead of leaving it streaming into nothing. It'll come
			// back on its own via the branch below once JOIN flips inWorld back to true.
			if (librespot != null) stopAudio();
			return;
		}

		ModConfig cfg = ModConfig.get();
		if (!cfg.librespotEnabled) {
			if (librespot != null) stopAudio();
			return;
		}
		// Reasserted every tick, unconditionally -- the early returns just below (audio already
		// healthy, or cooling down after a failure) would otherwise skip this entirely once
		// playback is running, leaving the cap applied only once, right at startup.
		applyOutputCap();

		boolean processAlive = librespot != null && librespot.isRunning();
		boolean withinStartupGrace = System.currentTimeMillis() - audioStartedAtMillis < AUDIO_START_GRACE_MS;
		// "Healthy" means the process is alive AND either the line is actually open and playing,
		// or we only just started it and haven't given it a chance to open the line yet.
		// Trusting processAlive alone (the old check) hides a dead SourceDataLine forever.
		if (processAlive && (audioPlayer.isActive() || withinStartupGrace)) return;
		if (System.currentTimeMillis() < audioNextRetryAtMillis) return; // cooling down after a failure

		stopAudio(); // clear out whatever half-broken state we're in before retrying
		try {
			if (cfg.librespotPath == null || cfg.librespotPath.isBlank()) {
				throw new IllegalStateException("librespot isn't installed -- press F12 -> Settings -> Install librespot");
			}
			librespot = new LibrespotProcess(cfg.librespotPath, cfg.librespotDeviceName, cfg.librespotBitrate,
					cfg.librespotInitialVolume);
			librespot.start();
			audioPlayer.start(librespot.audioStream());
			audioStartedAtMillis = System.currentTimeMillis();
			audioError = null;
		} catch (Exception e) {
			audioError = e.getMessage();
			audioNextRetryAtMillis = System.currentTimeMillis() + 10_000; // back off 10s before retrying
		}
	}

	/**
	 * Applies a fixed attenuation to libre spot's actual in-game output loudness (ModConfig
	 * #librespotOutputCapDb, default -8dB) -- independent of Spotify Connect's own volume, which
	 * still scales the full 0-100% range in F12/the Spotify app as normal. This just quietens
	 * wherever that lands, so maxing out Connect volume in-game still leaves room to hear
	 * Minecraft's own sound effects, without touching any of Minecraft's own volume settings.
	 * Converted from dB to a linear amplitude multiplier via the standard 10^(dB/20) formula --
	 * a plain percentage cut (e.g. 80% amplitude) is only ~-2dB and barely audible, since loudness
	 * perception is logarithmic, not linear. Re-applied every poll tick so it's picked up promptly
	 * if librespot just (re)started.
	 */
	private float lastLoggedCapDb = Float.NaN;

	private void applyOutputCap() {
		ModConfig cfg = ModConfig.get();
		float gain = (float) Math.pow(10.0, cfg.librespotOutputCapDb / 20.0);
		gain = Math.clamp(gain, 0f, 1f);
		audioPlayer.setVolume(gain);
		if (cfg.librespotOutputCapDb != lastLoggedCapDb) {
			System.out.println("[spotymc] Applying librespot output cap: " + cfg.librespotOutputCapDb
					+ "dB (linear gain " + String.format("%.2f", gain) + ")");
			lastLoggedCapDb = cfg.librespotOutputCapDb;
		}
	}

	private void stopAudio() {
		audioPlayer.stop();
		if (librespot != null) {
			librespot.stop();
			librespot = null;
		}
	}

	/**
	 * Stops any running librespot process and returns once it's actually down -- for a caller
	 * (uninstall) that's about to delete the binary and needs it not to still be running out from
	 * under that delete. On Windows especially, a running exe's file is locked and can't be
	 * deleted at all while the process holds it open.
	 * <p>
	 * Runs the actual stop on this poller's own executor thread, the only thread that's otherwise
	 * allowed to touch the {@code librespot} field (poll() -> maintainAudio() runs there too) --
	 * calling stopAudio() directly from another thread (e.g. the render thread) would race that
	 * loop. Callers should also flip ModConfig#librespotEnabled to false first (and save it)
	 * so maintainAudio() doesn't just start it back up again on its next tick.
	 */
	public CompletableFuture<Void> stopAudioAndWait() {
		if (executor == null || executor.isShutdown()) {
			// No poll loop running (start() never called, or stop() already has) -- nothing else
			// can be touching the field concurrently, so it's safe to just do it here directly.
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

	/** True once Spotify Connect's active device matches our librespot instance -- i.e. audio should be audible. */
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
	 * Immediately reflects a seek in the shared state, ahead of the next real poll. Used right
	 * when the user releases a drag on the progress bar, so playback appears to jump instantly
	 * instead of waiting on the round-trip to Spotify and back.
	 */
	public void optimisticSeek(int positionMs) {
		state.updateAndGet(s -> s.trackId != null ? s.withOptimisticProgress(positionMs) : s);
	}

	/** Line to display right now, or null if there's no synced lyric for this instant. */
	public LyricLine getCurrentLyricLine() {
		PlaybackState s = state.get();
		if (s.trackId == null) return null;
		return LyricsService.currentLine(currentLyrics.get(), s.estimatedProgressMs());
	}

	public boolean hasLyricsForCurrentTrack() {
		return !currentLyrics.get().isEmpty();
	}

	/** Upcoming queue (excludes the currently playing track), refreshed each time the track changes. */
	public List<PlaybackState.QueueItem> getQueue() {
		return queue.get();
	}

}
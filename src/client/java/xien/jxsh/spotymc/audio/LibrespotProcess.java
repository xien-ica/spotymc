package xien.jxsh.spotymc.audio;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Wraps the librespot subprocess. librespot is NOT bundled with the mod --
 * download a binary for your OS from <a href="https://github.com/librespot-org/librespot/releases">...</a>
 * (or `cargo install librespot`) and point ModConfig#librespotPath at it.
 * <p>
 * It's launched in Spotify Connect "discovery" (Zeroconf) mode: no
 * credentials are ever given to it, and none are stored by this mod. Open
 * the real Spotify app once, tap the device icon, and select the configured
 * device name (default "Minecraft") -- Spotify's own app hands librespot its
 * session, exactly like connecting to a smart speaker.
 * <p>
 * Raw PCM (44100Hz, 16-bit signed, stereo, little-endian -- libre spot's
 * default) streams on stdout via --backend pipe, which AudioPlayer reads.
 * Volume commands sent through the normal Web API (SpotifyApiClient#setVolume)
 * are applied by libre spot's own soft mixer before the PCM ever reaches us.
 */
public class LibrespotProcess {
	private final String binaryPath;
	private final String deviceName;
	private final int bitrate;
	private final int initialVolume;

	private Process process;

	public LibrespotProcess(String binaryPath, String deviceName, int bitrate, int initialVolume) {
		this.binaryPath = binaryPath;
		this.deviceName = deviceName;
		this.bitrate = bitrate;
		this.initialVolume = initialVolume;
	}

	/** Starts librespot in zeroconf/discovery mode. Safe to call again after stop(). */
	public synchronized void start() throws IOException {
		if (isRunning()) return;

		List<String> command = new ArrayList<>();
		command.add(binaryPath);
		command.add("--name"); command.add(deviceName);
		command.add("--device-type"); command.add("gameconsole");
		command.add("--backend"); command.add("pipe"); // no --device -> raw PCM goes to stdout
		command.add("--bitrate"); command.add(String.valueOf(bitrate));
		// Without this, librespot starts at its own internal default volume (which is what was
		// showing up in-game as an unexpected ~49%) until Spotify Connect syncs a "real" value.
		command.add("--initial-volume"); command.add(String.valueOf(Math.clamp(initialVolume, 0, 100)));
		// librespot defaults to a logarithmic volume curve, which maps --initial-volume through
		// that curve rather than treating it as a plain percentage -- e.g. asking for 80 can come
		// back from Spotify Connect reporting more like 71%. Forcing linear makes the number we
		// pass in match the number Spotify Connect (and this mod's volume slider) actually shows.
		command.add("--volume-ctrl"); command.add("linear");
		command.add("--disable-audio-cache"); // no reason to fill disk under .Minecraft for this

		ProcessBuilder builder = new ProcessBuilder(command);
		process = builder.start();

		// librespot logs (including zeroconf/login diagnostics) go to stderr; stdout is
		// audio-only. Drain stderr on a daemon thread so it can't block the process and so
		// you can see connection issues in the game log.
		Thread stderrDrain = new Thread(this::drainStderr, "librespot-stderr");
		stderrDrain.setDaemon(true);
		stderrDrain.start();
	}

	private void drainStderr() {
		Process p = process;
		if (p == null) return;
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getErrorStream()))) {
			String line;
			while ((line = reader.readLine()) != null) {
				System.out.println("[librespot] " + line);
			}
		} catch (IOException ignored) {
			// process was stopped from under us; nothing to do
		}
	}

	/** Raw PCM stream: 44100Hz, 16-bit signed, stereo, little-endian. Null if not started. */
	public InputStream audioStream() {
		return process != null ? process.getInputStream() : null;
	}

	public boolean isRunning() {
		return process != null && process.isAlive();
	}

	public synchronized void stop() {
		if (process != null) {
			process.destroy();
			process = null;
		}
	}
}
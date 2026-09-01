package xien.jxsh.spotymc.audio;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Wraps the librespot subprocess. The binary is obtained via {@link LibrespotInstaller}
 * (or supplied manually) and pointed at by ModConfig#librespotPath.
 * <p>
 * Launched in Spotify Connect "discovery" (Zeroconf) mode: no credentials are ever
 * given to it. Open the real Spotify app, tap the device icon, and select the
 * configured device name (default "Minecraft") — Spotify hands librespot its session
 * exactly like connecting to a smart speaker.
 * <p>
 * Raw PCM (44100 Hz, 16-bit signed, stereo, little-endian) streams on stdout via
 * {@code --backend pipe}, which {@link AudioPlayer} reads. Volume commands sent
 * through the Web API are applied by librespot's own soft mixer before the PCM
 * reaches us.
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

	/** Starts librespot in zeroconf/discovery mode. Safe to call again after {@link #stop()}. */
	public synchronized void start() throws IOException {
		if (isRunning()) return;

		// Fixed capacity avoids a couple of internal resizes for a known argument list.
		List<String> command = new ArrayList<>(16);
		command.add(binaryPath);
		command.add("--name");
		command.add(deviceName);
		command.add("--device-type");
		command.add("gameconsole");
		command.add("--backend");
		command.add("pipe");                    // raw PCM on stdout
		command.add("--bitrate");
		command.add(String.valueOf(bitrate));
		// Without this, librespot starts at its own internal default (~49 %) until
		// Spotify Connect syncs a real value.
		command.add("--initial-volume");
		command.add(String.valueOf(Math.clamp(initialVolume, 0, 100)));
		// Force linear curve so the number we pass matches what Spotify Connect reports.
		command.add("--volume-ctrl");
		command.add("linear");
		command.add("--disable-audio-cache");   // no reason to fill disk under .minecraft

		ProcessBuilder builder = new ProcessBuilder(command);
		process = builder.start();

		// Drain stderr on a daemon thread so logs can't block the process and so
		// connection issues appear in the game log.
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
			// process was stopped from under us
		}
	}

	/** Raw PCM stream: 44100 Hz, 16-bit signed, stereo, little-endian. Null if not started. */
	public InputStream audioStream() {
		return process != null ? process.getInputStream() : null;
	}

	public boolean isRunning() {
		return process != null && process.isAlive();
	}

	/**
	 * Stops the process and blocks until it has actually exited.
	 * {@link Process#destroy()} only requests termination; on Windows the OS can take a
	 * moment to release the exe's file handle, so a caller that deletes the binary
	 * immediately after would still hit "file in use". Waiting here guarantees the
	 * handle is released by the time this method returns.
	 */
	public synchronized void stop() {
		if (process == null) return;
		Process p = process;
		process = null;
		p.destroy();
		try {
			if (!p.waitFor(5, TimeUnit.SECONDS)) {
				p.destroyForcibly();
				p.waitFor(5, TimeUnit.SECONDS);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			p.destroyForcibly();
		}
	}
}
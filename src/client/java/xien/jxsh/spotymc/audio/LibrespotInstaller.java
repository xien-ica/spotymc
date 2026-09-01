package xien.jxsh.spotymc.audio;

import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Sets up in-game audio by downloading a precompiled librespot binary and verifying its
 * checksum, rather than compiling librespot from source on the user's machine.
 *
 * <p>The binaries this installer fetches are NOT built or signed by upstream librespot
 * (it does not publish official releases). They are built by SpotyMC's own public,
 * pinned-commit GitHub Actions workflow, so the exact source revision and build steps
 * are auditable and reproducible.
 *
 * <p>Every download is verified against a SHA-256 checksum hardcoded below before the
 * file is ever marked executable or used. If verification fails, the file is deleted
 * and the install fails closed.
 */
public final class LibrespotInstaller {

	private LibrespotInstaller() {}

	// =============================================================================================
	// Pinned release: update BOTH the version tag and the checksums together, from the
	// SHA256SUMS.txt published on the matching GitHub Release. Never point this at a
	// mutable/"latest" URL.
	// =============================================================================================

	private static final String RELEASE_TAG = "librespot-v0.8.0-build3";
	private static final String RELEASE_BASE_URL =
			"https://github.com/xien-ica/spotymc/releases/download/" + RELEASE_TAG + "/";

	private record BinarySpec(String assetName, String sha256) {}

	private static final BinarySpec WINDOWS_X64 = new BinarySpec(
			"librespot-windows-x86_64.exe",
			"51a77499fc796c26e6eb805db77d835a4c8328ffba697abba02e123047389cbf");
	private static final BinarySpec LINUX_X64 = new BinarySpec(
			"librespot-linux-x86_64",
			"6fc32c5b8a6a4cf1116dbccb2329960a9128a0304741062d0fded120bd8b6327");
	private static final BinarySpec MACOS_X64 = new BinarySpec(
			"librespot-macos-x86_64",
			"6512be6e2627b47c2e93cf88591341c7740a0726cbb4dd89962cd6f8e86b9d3a");
	private static final BinarySpec MACOS_ARM64 = new BinarySpec(
			"librespot-macos-arm64",
			"b1c6042187f0df546e96ed0e94bccee3210aae7f10db69581316a4d534d4fd61");

	private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(5);
	private static final long ESTIMATED_DOWNLOAD_BYTES = 20L * 1024 * 1024;

	// Shared HttpClient for the (rare) install path — avoids creating a new one per download.
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(DOWNLOAD_TIMEOUT)
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	public record InstallResult(boolean success, String path, String message) {}

	// =============================================================================================
	// Shared state for the single background install
	// =============================================================================================

	private static final ExecutorService INSTALL_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "spotymc-librespot-install");
		thread.setDaemon(true);
		return thread;
	});

	private static final List<Consumer<String>> PROGRESS_LISTENERS = new CopyOnWriteArrayList<>();
	private static final List<Consumer<InstallResult>> COMPLETION_LISTENERS = new CopyOnWriteArrayList<>();
	private static final Object INSTALL_LOCK = new Object();

	private static volatile boolean installing = false;
	private static volatile boolean cancelRequested = false;
	private static volatile String lastProgressLine = "";
	private static volatile Thread installThread = null;

	// =============================================================================================
	// Public status checks
	// =============================================================================================

	public static boolean isInstalling() {
		return installing;
	}

	/** True if the configured path points at a real, runnable binary. */
	public static boolean isInstalled(String configuredPath) {
		if (configuredPath == null || configuredPath.isBlank()) return false;
		return Files.isExecutable(Paths.get(configuredPath));
	}

	/** Human-readable size estimate for the settings UI. */
	public static String estimateLabel() {
		return "Precompiled librespot binary ~" + formatBytes(ESTIMATED_DOWNLOAD_BYTES)
				+ " (verified download, no local build)";
	}

	// =============================================================================================
	// Async entry point (what the UI actually calls)
	// =============================================================================================

	public static void installLibrespotAsync(Consumer<String> onProgress, Consumer<InstallResult> onComplete) {
		if (onProgress != null) PROGRESS_LISTENERS.add(onProgress);
		if (onComplete != null) COMPLETION_LISTENERS.add(onComplete);

		synchronized (INSTALL_LOCK) {
			if (installing) {
				if (onProgress != null && !lastProgressLine.isEmpty()) {
					onProgress.accept(lastProgressLine);
				}
				return;
			}
			installing = true;
			cancelRequested = false;
		}

		INSTALL_EXECUTOR.submit(() -> {
			installThread = Thread.currentThread();
			InstallResult result = installLibrespot(LibrespotInstaller::broadcastProgress);
			if (cancelRequested && !result.success()) {
				result = new InstallResult(false, null, "Install cancelled.");
			}
			synchronized (INSTALL_LOCK) {
				installing = false;
				installThread = null;
			}
			for (Consumer<InstallResult> listener : COMPLETION_LISTENERS) {
				listener.accept(result);
			}
			COMPLETION_LISTENERS.clear();
			PROGRESS_LISTENERS.clear();
		});
	}

	public static void removeListeners(Consumer<String> onProgress, Consumer<InstallResult> onComplete) {
		if (onProgress != null) PROGRESS_LISTENERS.remove(onProgress);
		if (onComplete != null) COMPLETION_LISTENERS.remove(onComplete);
	}

	private static void broadcastProgress(String line) {
		lastProgressLine = line;
		for (Consumer<String> listener : PROGRESS_LISTENERS) {
			listener.accept(line);
		}
	}

	public static void cancelInstall() {
		if (!installing) return;
		cancelRequested = true;
		Thread thread = installThread;
		if (thread != null) thread.interrupt();
	}

	// =============================================================================================
	// The main install flow: resolve platform → download → verify checksum → mark executable
	// =============================================================================================

	public static InstallResult installLibrespot(Consumer<String> onProgress) {
		BinarySpec spec;
		try {
			spec = resolveBinarySpecForPlatform();
		} catch (UnsupportedOperationException e) {
			return new InstallResult(false, null, e.getMessage());
		}

		Path destDir = installDirectory();
		Path destFile = destDir.resolve(spec.assetName());

		try {
			Files.createDirectories(destDir);
		} catch (IOException e) {
			return new InstallResult(false, null, "Could not create install directory: " + e.getMessage());
		}

		report(onProgress, "Downloading librespot " + RELEASE_TAG + " (" + spec.assetName() + ")...");
		Path tempFile;
		try {
			tempFile = download(RELEASE_BASE_URL + spec.assetName(), destDir, onProgress);
		} catch (IOException | InterruptedException e) {
			if (e instanceof InterruptedException) Thread.currentThread().interrupt();
			if (cancelRequested) return new InstallResult(false, null, "Install cancelled.");
			return new InstallResult(false, null, "Download failed: " + e.getMessage());
		}
		if (cancelRequested) {
			deleteQuietly(tempFile);
			return new InstallResult(false, null, "Install cancelled.");
		}

		report(onProgress, "Verifying checksum...");
		String actualSha256;
		try {
			actualSha256 = sha256Hex(tempFile);
		} catch (IOException | NoSuchAlgorithmException e) {
			deleteQuietly(tempFile);
			return new InstallResult(false, null, "Could not verify checksum: " + e.getMessage());
		}
		if (!actualSha256.equalsIgnoreCase(spec.sha256())) {
			deleteQuietly(tempFile);
			return new InstallResult(false, null,
					"Checksum mismatch for " + spec.assetName() + " -- expected " + spec.sha256()
							+ " but got " + actualSha256 + ". Refusing to install a binary that doesn't "
							+ "match the published SHA256SUMS.txt for " + RELEASE_TAG + ".");
		}
		report(onProgress, "Checksum verified.");

		try {
			Files.move(tempFile, destFile, StandardCopyOption.REPLACE_EXISTING);
			if (!isWindows()) {
				makeExecutable(destFile);
			}
		} catch (IOException e) {
			deleteQuietly(tempFile);
			return new InstallResult(false, null, "Could not finalize install: " + e.getMessage());
		}

		if (!Files.isExecutable(destFile)) {
			return new InstallResult(false, null, "Downloaded file at " + destFile + " is not executable.");
		}

		report(onProgress, "librespot installed at " + destFile);
		return new InstallResult(true, destFile.toString(), "Installed successfully.");
	}

	/**
	 * Removes the installed librespot binary (plain delete — no toolchain to clean up).
	 */
	public static InstallResult uninstallLibrespot(String path, Consumer<String> onProgress) {
		if (path == null || path.isBlank()) {
			return new InstallResult(true, null, "Nothing to uninstall.");
		}
		Path binary = Paths.get(path);
		report(onProgress, "Removing " + binary + "...");
		try {
			Files.deleteIfExists(binary);
		} catch (IOException e) {
			return new InstallResult(false, path, "Could not delete " + binary + ": " + e.getMessage());
		}
		return new InstallResult(true, null, "librespot uninstalled.");
	}

	// =============================================================================================
	// Platform resolution
	// =============================================================================================

	private static BinarySpec resolveBinarySpecForPlatform() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
		boolean isArm64 = Set.of("aarch64", "arm64").contains(arch);

		if (os.contains("win")) {
			return WINDOWS_X64;
		}
		if (os.contains("mac") || os.contains("darwin")) {
			return isArm64 ? MACOS_ARM64 : MACOS_X64;
		}
		if (os.contains("nux") || os.contains("nix")) {
			if (isArm64) {
				throw new UnsupportedOperationException(
						"No precompiled librespot build is published for Linux ARM64 yet. "
								+ "Please open an issue on the SpotyMC repository.");
			}
			return LINUX_X64;
		}
		throw new UnsupportedOperationException("Unsupported platform: " + os + " / " + arch);
	}

	private static Path installDirectory() {
		return Paths.get(System.getProperty("user.home"), ".spotymc", "librespot");
	}

	// =============================================================================================
	// Download + checksum helpers
	// =============================================================================================

	private static Path download(String url, Path destDir, Consumer<String> onProgress)
			throws IOException, InterruptedException {
		Path tempFile = Files.createTempFile(destDir, "librespot-download-", ".part");
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.timeout(DOWNLOAD_TIMEOUT)
				.GET()
				.build();

		HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
		if (response.statusCode() != 200) {
			deleteQuietly(tempFile);
			throw new IOException("Server returned HTTP " + response.statusCode() + " for " + url);
		}

		try (InputStream in = response.body()) {
			Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
		}
		report(onProgress, "Downloaded " + formatBytes(Files.size(tempFile)) + ".");
		return tempFile;
	}

	private static String sha256Hex(Path file) throws IOException, NoSuchAlgorithmException {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		try (InputStream in = Files.newInputStream(file)) {
			byte[] buffer = new byte[8192];
			int read;
			while ((read = in.read(buffer)) != -1) {
				digest.update(buffer, 0, read);
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	private static void makeExecutable(@NonNull Path file) throws IOException {
		if (!file.toFile().setExecutable(true, false)) {
			throw new IOException("Failed to mark " + file + " as executable.");
		}
	}

	private static void deleteQuietly(Path path) {
		try {
			Files.deleteIfExists(path);
		} catch (IOException ignored) {
			// best effort
		}
	}

	// =============================================================================================
	// Small shared utilities
	// =============================================================================================

	private static String formatBytes(long bytes) {
		double gigabytes = bytes / (1024.0 * 1024 * 1024);
		if (gigabytes >= 1) return String.format(Locale.ROOT, "%.1f GB", gigabytes);
		return String.format(Locale.ROOT, "%d MB", Math.max(1, bytes / (1024 * 1024)));
	}

	private static void report(Consumer<String> onProgress, String line) {
		if (onProgress != null) onProgress.accept(line);
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	}
}
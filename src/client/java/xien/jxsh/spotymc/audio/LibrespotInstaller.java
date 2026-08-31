package xien.jxsh.spotymc.audio;

import org.jspecify.annotations.NonNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.FileStore;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Sets up in-game audio: checks/installs cargo (Windows only, via bundled rustup-init.exe),
 * builds librespot from source via cargo, returns the binary path.
 * On macOS/Linux without cargo, points the user to <a href="https://rustup.rs">...</a> instead.
 */
public final class LibrespotInstaller {

	private LibrespotInstaller() {
	}
	private static final String LIBRESPOT_GIT_URL = "https://github.com/librespot-org/librespot.git";
	private static final String BUNDLED_RUSTUP_RESOURCE = "/rust-installer/rustup-init.exe";
	private static final long RUST_TOOLCHAIN_BYTES = 700L * 1024 * 1024;   // ~/.rustup, permanent
	private static final long CARGO_CACHE_BYTES = 400L * 1024 * 1024;      // ~/.cargo, permanent
	private static final long BUILD_SCRATCH_BYTES = 2_000L * 1024 * 1024; // OS temp dir, temporary
	private static final long TOTAL_ESTIMATE_BYTES =
			RUST_TOOLCHAIN_BYTES + CARGO_CACHE_BYTES + BUILD_SCRATCH_BYTES;

	// Fail fast rather than risk an out-of-space build mid-compile.
	private static final long MIN_FREE_SPACE_BYTES = TOTAL_ESTIMATE_BYTES + 500L * 1024 * 1024;

	// Only clean up scratch folders older than this, to avoid touching a concurrent build.
	private static final Duration STALE_TEMP_AGE = Duration.ofMinutes(10);

	public record InstallResult(boolean success, String path, String message) {}

	// =============================================================================================
	// Shared state for the single background install
	// =============================================================================================
	// Static so the running install survives the settings screen being closed/reopened; all screen
	// instances attach as listeners to one real install instead of risking a duplicate build.

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
	private static volatile Process currentProcess = null;
	private static volatile String lastProgressLine = "";

	// =============================================================================================
	// Public status checks
	// =============================================================================================

	public static boolean isInstalling() {
		return installing;
	}

	/** True if cargo is runnable, on PATH or in the default ~/.cargo/bin. */
	public static boolean isCargoAvailable() {
		return findCargo() != null;
	}

	/** True if the configured path points at a real, runnable binary. */
	public static boolean isInstalled(String configuredPath) {
		if (configuredPath == null || configuredPath.isBlank()) return false;
		return Files.isExecutable(Paths.get(configuredPath));
	}

	/** Human-readable size estimate for the settings UI. */
	public static String estimateLabel() {
		return "Rust toolchain ~" + formatBytes(RUST_TOOLCHAIN_BYTES)
				+ " + librespot build ~" + formatBytes(CARGO_CACHE_BYTES + BUILD_SCRATCH_BYTES)
				+ " (~" + formatBytes(TOTAL_ESTIMATE_BYTES) + " total, most of it temporary)";
	}

	// =============================================================================================
	// Async entry point (what the UI actually calls)
	// =============================================================================================

	/**
	 * Starts the installation, or attaches listeners to one already in flight. Safe to call from the
	 * render thread; work runs on {@link #INSTALL_EXECUTOR}.
	 */
	public static void installLibrespotAsync(Consumer<String> onProgress, Consumer<InstallResult> onComplete) {
		if (onProgress != null) PROGRESS_LISTENERS.add(onProgress);
		if (onComplete != null) COMPLETION_LISTENERS.add(onComplete);

		synchronized (INSTALL_LOCK) {
			if (installing) {
				// Already running -- catch this listener up instead of starting a second build.
				if (onProgress != null && !lastProgressLine.isEmpty()) {
					onProgress.accept(lastProgressLine);
				}
				return;
			}
			installing = true;
			cancelRequested = false;
		}

		INSTALL_EXECUTOR.submit(() -> {
			InstallResult result = installLibrespot(LibrespotInstaller::broadcastProgress);
			if (cancelRequested && !result.success()) {
				result = new InstallResult(false, null, "Install cancelled.");
			}
			synchronized (INSTALL_LOCK) {
				installing = false;
				currentProcess = null;
			}
			for (Consumer<InstallResult> listener : COMPLETION_LISTENERS) {
				listener.accept(result);
			}
			COMPLETION_LISTENERS.clear();
			PROGRESS_LISTENERS.clear();
		});
	}

	/** Detaches a closing screen's listeners; the installation itself keeps running. */
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

	/** Force-kills whatever's currently running; also checked between steps for gaps. */
	public static void cancelInstall() {
		if (!installing) return;
		cancelRequested = true;
		Process runningProcess = currentProcess;
		if (runningProcess != null) runningProcess.destroyForcibly();
	}

	// =============================================================================================
	// The main install flow
	// =============================================================================================

	/**
	 * Full installation flow: cleanup stale scratch -> check disk space -> ensure cargo (installing Rust
	 * on Windows if needed) -> cargo-build librespot -> verify the resulting binary.
	 */
	public static InstallResult installLibrespot(Consumer<String> onProgress) {
		cleanupStaleBuildScratch(onProgress);
		if (cancelRequested) return new InstallResult(false, null, "Install cancelled.");

		long available = availableBytes();
		if (available < MIN_FREE_SPACE_BYTES) {
			return new InstallResult(false, null,
					"Not enough disk space: " + formatBytes(available) + " free, need roughly "
							+ formatBytes(TOTAL_ESTIMATE_BYTES) + ". Free up space and try again.");
		}
		report(onProgress, "Estimated space needed: " + estimateLabel());

		if (!isCargoAvailable()) {
			if (!isWindows()) {
				return new InstallResult(false, null,
						"Rust/Cargo not found. Install it from https://rustup.rs, then try again.");
			}
			report(onProgress, "Rust not found -- running the bundled installer...");
			String[] lastLine = new String[1];
			boolean installedOk = runBundledRustupInstaller(line -> {
				lastLine[0] = line;
				report(onProgress, line);
			});
			if (!installedOk || !isCargoAvailable()) {
				String detail = (lastLine[0] != null && !lastLine[0].isBlank())
						? " Installer said: \"" + lastLine[0] + "\""
						: "";
				return new InstallResult(false, null,
						"Rust install didn't complete." + detail
								+ " Try running it yourself from https://rustup.rs.");
			}
			report(onProgress, "Rust installed.");
		}
		if (cancelRequested) return new InstallResult(false, null, "Install cancelled.");

		report(onProgress, "Building librespot from source...");
		InstallResult buildFailure = runCargoInstall(onProgress);
		if (buildFailure != null) return buildFailure;

		String binaryPath = resolveInstalledBinary();
		if (binaryPath == null) {
			return new InstallResult(false, null,
					"cargo reported success, but the librespot binary wasn't found in ~/.cargo/bin");
		}
		return new InstallResult(true, binaryPath, "Installed!");
	}

	/** Uninstalls via `cargo uninstall`, falling back to deleting the binary directly. */
	public static InstallResult uninstallLibrespot(String configuredPath, Consumer<String> onProgress) {
		report(onProgress, "Uninstalling librespot...");

		String cargo = findCargo();
		if (cargo != null) {
			try {
				ProcessBuilder builder = new ProcessBuilder(cargo, "uninstall", "librespot");
				builder.redirectErrorStream(true);
				Process process = builder.start();
				streamLines(process, onProgress);
				process.waitFor();
			} catch (IOException | InterruptedException e) {
				if (e instanceof InterruptedException) Thread.currentThread().interrupt();
				// fall through to direct-delete backstop
			}
		}

		try {
			if (configuredPath != null && !configuredPath.isBlank()) {
				Files.deleteIfExists(Paths.get(configuredPath));
			}
		} catch (IOException e) {
			return new InstallResult(false, null, "Couldn't remove the librespot binary: " + e.getMessage());
		}
		return new InstallResult(true, null, "librespot uninstalled.");
	}

	// =============================================================================================
	// Step implementations: installing Rust
	// =============================================================================================

	private static boolean runBundledRustupInstaller(Consumer<String> onProgress) {
		try {
			Path installer = extractBundledRustupInstaller();
			ProcessBuilder builder = new ProcessBuilder(
					installer.toString(), "-y", "--default-toolchain", "stable", "--profile", "minimal");
			builder.redirectErrorStream(true);
			Process process = builder.start();
			currentProcess = process;
			streamLines(process, onProgress);
			return process.waitFor() == 0;
		} catch (IOException | InterruptedException e) {
			if (e instanceof InterruptedException) Thread.currentThread().interrupt();
			if (cancelRequested) return false;
			report(onProgress, "Rust installer failed: " + e.getMessage());
			return false;
		} finally {
			currentProcess = null;
		}
	}

	/** Must stay named "rustup-init.exe" -- rustup picks its behavior based on filename. */
	private static Path extractBundledRustupInstaller() throws IOException {
		Path destDir = Paths.get(System.getProperty("java.io.tmpdir"), "spotymc-rustup");
		Files.createDirectories(destDir);
		Path dest = destDir.resolve("rustup-init.exe");
		try (InputStream in = LibrespotInstaller.class.getResourceAsStream(BUNDLED_RUSTUP_RESOURCE)) {
			if (in == null) {
				throw new IOException("Bundled rustup-init.exe not found on the classpath at "
						+ BUNDLED_RUSTUP_RESOURCE);
			}
			Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
		}
		return dest;
	}

	// =============================================================================================
	// Step implementation: building librespot
	// =============================================================================================

	private static InstallResult runCargoInstall(Consumer<String> onProgress) {
		String cargo = findCargo();
		try {
			ProcessBuilder builder = new ProcessBuilder(cargo, "install", "--git", LIBRESPOT_GIT_URL, "librespot");
			builder.redirectErrorStream(true);
			Process process = builder.start();
			currentProcess = process;
			streamLines(process, onProgress);
			int exitCode = process.waitFor();

			if (cancelRequested) {
				return new InstallResult(false, null, "Install cancelled.");
			}
			if (exitCode != 0) {
				return new InstallResult(false, null, "cargo install exited with code " + exitCode
						+ " -- likely missing system build dependencies. On Debian/Ubuntu: "
						+ "sudo apt-get install build-essential libasound2-dev. On Fedora: "
						+ "sudo dnf install alsa-lib-devel make gcc. See libre spot's COMPILING.md.");
			}
		} catch (IOException | InterruptedException e) {
			if (e instanceof InterruptedException) Thread.currentThread().interrupt();
			if (cancelRequested) return new InstallResult(false, null, "Install cancelled.");
			return new InstallResult(false, null, "Install failed: " + e.getMessage());
		} finally {
			currentProcess = null;
		}
		return null; // success
	}

	private static String findCargo() {
		String exeName = isWindows() ? "cargo.exe" : "cargo";
		if (canRun(exeName)) return exeName;

		String cargoHome = System.getenv("CARGO_HOME");
		Path bin = cargoHome != null
				? Paths.get(cargoHome, "bin", exeName)
				: Paths.get(System.getProperty("user.home"), ".cargo", "bin", exeName);
		return Files.isExecutable(bin) ? bin.toString() : null;
	}

	private static boolean canRun(String command) {
		try {
			Process process = new ProcessBuilder(command, "--version").start();
			return process.waitFor() == 0;
		} catch (Exception e) {
			return false;
		}
	}

	private static String resolveInstalledBinary() {
		String exeName = isWindows() ? "librespot.exe" : "librespot";
		String cargoHome = System.getenv("CARGO_HOME");
		Path bin = cargoHome != null
				? Paths.get(cargoHome, "bin", exeName)
				: Paths.get(System.getProperty("user.home"), ".cargo", "bin", exeName);
		return Files.isExecutable(bin) ? bin.toString() : null;
	}

	// =============================================================================================
	// Disk space helpers
	// =============================================================================================

	private static long availableBytes() {
		try {
			FileStore store = Files.getFileStore(Paths.get(System.getProperty("user.home")));
			return store.getUsableSpace();
		} catch (IOException e) {
			return Long.MAX_VALUE; // can't tell -- don't block install over this
		}
	}

	private static String formatBytes(long bytes) {
		double gigabytes = bytes / (1024.0 * 1024 * 1024);
		if (gigabytes >= 1) return String.format(Locale.ROOT, "%.1f GB", gigabytes);
		return String.format(Locale.ROOT, "%d MB", bytes / (1024 * 1024));
	}

	// =============================================================================================
	// Cleanup of leftover build scratch folders
	// =============================================================================================

	/**
	 * Deletes orphaned cargo-installXXXXXX scratch dirs older than {@link #STALE_TEMP_AGE}.
	 */
	private static void cleanupStaleBuildScratch(Consumer<String> onProgress) {
		Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"));
		long reclaimed = 0;
		Instant cutoff = Instant.now().minus(STALE_TEMP_AGE);

		try (Stream<Path> entries = Files.list(tempDir)) {
			for (Path entry : (Iterable<Path>) entries::iterator) {
				String name = entry.getFileName().toString();
				if (!name.startsWith("cargo-install")) continue;

				try {
					if (Files.getLastModifiedTime(entry).toInstant().isAfter(cutoff)) {
						continue; // recently modified, might still be in use
					}
					long size = dirSize(entry);
					deleteRecursively(entry);
					reclaimed += size;
				} catch (IOException ignored) {
					// in use / permissions -- not worth failing the installation over
				}
			}
		} catch (IOException ignored) {
			// can't list temp dir
		}

		if (reclaimed > 0) {
			report(onProgress, "Cleaned up " + formatBytes(reclaimed)
					+ " left over from a previous interrupted install.");
		}
	}

	private static long dirSize(Path dir) throws IOException {
		try (Stream<Path> files = Files.walk(dir)) {
			return files.filter(Files::isRegularFile)
					.mapToLong(file -> {
						try {
							return Files.size(file);
						} catch (IOException e) {
							return 0L;
						}
					})
					.sum();
		}
	}

	private static void deleteRecursively(Path dir) throws IOException {
		Files.walkFileTree(dir, new SimpleFileVisitor<>() {
			@Override
			public @NonNull FileVisitResult visitFile(@NonNull Path file, @NonNull BasicFileAttributes attrs) throws IOException {
				Files.delete(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public @NonNull FileVisitResult postVisitDirectory(@NonNull Path directory, IOException exc) throws IOException {
				Files.delete(directory);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	// =============================================================================================
	// Small shared utilities
	// =============================================================================================

	private static void streamLines(Process process, Consumer<String> onProgress) throws IOException {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
			String line;
			while ((line = reader.readLine()) != null) {
				report(onProgress, line);
			}
		}
	}

	private static void report(Consumer<String> onProgress, String line) {
		if (onProgress != null) onProgress.accept(line);
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	}
}
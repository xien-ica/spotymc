package xien.jxsh.spotymc.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * On-disk config for SpotyMC: Spotify Client ID, OAuth refresh token, HUD
 * preferences, and librespot settings. Stored under
 * {@code .minecraft/config/spotymc/config.json}.
 * <p>
 * Access via {@link #get()}. The first call loads (or creates) the file; later
 * calls return the same instance. Mutations should be followed by {@link #save()}.
 */
public class ModConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("spotymc");
	private static final Path CONFIG_FILE = DIR.resolve("config.json");

	public String clientId = "";
	public String redirectUri = "http://127.0.0.1:8888/callback";
	public String refreshToken = "";

	public boolean lyricsEnabled = true;
	/** Whether the "Title — Artist" line is shown above the hotbar. */
	public boolean nowPlayingEnabled = true;

	/**
	 * Pixels between the hotbar and the title/artist line (creative/spectator only —
	 * survival pins the title to the top of the screen). Only used once
	 * {@link #titleArtistHudGapCustomized} is true; otherwise
	 * {@link #effectiveTitleArtistHudGap()} returns the computed default.
	 */
	public int titleArtistHudGap = 20;
	/** True once the player has dragged the Title/Artist Height slider. */
	public boolean titleArtistHudGapCustomized = false;

	/**
	 * Pixels between the hotbar and the lyric line. Same customisation rules as
	 * {@link #titleArtistHudGap}.
	 */
	public int lyricsHudGap = 30;
	/** True once the player has dragged the Lyrics Height slider. */
	public boolean lyricsHudGapCustomized = false;

	/** Name of a {@code LyricsColor} entry — the lyric line's text color. */
	public String lyricsColorName = "YELLOW";
	/** Scale factor for the lyric line (1.0 = vanilla size). Steps of 0.25. */
	public float lyricsFontScale = 1.0f;
	/** When true, wraps each lyric line as "♪ text ♪". Off by default. */
	public boolean lyricsNotesEnabled = false;

	/**
	 * When true, pauses Spotify when the game pauses and resumes when it unpauses,
	 * but only for pauses this mod itself initiated.
	 */
	public boolean pauseMusicWithGame = false;

	// --- In-game audio via librespot ---

	/** Whether the mod should launch/keep a librespot process for in-game audio. */
	public boolean librespotEnabled = false;
	/** Absolute path to the librespot binary (set by the installer or manually). */
	public String librespotPath = "";
	/** Spotify Connect device name librespot advertises. */
	public String librespotDeviceName = "Minecraft";
	/** Ogg Vorbis bitrate: 96, 160, or 320. */
	public int librespotBitrate = 160;
	/**
	 * Volume (0–100) librespot starts at before Spotify Connect syncs a real value.
	 * Without this it falls back to its internal default (~49 %).
	 */
	public int librespotInitialVolume = 80;
	/**
	 * Fixed attenuation (dB, ≤ 0) applied to librespot's in-game output on top of
	 * Spotify Connect volume. Default −8 dB leaves headroom for Minecraft SFX while
	 * still allowing Connect volume to stay high. Edit in config.json if desired.
	 */
	public float librespotOutputCapDb = -8.0f;

	private static volatile ModConfig instance;

	/**
	 * Sensible default for the lyrics line gap when the player hasn't customised it.
	 * Survival always pins title/artist to the top, so lyrics need less headroom there.
	 */
	public static int defaultLyricsHudGap(boolean survivalHud, boolean titleArtistShown) {
		if (survivalHud) return 14;
		return titleArtistShown ? 30 : 18;
	}

	/** Default title/artist gap in creative/spectator. */
	public static int defaultTitleArtistHudGap() {
		return 20;
	}

	/** Gap LyricsHud should actually use: player's value once set, otherwise the default. */
	public int effectiveLyricsHudGap(boolean survivalHud) {
		return lyricsHudGapCustomized ? lyricsHudGap : defaultLyricsHudGap(survivalHud, nowPlayingEnabled);
	}

	/** Same idea for the title/artist line. */
	public int effectiveTitleArtistHudGap() {
		return titleArtistHudGapCustomized ? titleArtistHudGap : defaultTitleArtistHudGap();
	}

	public static ModConfig get() {
		ModConfig local = instance;
		if (local == null) {
			synchronized (ModConfig.class) {
				local = instance;
				if (local == null) {
					instance = local = load();
				}
			}
		}
		return local;
	}

	private static ModConfig load() {
		try {
			if (Files.exists(CONFIG_FILE)) {
				String json = Files.readString(CONFIG_FILE, StandardCharsets.UTF_8);
				ModConfig cfg = GSON.fromJson(json, ModConfig.class);
				if (cfg != null) return cfg;
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		return new ModConfig();
	}

	public void save() {
		try {
			Files.createDirectories(DIR);
			Files.writeString(CONFIG_FILE, GSON.toJson(this), StandardCharsets.UTF_8);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}
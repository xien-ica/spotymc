package xien.jxsh.spotymc.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Handles the mod's on-disk config: your Spotify app Client ID (you must
 * register a free app at <a href="https://developer.spotify.com/dashboard">...</a>) plus the
 * persisted OAuth refresh token, stored under .minecraft/config/spotymc/.
 */
public class ModConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("spotymc");
	private static final Path CONFIG_FILE = DIR.resolve("config.json");

	public String clientId = "";
	public String redirectUri = "http://127.0.0.1:8888/callback";
	public String refreshToken = "";
	public boolean lyricsEnabled = true;
	/** Whether the "Title — Artist" line is shown above the hotbar. Lyrics (if on) still render independently. */
	public boolean nowPlayingEnabled = true;
	/**
	 * Pixels between the hotbar and the title/artist line (creative/spectator only -- survival
	 * pins the title to the top of the screen instead). Only meaningful once
	 * {@link #titleArtistHudGapCustomized} is true; until the player actually drags the slider,
	 * {@link #effectiveTitleArtistHudGap()} computes a sensible default instead of reading this
	 * raw value, so the position keeps making sense on its own without them having to touch it.
	 */
	public int titleArtistHudGap = 20;
	/** Whether {@link #titleArtistHudGap} is the player's own explicit choice (set the moment they
	 *  drag the slider in F12 -> Settings) rather than the computed default. */
	public boolean titleArtistHudGapCustomized = false;
	/**
	 * Pixels between the hotbar and the lyric line. Same deal as {@link #titleArtistHudGap}: only
	 * used once {@link #lyricsHudGapCustomized} is true, otherwise {@link #effectiveLyricsHudGap}
	 * computes the default.
	 */
	public int lyricsHudGap = 30;
	/** Whether {@link #lyricsHudGap} is the player's own explicit choice rather than the computed default. */
	public boolean lyricsHudGapCustomized = false;
	/** Name of a {@link xien.jxsh.spotymc.lyrics.LyricsColor} entry -- the lyric line's text color. */
	public String lyricsColorName = "YELLOW";
	/** Scale factor applied to the lyric line's font size (1.0 = vanilla size). Adjustable in steps of 0.25. */
	public float lyricsFontScale = 1.0f;
	/** When true, wraps each lyric line as "♪ text ♪". Off by default. */
	public boolean lyricsNotesEnabled = false;

	// --- In-game audio via librespot ---
	/** Whether the mod should launch/keep a librespot process running for in-game audio. */
	public boolean librespotEnabled = false;
	/** Absolute path to a librespot binary. Download one from <a href="https://github.com/librespot-org/librespot/releases">...</a>
	 *  (or `cargo install librespot`) -- this mod does not bundle or download it for you. */
	public String librespotPath = "";
	/** Spotify Connect device name librespot advertises. Select this in the real Spotify app once to route audio here. */
	public String librespotDeviceName = "Minecraft";
	/** Ogg Vorbis stream bitrate librespot requests from Spotify: 96, 160, or 320. */
	public int librespotBitrate = 160;
	/** Volume (0-100) librespot starts at before Spotify Connect syncs a "real" value from
	 *  whatever device last set it. Without this, librespot falls back to its own internal
	 *  default, which is what was showing up as an unexpected ~49% right after connecting. */
	public int librespotInitialVolume = 80;
	/** Fixed attenuation (in dB, 0 or negative) applied to libre spot's actual in-game output,
	 *  independent of and on top of Spotify Connect's own volume (which still scales 0-100% in
	 *  F12/the Spotify app as normal -- this just quietens wherever that lands). Expressed in dB
	 *  rather than a plain percentage because loudness is logarithmic: a straight 80% amplitude
	 *  multiplier is only about -2dB, which is nearly inaudible. -6dB is roughly "half as loud",
	 *  -12dB roughly "a quarter as loud". Default -8dB is a noticeable but not drastic dip, so
	 *  Connect volume can stay maxed in-game while still leaving room for Minecraft's own SFX.
	 *  Not exposed as its own slider; edit directly in config.json if you want a different cut. */
	public float librespotOutputCapDb = -8.0f;

	private static ModConfig instance;

	/**
	 * Sensible default for the lyrics line's gap when the player hasn't customized it, given the
	 * current gamemode and whether the title/artist line is even showing. Survival always pins
	 * title/artist to the top of the screen (see LyricsHud), so lyrics never need to leave room
	 * for it there; in creative, lyrics need noticeably more headroom above a visible title line
	 * than when that line is off entirely.
	 */
	public static int defaultLyricsHudGap(boolean survivalHud, boolean titleArtistShown) {
		if (survivalHud) return 14;
		return titleArtistShown ? 30 : 18;
	}

	/** Default title/artist gap in creative/spectator -- meaningless in survival, where it's pinned to the top instead. */
	public static int defaultTitleArtistHudGap() {
		return 20;
	}

	/** The gap LyricsHud should actually draw at: the player's own value once they've set one, otherwise the computed default. */
	public int effectiveLyricsHudGap(boolean survivalHud) {
		return lyricsHudGapCustomized ? lyricsHudGap : defaultLyricsHudGap(survivalHud, nowPlayingEnabled);
	}

	/** Same idea as {@link #effectiveLyricsHudGap}, for the title/artist line. */
	public int effectiveTitleArtistHudGap() {
		return titleArtistHudGapCustomized ? titleArtistHudGap : defaultTitleArtistHudGap();
	}

	public static ModConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
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
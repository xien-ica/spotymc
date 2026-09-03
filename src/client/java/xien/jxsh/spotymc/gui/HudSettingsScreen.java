package xien.jxsh.spotymc.gui;

import org.jspecify.annotations.NonNull;
import xien.jxsh.spotymc.PlaybackPoller;
import xien.jxsh.spotymc.Spotymc;
import xien.jxsh.spotymc.audio.LibrespotInstaller;
import xien.jxsh.spotymc.config.ModConfig;
import xien.jxsh.spotymc.gui.layout.HudSettingsLayout;
import xien.jxsh.spotymc.gui.layout.ScrollTextBlock;
import xien.jxsh.spotymc.gui.layout.ScrollablePanel;
import xien.jxsh.spotymc.gui.render.TextLayout;
import xien.jxsh.spotymc.gui.widget.FontScaleSlider;
import xien.jxsh.spotymc.gui.widget.GapSlider;
import xien.jxsh.spotymc.hud.LyricsColor;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * F12 → Settings: lyrics / title-artist on-off toggles plus independent sliders for how far
 * above the hotbar each line sits. Every change writes straight to {@link ModConfig} and saves
 * immediately — LyricsHud reads ModConfig.get() fresh on every frame, so the HUD updates live.
 * <p>
 * Also hosts a manual auth-recovery action ("Re-authenticate") and the librespot install /
 * uninstall flow (delegated to {@link LibrespotSettingsController}).
 * <p>
 * Layout is laid out once at nominal size in content-local coordinates. If the content is taller
 * than the viewport the panel scrolls. Scrolling, panel geometry, the custom sliders, and the
 * librespot section live in dedicated helpers so this screen only wires widgets and reacts to
 * the remaining local actions.
 */
public class HudSettingsScreen extends Screen {

	private final Screen parent;
	private final PlaybackPoller poller;

	private HudSettingsLayout layout;
	private final ScrollablePanel scroll = new ScrollablePanel();
	private LibrespotSettingsController librespot;

	private Button lyricsToggleButton;
	private Button titleArtistToggleButton;
	private Button lyricsNotesToggleButton;
	private Button lyricsColorButton;
	private Button pauseMusicWithGameButton;
	private Button reauthButton;

	/** Survival-mode note shown in place of the Title/Artist Height slider. */
	private ScrollTextBlock titlePinnedNote = ScrollTextBlock.EMPTY;

	/** Status line (updates live during install progress / re-auth without a full rebuild). */
	private int statusLocalY;
	private String statusMessage = "";

	private boolean reauthenticating = false;

	private final ExecutorService bgExecutor = Executors.newFixedThreadPool(1, r -> {
		Thread t = new Thread(r, "spotymc-settings-auth");
		t.setDaemon(true);
		return t;
	});

	public HudSettingsScreen(Screen parent, PlaybackPoller poller) {
		super(Component.literal("HUD Settings"));
		this.parent = parent;
		this.poller = poller;
	}

	@Override
	public void removed() {
		bgExecutor.shutdownNow();
		if (librespot != null) {
			LibrespotInstaller.removeListeners(librespot.progressListener(), librespot.completeListener());
		}
		super.removed();
	}

	@Override
	protected void init() {
		layout = HudSettingsLayout.compute(width, height);
		scroll.clear();
		scroll.setViewport(layout.viewportTop, layout.viewportBottom);

		if (librespot == null) {
			librespot = new LibrespotSettingsController(
					() -> init(width, height),
					this::setStatusMessage,
					this::addScrollable,
					poller,
					bgExecutor,
					this.font);
		}

		if (librespot.isShowingConfirm()) {
			initInstallConfirm();
			return;
		}

		librespot.clearConfirmText();
		initNormalSettings();
	}

	// =========================================================================================
	// Normal settings layout
	// =========================================================================================

	private void initNormalSettings() {
		ModConfig cfg = ModConfig.get();
		int cx = layout.centerX;
		int curY = HudSettingsLayout.CONTENT_TOP_PADDING;

		// Row 1: Lyrics + Title/Artist toggles
		boolean lyricsOn = cfg.lyricsEnabled;
		lyricsToggleButton = Button.builder(
						Component.literal("Lyrics: " + (lyricsOn ? "ON" : "OFF")), _ -> toggleLyrics())
				.bounds(cx - 130, 0, 120, 20).build();
		addScrollable(lyricsToggleButton, curY);

		boolean titleArtistOn = cfg.nowPlayingEnabled;
		titleArtistToggleButton = Button.builder(
						Component.literal("Title/Artist: " + (titleArtistOn ? "ON" : "OFF")), _ -> toggleTitleArtist())
				.bounds(cx + 10, 0, 120, 20).build();
		addScrollable(titleArtistToggleButton, curY);
		curY += 26;

		// Row 2: Notes + Color
		boolean notesOn = cfg.lyricsNotesEnabled;
		lyricsNotesToggleButton = Button.builder(
						Component.literal("Notes ♪: " + (notesOn ? "ON" : "OFF")), _ -> toggleLyricsNotes())
				.bounds(cx - 130, 0, 120, 20).build();
		addScrollable(lyricsNotesToggleButton, curY);

		lyricsColorButton = Button.builder(
						Component.literal("Color: " + LyricsColor.byName(cfg.lyricsColorName).label), _ -> cycleLyricsColor())
				.bounds(cx + 10, 0, 120, 20).build();
		addScrollable(lyricsColorButton, curY);
		curY += 30;

		// Lyrics Height slider
		curY = addGapSlider(cx - 130, curY, "Lyrics Height", cfg.effectiveLyricsHudGap(isSurvivalHud()), gap -> {
			ModConfig c = ModConfig.get();
			c.lyricsHudGap = gap;
			c.lyricsHudGapCustomized = true;
			c.save();
		});

		// Title/Artist Height — real slider in creative/spectator, explanatory note in survival
		if (isSurvivalHud()) {
			List<String> lines = wrapText("Title/Artist is pinned to the top of the screen in Survival",
					HudSettingsLayout.PANEL_WIDTH - 40, 2);
			titlePinnedNote = new ScrollTextBlock(curY + 4, lines, 0xFF888888, false);
			curY += 8 + lines.size() * HudSettingsLayout.STATUS_LINE_HEIGHT + 14;
		} else {
			titlePinnedNote = ScrollTextBlock.EMPTY;
			curY = addGapSlider(cx - 130, curY, "Title/Artist Height", cfg.effectiveTitleArtistHudGap(), gap -> {
				ModConfig c = ModConfig.get();
				c.titleArtistHudGap = gap;
				c.titleArtistHudGapCustomized = true;
				c.save();
			});
		}

		// Font scale
		curY = addFontScaleSlider(cx - 130, curY, cfg.lyricsFontScale, scaleVal -> {
			ModConfig c = ModConfig.get();
			c.lyricsFontScale = (float) scaleVal;
			c.save();
		});

		// Pause music when the game itself is paused (singleplayer Esc menu, etc.)
		boolean pauseWithGame = cfg.pauseMusicWithGame;
		pauseMusicWithGameButton = Button.builder(
						Component.literal("Pause music when game paused: " + (pauseWithGame ? "ON" : "OFF")),
						_ -> togglePauseMusicWithGame())
				.bounds(cx - 130, 0, 260, 20).build();
		addScrollable(pauseMusicWithGameButton, curY);
		curY += 28;

		// Librespot install / uninstall
		curY = librespot.buildSection(cx, curY, cfg);

		// Re-authenticate
		reauthButton = Button.builder(
						Component.literal(reauthenticating ? "Opening browser..." : "Re-authenticate"), _ -> reauthenticate())
				.bounds(cx - 60, 0, 120, 20).build();
		addScrollable(reauthButton, curY);
		curY += 26;

		finishLayout(cx, curY, () -> minecraft.gui.setScreen(parent));
	}

	// =========================================================================================
	// Install-consent confirmation view
	// =========================================================================================

	private void initInstallConfirm() {
		titlePinnedNote = ScrollTextBlock.EMPTY;
		statusMessage = "";
		int cx = layout.centerX;
		int curY = HudSettingsLayout.CONTENT_TOP_PADDING;
		curY = librespot.buildConfirmView(cx, curY, ModConfig.get());
		finishLayout(cx, curY, librespot::cancelConfirm);
	}

	/**
	 * Shared tail of both layout paths: reserve status space, apply scroll, add Back button.
	 */
	private void finishLayout(int cx, int curY, Runnable backAction) {
		statusLocalY = curY + 10;
		// Reserve room for the full STATUS_MAX_LINES even when nothing is showing, so scroll
		// range doesn't jump when a status message appears or clears.
		scroll.setContentHeight(statusLocalY
				+ HudSettingsLayout.STATUS_MAX_LINES * HudSettingsLayout.STATUS_LINE_HEIGHT
				+ HudSettingsLayout.CONTENT_BOTTOM_PADDING);
		scroll.setScrollbarGeometry(layout.panelRight(width));
		scroll.applyScroll();

		addRenderableWidget(Button.builder(Component.literal("← Back"), _ -> backAction.run())
				.bounds(cx - 40, layout.backButtonY(), 80, 20).build());
	}

	// =========================================================================================
	// Widget helpers
	// =========================================================================================

	private void addScrollable(AbstractWidget widget, int localY) {
		addRenderableWidget(widget);
		scroll.add(widget, localY);
	}

	private int addGapSlider(int x, int localY, String label, int currentGap, java.util.function.IntConsumer onChange) {
		GapSlider slider = new GapSlider(x, 0, 260, 20, label, currentGap, onChange);
		addScrollable(slider, localY);
		return localY + 28;
	}

	private int addFontScaleSlider(int x, int localY, double currentScale, java.util.function.DoubleConsumer onChange) {
		FontScaleSlider slider = new FontScaleSlider(x, 0, 260, 20, currentScale, onChange);
		addScrollable(slider, localY);
		return localY + 28;
	}

	// =========================================================================================
	// Local actions (toggles + re-auth)
	// =========================================================================================

	private void toggleLyrics() {
		ModConfig cfg = ModConfig.get();
		cfg.lyricsEnabled = !cfg.lyricsEnabled;
		cfg.save();
		if (lyricsToggleButton != null) {
			lyricsToggleButton.setMessage(Component.literal("Lyrics: " + (cfg.lyricsEnabled ? "ON" : "OFF")));
		}
	}

	private void toggleTitleArtist() {
		ModConfig cfg = ModConfig.get();
		cfg.nowPlayingEnabled = !cfg.nowPlayingEnabled;
		cfg.save();
		if (titleArtistToggleButton != null) {
			titleArtistToggleButton.setMessage(Component.literal("Title/Artist: " + (cfg.nowPlayingEnabled ? "ON" : "OFF")));
		}
	}

	private void toggleLyricsNotes() {
		ModConfig cfg = ModConfig.get();
		cfg.lyricsNotesEnabled = !cfg.lyricsNotesEnabled;
		cfg.save();
		if (lyricsNotesToggleButton != null) {
			lyricsNotesToggleButton.setMessage(Component.literal("Notes ♪: " + (cfg.lyricsNotesEnabled ? "ON" : "OFF")));
		}
	}

	private void cycleLyricsColor() {
		ModConfig cfg = ModConfig.get();
		LyricsColor nextColor = LyricsColor.byName(cfg.lyricsColorName).next();
		cfg.lyricsColorName = nextColor.name();
		cfg.save();
		if (lyricsColorButton != null) {
			lyricsColorButton.setMessage(Component.literal("Color: " + nextColor.label));
		}
	}

	private void togglePauseMusicWithGame() {
		ModConfig cfg = ModConfig.get();
		cfg.pauseMusicWithGame = !cfg.pauseMusicWithGame;
		cfg.save();
		if (pauseMusicWithGameButton != null) {
			pauseMusicWithGameButton.setMessage(Component.literal(
					"Pause music when game paused: " + (cfg.pauseMusicWithGame ? "ON" : "OFF")));
		}
	}

	private void setStatusMessage(String message) {
		minecraft.execute(() -> {
			statusMessage = message;
			scroll.applyScroll();
		});
	}

	private void reauthenticate() {
		if (reauthenticating) return;
		reauthenticating = true;
		statusMessage = "";
		if (reauthButton != null) reauthButton.setMessage(Component.literal("Opening browser..."));
		poller.auth.login()
				.thenRun(() -> setStatusMessage("Re-connected to Spotify!"))
				.exceptionally(ex -> {
					setStatusMessage("Re-auth failed: " + ex.getMessage());
					return null;
				})
				.whenComplete((_, _) -> minecraft.execute(() -> {
					reauthenticating = false;
					if (reauthButton != null) reauthButton.setMessage(Component.literal("Re-authenticate"));
					scroll.applyScroll();
				}));
	}

	// =========================================================================================
	// Input
	// =========================================================================================

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (scroll.mouseScrolled(scrollY)) return true;
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (scroll.mouseClicked(event)) return true;
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(@NonNull MouseButtonEvent event, double dragX, double dragY) {
		if (scroll.mouseDragged(event)) return true;
		return super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(@NonNull MouseButtonEvent event) {
		if (scroll.mouseReleased()) return true;
		return super.mouseReleased(event);
	}

	@Override
	public boolean keyPressed(@NonNull KeyEvent event) {
		if (super.keyPressed(event)) return true;
		if (event.key() == Spotymc.TOGGLE_KEYCODE) {
			minecraft.gui.setScreen(null);
			return true;
		}
		return false;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return true;
	}

	// =========================================================================================
	// Rendering
	// =========================================================================================

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		int x = layout.panelLeft(width);
		int y = layout.panelTop;
		graphics.fill(x, y, x + HudSettingsLayout.PANEL_WIDTH, y + layout.panelHeight, HudSettingsLayout.PANEL_ALPHA);

		super.extractRenderState(graphics, mouseX, mouseY, delta);

		String titleText = this.title.getString();
		int textWidth = this.font.width(titleText);
		graphics.text(this.font, titleText, width / 2 - textWidth / 2, y + 8, 0xFFFFFFFF, true);

		int cx = width / 2;
		titlePinnedNote.render(graphics, this.font, scroll, cx);
		if (librespot != null) {
			librespot.installNote().render(graphics, this.font, scroll, cx);
			librespot.confirmHeading().render(graphics, this.font, scroll, cx);
			librespot.confirmBody().render(graphics, this.font, scroll, cx);
		}

		if (!statusMessage.isEmpty()) {
			int color;
			if (statusMessage.startsWith("Re-auth failed") || statusMessage.startsWith("Install failed")) {
				color = 0xFFFF5555;
			} else if (statusMessage.contains("installed") || statusMessage.contains("Re-connected")) {
				color = 0xFF55FF55;
			} else {
				color = 0xFFAAAAAA;
			}
			List<String> lines = wrapText(statusMessage, HudSettingsLayout.PANEL_WIDTH - 20,
					HudSettingsLayout.STATUS_MAX_LINES);
			new ScrollTextBlock(statusLocalY, lines, color, true).render(graphics, this.font, scroll, cx);
		}

		scroll.renderScrollbar(graphics);
	}

	// =========================================================================================
	// Small utilities
	// =========================================================================================

	/** Mirrors LyricsHud's own game-mode check so this screen's layout matches what is drawn. */
	private boolean isSurvivalHud() {
		if (minecraft.gameMode == null) return false;
		GameType mode = minecraft.gameMode.getPlayerMode();
		return mode == GameType.SURVIVAL || mode == GameType.ADVENTURE;
	}

	/**
	 * Word-wraps text to a pixel width, limited to {@code maxLines}. If the text still doesn't
	 * fit, the last line is truncated with an ellipsis.
	 */
	private List<String> wrapText(String s, int maxWidthPx, int maxLines) {
		List<String> full = TextLayout.wrapText(this.font, s, maxWidthPx);
		if (full.size() <= maxLines) return full;
		java.util.ArrayList<String> truncated = new java.util.ArrayList<>(full.subList(0, maxLines));
		int last = maxLines - 1;
		truncated.set(last, TextLayout.fitText(this.font, truncated.get(last), maxWidthPx));
		return truncated;
	}
}

package xien.jxsh.spotymc.gui;

import org.jspecify.annotations.NonNull;
import xien.jxsh.spotymc.PlaybackPoller;
import xien.jxsh.spotymc.Spotymc;
import xien.jxsh.spotymc.audio.LibrespotInstaller;
import xien.jxsh.spotymc.config.ModConfig;
import xien.jxsh.spotymc.gui.render.TextLayout;
import xien.jxsh.spotymc.hud.LyricsColor;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;

/**
 * F12 -> Settings: lyrics/title-artist on-off toggles (moved here from the main controls screen)
 * plus independent sliders for how far above the hotbar each line sits -- lyrics and title/artist
 * no longer share one position. Every change writes straight to ModConfig and saves immediately --
 * LyricsHud reads ModConfig.get() fresh on every frame, so the HUD updates live as you drag either
 * slider. No mod or game reload required.
 * <p>
 * Also hosts a manual auth-recovery action for when something's gone stale: "Re-authenticate"
 * (full browser relogin -- fixes a revoked/invalid refresh token). A "Force Token Refresh" used
 * to sit here too, but SpotifyAuth#getValidAccessToken() already refreshes silently whenever the
 * cached token is near expiry, so a manual version of that was redundant.
 * <p>
 * Layout is laid out once at nominal (full) size in "content-local" coordinates (0 = top of the
 * scrollable area) rather than shrunk to fit small windows. If the content is taller than the
 * available viewport, the panel scrolls -- mouse wheel or dragging the thumb -- instead of
 * cramming every row together or letting widgets overlap. The title and the Back button are
 * fixed chrome outside the scrollable area.
 */
public class HudSettingsScreen extends Screen {
	private static final int PANEL_ALPHA = 0xB0000000;
	private static final int PANEL_WIDTH = 320;
	private static final int NOMINAL_PANEL_HEIGHT = 350; // used whenever the screen has room for it
	private static final int MIN_PANEL_HEIGHT = 220; // hard floor for very small windows / high GUI Scale
	private static final int STATUS_MAX_LINES = 3;
	private static final int STATUS_LINE_HEIGHT = 10;
	private static final int MIN_GAP = 0;
	private static final int MAX_GAP = 150;
	private static final double MIN_FONT_SCALE = 0.75;
	private static final double MAX_FONT_SCALE = 2.0;
	private static final double FONT_SCALE_STEP = 0.25; // snap points: .75, 1.0, 1.25, 1.5, 1.75, 2.0
	// Built once per init() from LibrespotInstaller's estimate rather than hardcoded, so it stays
	// in sync if the estimate constants there ever change.
	private static String installNoteText() { return "A small, verified file (" + LibrespotInstaller.estimateLabel() + ") will be downloaded "
			+ "to enable Spotify playback in-game. It usually takes just 10-30 seconds, and no " + "additional setup is required.";
	}
	private static final int WIDGET_HEIGHT = 20;
	private static final int CONTENT_TOP_PADDING = 8;
	private static final int CONTENT_BOTTOM_PADDING = 8;
	private static final int HEADER_HEIGHT = 34; // room for the title above the scroll viewport
	private static final int FOOTER_HEIGHT = 38; // room for the Back button below the scroll viewport
	private static final int SCROLLBAR_WIDTH = 4;
	private static final int SCROLLBAR_MARGIN = 6;
	private static final int SCROLL_STEP_PX = 18; // per notch of mouse wheel

	private final Screen parent;
	private final PlaybackPoller poller;
	private Button lyricsToggleButton;
	private Button titleArtistToggleButton;
	private Button lyricsNotesToggleButton;
	private Button lyricsColorButton;
	private Button reauthButton;
	private Button cancelInstallButton;
	private Button uninstallLibrespotButton;
	// --- Install-consent confirmation view -------------------------------------------------
	// Clicking "Install librespot" doesn't download anything directly -- it swaps this screen's
	// content over to a short explainer (what's downloaded, that it's checksum-verified against
	// SpotyMC's own public build, and that no Spotify credentials touch it) with explicit
	// Install/Cancel buttons, so the actual download only ever starts after the user's read that
	// and clicked through it on purpose.
	private boolean showInstallConfirm = false;
	private Button confirmInstallButton;
	private Button confirmCancelButton;
	private List<String> confirmHeadingLines = Collections.emptyList();
	private int confirmHeadingLocalY = -1;
	private int confirmHeadingScreenY = -1;
	private List<String> confirmBodyLines = Collections.emptyList();
	private int confirmBodyLocalY = -1;
	private int confirmBodyScreenY = -1;
	private String statusMessage = "";
	private boolean reauthenticating = false;
	private boolean uninstalling = false;
	// Bound instances of this screen's callbacks, kept as fields so removed() can unregister the
	// exact same references it registered -- LibrespotInstaller's listener lists compare by
	// reference, and a fresh method reference each time wouldn't match for removal.
	private final Consumer<String> installProgressListener = this::setStatusMessage;
	private final Consumer<LibrespotInstaller.InstallResult> installCompleteListener = this::onInstallComplete;

	// --- Scrolling state ---------------------------------------------------------------------
	// Every scrollable widget is registered here with its "local" Y (measured from the top of the
	// scroll content, before the scroll offset is applied). applyScroll() re-derives each widget's
	// real on-screen Y from this every time the offset changes, and hides widgets that would only
	// be partially visible so nothing ever overlaps the header or footer chrome.
	private final List<ScrollEntry> scrollWidgets = new ArrayList<>();
	private int contentHeight;
	private int scrollOffset = 0;
	private int viewportTop;
	private int viewportBottom;
	private int viewportHeight;
	private int installNoteLocalY = -1;
	private List<String> installNoteLines = Collections.emptyList();
	private int statusLocalY;
	// Same idea as installNoteLocalY/installNoteLines/installNoteScreenY below, but for the note
	// shown in place of the Title/Artist Height slider while in survival/adventure -- that slider
	// does nothing there since LyricsHud pins the title to the top of the screen in that mode, so
	// dragging it would just be a dead control.
	private int titlePinnedNoteLocalY = -1;
	private List<String> titlePinnedNoteLines = Collections.emptyList();
	private int titlePinnedNoteScreenY = -1;
	// Absolute (screen-space) Y for the currently-visible install note / status lines, recomputed
	// by applyScroll(); -1 means "not visible right now" (scrolled off, or not shown at all).
	private int installNoteScreenY = -1;
	private int statusScreenY = -1;
	// Scrollbar thumb geometry, recomputed by applyScroll(); used for both drawing and hit-testing.
	private int scrollbarX, scrollbarTrackTop, scrollbarTrackHeight, scrollbarThumbY, scrollbarThumbHeight;
	private boolean scrollbarDragging = false;

	// Actual on-screen panel geometry for this frame -- shrinks below NOMINAL_PANEL_HEIGHT when
	// the window/GUI Scale doesn't leave enough vertical room. extractRenderState() reads these
	// instead of recomputing its own copy, so the background box always matches the real layout.
	private int panelTop;
	private int panelHeight = NOMINAL_PANEL_HEIGHT;

	// Background executor for actions that can't run on the render thread but also don't need
	// LibrespotInstaller's cross-screen tracking: the auth actions (network) and uninstall (disk).
	// Small and short-lived; shut down when the screen closes.
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
		// The installation itself (if running) is owned by LibrespotInstaller, not this screen, and
		// keeps going in the background -- only detach this screen's own callbacks so they stop
		// firing into a widget list that no longer exists.
		LibrespotInstaller.removeListeners(installProgressListener, installCompleteListener);
		super.removed();
	}

	@Override
	protected void init() {
		int centerX = width / 2;
		scrollWidgets.clear();

		int available = Math.max(MIN_PANEL_HEIGHT, height - 8);
		panelHeight = Math.min(NOMINAL_PANEL_HEIGHT, available);
		panelTop = Math.max(4, (height - panelHeight) / 2);
		int top = panelTop;
		viewportTop = top + HEADER_HEIGHT;
		viewportBottom = top + panelHeight - FOOTER_HEIGHT;
		viewportHeight = Math.max(WIDGET_HEIGHT, viewportBottom - viewportTop);

		ModConfig cfg = ModConfig.get();

		if (showInstallConfirm) {
			initInstallConfirm(centerX, top, cfg);
			return;
		}

		confirmHeadingLines = Collections.emptyList();
		confirmHeadingLocalY = -1;
		confirmBodyLines = Collections.emptyList();
		confirmBodyLocalY = -1;

		int curY = CONTENT_TOP_PADDING; // local coords: 0 = top of the scrollable content
		boolean lyricsOn = cfg.lyricsEnabled;
		lyricsToggleButton = Button.builder(
						Component.literal("Lyrics: " + (lyricsOn ? "ON" : "OFF")), _ -> toggleLyrics())
				.bounds(centerX - 130, 0, 120, 20).build();
		addScrollable(lyricsToggleButton, curY);

		boolean titleArtistOn = cfg.nowPlayingEnabled;
		titleArtistToggleButton = Button.builder(
						Component.literal("Title/Artist: " + (titleArtistOn ? "ON" : "OFF")), _ -> toggleTitleArtist())
				.bounds(centerX + 10, 0, 120, 20).build();
		addScrollable(titleArtistToggleButton, curY);
		curY += 26;

		boolean notesOn = cfg.lyricsNotesEnabled;
		lyricsNotesToggleButton = Button.builder(
						Component.literal("Notes ♪: " + (notesOn ? "ON" : "OFF")), _ -> toggleLyricsNotes())
				.bounds(centerX - 130, 0, 120, 20).build();
		addScrollable(lyricsNotesToggleButton, curY);

		lyricsColorButton = Button.builder(
						Component.literal("Color: " + LyricsColor.byName(cfg.lyricsColorName).label), _ -> cycleLyricsColor())
				.bounds(centerX + 10, 0, 120, 20).build();
		addScrollable(lyricsColorButton, curY);
		curY += 30;

		curY = addGapSlider(centerX - 130, curY, "Lyrics Height", cfg.effectiveLyricsHudGap(isSurvivalHud()), gap -> {
			ModConfig c = ModConfig.get();
			c.lyricsHudGap = gap;
			c.lyricsHudGapCustomized = true;
			c.save();
		});

		// The Title/Artist Height slider only does anything in creative/spectator -- LyricsHud pins
		// the title to the top of the screen in survival/adventure instead, so the slider would just
		// sit there doing nothing (and inviting a "why doesn't this work?" bug report). Swap it for a
		// short note there instead of leaving a dead control.
		if (isSurvivalHud()) {
			titlePinnedNoteLines = wrapText("Title/Artist is pinned to the top of the screen in Survival", PANEL_WIDTH - 40, 2);
			titlePinnedNoteLocalY = curY + 4;
			curY += 8 + titlePinnedNoteLines.size() * STATUS_LINE_HEIGHT + 14;
		} else {
			titlePinnedNoteLines = Collections.emptyList();
			titlePinnedNoteLocalY = -1;
			curY = addGapSlider(centerX - 130, curY, "Title/Artist Height", cfg.effectiveTitleArtistHudGap(), gap -> {
				ModConfig c = ModConfig.get();
				c.titleArtistHudGap = gap;
				c.titleArtistHudGapCustomized = true;
				c.save();
			});
		}

		curY = addFontScaleSlider(centerX - 130, curY, cfg.lyricsFontScale, scaleVal -> {
			ModConfig c = ModConfig.get();
			c.lyricsFontScale = (float) scaleVal;
			c.save();
		});

		// --- In-game audio setup: install librespot by downloading a precompiled, checksum-verified
		// binary. Whether a download is running lives on LibrespotInstaller, not this screen (see its
		// class doc) -- that's what lets a second open of this screen mid-download show
		// "Installing..." + Cancel instead of offering a second button that would kick off a parallel
		// download.
		boolean installRunning = LibrespotInstaller.isInstalling();
		boolean installed = LibrespotInstaller.isInstalled(cfg.librespotPath);
		Button installLibrespotButton;
		if (installRunning) {
			installLibrespotButton = Button.builder(Component.literal("Installing librespot..."), _ -> {})
					.bounds(centerX - 130, 0, 190, 20).build();
			installLibrespotButton.active = false;
			addScrollable(installLibrespotButton, curY);
			cancelInstallButton = Button.builder(Component.literal("Cancel"), _ -> cancelInstall())
					.bounds(centerX + 65, 0, 65, 20).build();
			addScrollable(cancelInstallButton, curY);
			uninstallLibrespotButton = null;
			installNoteLines = wrapText(installNoteText(), PANEL_WIDTH - 40, 3);
			installNoteLocalY = curY + 22;
			// Base gap already covers one line; add room for every extra wrapped line so the
			// row below (Re-authenticate) never overlaps the note.
			curY += 38 + (installNoteLines.size() - 1) * STATUS_LINE_HEIGHT;
		} else if (!installed) {
			installLibrespotButton = Button.builder(
							Component.literal("Install librespot"), _ -> openInstallConfirm())
					.bounds(centerX - 130, 0, 260, 20).build();
			addScrollable(installLibrespotButton, curY);
			cancelInstallButton = null;
			uninstallLibrespotButton = null;
			installNoteLines = wrapText(installNoteText(), PANEL_WIDTH - 40, 3);
			installNoteLocalY = curY + 22;
			curY += 38 + (installNoteLines.size() - 1) * STATUS_LINE_HEIGHT;
		} else {
			cancelInstallButton = null;
			installNoteLines = Collections.emptyList();
			installNoteLocalY = -1;
			// Uninstalling is just deleting the downloaded binary now -- no cargo/Rust toolchain
			// needs to be present to do it, unlike the old `cargo uninstall` flow.
			uninstallLibrespotButton = Button.builder(
							Component.literal(uninstalling ? "Uninstalling..." : "Uninstall librespot"),
							_ -> uninstallLibrespot())
					.bounds(centerX - 65, 0, 130, 20).build();
			uninstallLibrespotButton.active = !uninstalling;
			addScrollable(uninstallLibrespotButton, curY);
			curY += 26;
		}

		// --- Auth recovery: full browser relogin, for when the refresh token itself is bad/revoked. ---
		reauthButton = Button.builder(
						Component.literal(reauthenticating ? "Opening browser..." : "Re-authenticate"), _ -> reauthenticate())
				.bounds(centerX - 60, 0, 120, 20).build();
		addScrollable(reauthButton, curY);
		curY += 26;

		statusLocalY = curY + 10;
		// Reserve room for the full STATUS_MAX_LINES even when nothing's showing right now, so
		// scroll range doesn't jump around every time a status message appears or clears.
		contentHeight = statusLocalY + STATUS_MAX_LINES * STATUS_LINE_HEIGHT + CONTENT_BOTTOM_PADDING;

		int maxScroll = Math.max(0, contentHeight - viewportHeight);
		scrollOffset = Math.clamp(scrollOffset, 0, maxScroll);
		applyScroll();

		addRenderableWidget(Button.builder(Component.literal("← Back"), _ -> minecraft.gui.setScreen(parent))
				.bounds(centerX - 40, top + panelHeight - 30, 80, 20).build());
	}

	/**
	 * Lays out the install-consent view in place of the normal settings content: a short heading,
	 * an explainer of what's about to happen, and explicit Install/Cancel buttons. Reuses this
	 * screen's existing scroll/panel machinery (addScrollable, applyScroll, the same panel
	 * background) rather than a separate popup class, so it behaves consistently on small windows
	 * / high GUI Scale exactly like every other view here.
	 */
	private void initInstallConfirm(int centerX, int top, ModConfig cfg) {
		// Clear layout state that's only meaningful in the normal settings view, so a stale value
		// from before this view was entered can't make applyScroll() draw leftover content.
		installNoteLines = Collections.emptyList();
		installNoteLocalY = -1;
		titlePinnedNoteLines = Collections.emptyList();
		titlePinnedNoteLocalY = -1;
		cancelInstallButton = null;
		uninstallLibrespotButton = null;
		statusMessage = "";

		int curY = CONTENT_TOP_PADDING;

		confirmHeadingLines = wrapText("Install librespot?", PANEL_WIDTH - 40, 1);
		confirmHeadingLocalY = curY;
		curY += confirmHeadingLines.size() * STATUS_LINE_HEIGHT + 10;

		String body = "This downloads a small (~20 MB) file to let Minecraft play Spotify. " +
				"The file is checked to make sure it's safe. " + "After that, open Spotify and choose \"" +
				cfg.librespotDeviceName + "\" as your playback device.";
		confirmBodyLines = wrapText(body, PANEL_WIDTH - 40, 8);
		confirmBodyLocalY = curY;
		curY += confirmBodyLines.size() * STATUS_LINE_HEIGHT + 16;

		confirmInstallButton = Button.builder(Component.literal("Install librespot"), _ -> confirmInstall())
				.bounds(centerX - 130, 0, 120, 20).build();
		addScrollable(confirmInstallButton, curY);
		confirmCancelButton = Button.builder(Component.literal("Cancel"), _ -> cancelInstallConfirm())
				.bounds(centerX + 10, 0, 120, 20).build();
		addScrollable(confirmCancelButton, curY);
		curY += 26;

		statusLocalY = curY + 10;
		contentHeight = statusLocalY + STATUS_MAX_LINES * STATUS_LINE_HEIGHT + CONTENT_BOTTOM_PADDING;

		int maxScroll = Math.max(0, contentHeight - viewportHeight);
		scrollOffset = Math.clamp(scrollOffset, 0, maxScroll);
		applyScroll();

		addRenderableWidget(Button.builder(Component.literal("← Back"), _ -> cancelInstallConfirm())
				.bounds(centerX - 40, top + panelHeight - 30, 80, 20).build());
	}

	/** User clicked through the consent screen -- actually start the download now. */
	private void confirmInstall() {
		showInstallConfirm = false;
		installLibrespot();
	}

	/** User backed out of the consent screen without installing anything. */
	private void cancelInstallConfirm() {
		showInstallConfirm = false;
		init(width, height);
	}


	private void addScrollable(AbstractWidget widget, int localY) {
		addRenderableWidget(widget);
		scrollWidgets.add(new ScrollEntry(widget, localY));
	}

	private int maxScroll() {
		return Math.max(0, contentHeight - viewportHeight);
	}

	/**
	 * Re-derives every scrollable widget's real screen Y from its stored local Y and the current
	 * scroll offset, hiding (and disabling clicks on) anything that wouldn't fit fully inside the
	 * viewport. Also recomputes the install-note / status-message screen Y and the scrollbar
	 * thumb geometry. Called after init() and every time the user scrolls.
	 */
	private void applyScroll() {
		for (ScrollEntry entry : scrollWidgets) {
			int screenY = viewportTop + entry.localY - scrollOffset;
			boolean visible = screenY >= viewportTop && screenY + WIDGET_HEIGHT <= viewportBottom;
			entry.widget.setY(screenY);
			entry.widget.visible = visible;
		}

		if (installNoteLocalY >= 0) {
			int screenY = viewportTop + installNoteLocalY - scrollOffset;
			int blockHeight = installNoteLines.size() * STATUS_LINE_HEIGHT;
			installNoteScreenY = (screenY >= viewportTop && screenY + blockHeight <= viewportBottom) ? screenY : -1;
		} else {
			installNoteScreenY = -1;
		}

		if (titlePinnedNoteLocalY >= 0) {
			int screenY = viewportTop + titlePinnedNoteLocalY - scrollOffset;
			int blockHeight = titlePinnedNoteLines.size() * STATUS_LINE_HEIGHT;
			titlePinnedNoteScreenY = (screenY >= viewportTop && screenY + blockHeight <= viewportBottom) ? screenY : -1;
		} else {
			titlePinnedNoteScreenY = -1;
		}

		if (confirmHeadingLocalY >= 0) {
			int screenY = viewportTop + confirmHeadingLocalY - scrollOffset;
			int blockHeight = confirmHeadingLines.size() * STATUS_LINE_HEIGHT;
			confirmHeadingScreenY = (screenY >= viewportTop && screenY + blockHeight <= viewportBottom) ? screenY : -1;
		} else {
			confirmHeadingScreenY = -1;
		}

		if (confirmBodyLocalY >= 0) {
			int screenY = viewportTop + confirmBodyLocalY - scrollOffset;
			int blockHeight = confirmBodyLines.size() * STATUS_LINE_HEIGHT;
			confirmBodyScreenY = (screenY >= viewportTop && screenY + blockHeight <= viewportBottom) ? screenY : -1;
		} else {
			confirmBodyScreenY = -1;
		}

		if (!statusMessage.isEmpty()) {
			int screenY = viewportTop + statusLocalY - scrollOffset;
			int blockHeight = STATUS_MAX_LINES * STATUS_LINE_HEIGHT;
			statusScreenY = (screenY >= viewportTop && screenY + blockHeight <= viewportBottom) ? screenY : -1;
		} else {
			statusScreenY = -1;
		}

		int maxScroll = maxScroll();
		int panelRight = width / 2 + PANEL_WIDTH / 2;
		scrollbarX = panelRight - SCROLLBAR_MARGIN - SCROLLBAR_WIDTH;
		scrollbarTrackTop = viewportTop;
		scrollbarTrackHeight = viewportHeight;
		if (maxScroll > 0) {
			double visibleFraction = viewportHeight / (double) contentHeight;
			scrollbarThumbHeight = Math.max(12, (int) Math.round(viewportHeight * visibleFraction));
			int travel = Math.max(1, scrollbarTrackHeight - scrollbarThumbHeight);
			scrollbarThumbY = scrollbarTrackTop + (int) Math.round(travel * (scrollOffset / (double) maxScroll));
		} else {
			scrollbarThumbHeight = scrollbarTrackHeight;
			scrollbarThumbY = scrollbarTrackTop;
		}
	}

	private void scrollBy(int deltaPx) {
		int maxScroll = maxScroll();
		scrollOffset = Math.clamp(scrollOffset + deltaPx, 0, maxScroll);
		applyScroll();
	}

	private boolean isOverThumb(double mouseX, double mouseY) {
		return maxScroll() > 0
				&& mouseX >= scrollbarX && mouseX <= scrollbarX + SCROLLBAR_WIDTH
				&& mouseY >= scrollbarThumbY && mouseY <= scrollbarThumbY + scrollbarThumbHeight;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (maxScroll() > 0) {
			scrollBy((int) Math.round(-scrollY * SCROLL_STEP_PX));
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	// As of 26.x, ContainerEventHandler bundles the mouse position and button into a
	// MouseButtonEvent instead of passing (double, double, int) separately. event.x()/event.y()
	// replace the old mouseX/mouseY params, and event.button().button() is the old raw button int
	// (0 = left). mouseScrolled wasn't touched by this change, so it keeps its old signature.
	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == 0 && isOverThumb(event.x(), event.y())) {
			scrollbarDragging = true;
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(@NonNull MouseButtonEvent event, double dragX, double dragY) {
		if (scrollbarDragging) {
			int maxScroll = maxScroll();
			if (maxScroll > 0) {
				int travel = Math.max(1, scrollbarTrackHeight - scrollbarThumbHeight);
				double t = (event.y() - scrollbarTrackTop - scrollbarThumbHeight / 2.0) / travel;
				scrollOffset = (int) Math.round(Math.clamp(t, 0, 1) * maxScroll);
				applyScroll();
			}
			return true;
		}
		return super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(@NonNull MouseButtonEvent event) {
		if (scrollbarDragging) {
			scrollbarDragging = false;
			return true;
		}
		return super.mouseReleased(event);
	}

	/** Adds a 0-150px slider bound to a single gap value, saving on every drag so the HUD moves live.
	 *  Returns the content-local Y for the row after this one. */
	private int addGapSlider(int x, int localY, String label, int currentGap,
	                         IntConsumer onChange) {
		int startGap = clampGap(currentGap);
		double initialValue = (startGap - MIN_GAP) / (double) (MAX_GAP - MIN_GAP);
		AbstractSliderButton slider = new AbstractSliderButton(x, 0, 260, 20,
				Component.literal(sliderLabel(label, startGap)), initialValue) {
			@Override
			protected void updateMessage() {
				setMessage(Component.literal(sliderLabel(label, gapFromValue(this.value))));
			}

			@Override
			protected void applyValue() {
				onChange.accept(gapFromValue(this.value));
			}
		};
		addScrollable(slider, localY);
		return localY + 28;
	}

	/** Adds a font-scale slider snapped to 0.25 steps (0.75x-2.0x), saving on every drag.
	 *  Returns the content-local Y for the row after this one. */
	private int addFontScaleSlider(int x, int localY, double currentScale,
	                               DoubleConsumer onChange) {
		double startScale = clampAndSnapScale(currentScale);
		double initialValue = (startScale - MIN_FONT_SCALE) / (MAX_FONT_SCALE - MIN_FONT_SCALE);
		AbstractSliderButton slider = new AbstractSliderButton(x, 0, 260, 20,
				Component.literal(scaleSliderLabel(startScale)), initialValue) {
			@Override
			protected void updateMessage() {
				setMessage(Component.literal(scaleSliderLabel(scaleFromValue(this.value))));
			}

			@Override
			protected void applyValue() {
				onChange.accept(scaleFromValue(this.value));
			}
		};
		addScrollable(slider, localY);
		return localY + 28;
	}

	private static String scaleSliderLabel(double scale) {
		return "Lyrics Font Size" + ": " + trimTrailingZero(scale) + "x";
	}

	/** Formats 1.0/1.25/1.5 etc. without a trailing ".00"/".0" for whole values. */
	private static String trimTrailingZero(double scale) {
		String s = String.format(java.util.Locale.ROOT, "%.2f", scale);
		if (s.endsWith("00")) return s.substring(0, s.length() - 3);
		if (s.endsWith("0")) return s.substring(0, s.length() - 1);
		return s;
	}

	private static double clampAndSnapScale(double scale) {
		double clamped = Math.clamp(scale, MIN_FONT_SCALE, MAX_FONT_SCALE);
		return MIN_FONT_SCALE + Math.round((clamped - MIN_FONT_SCALE) / FONT_SCALE_STEP) * FONT_SCALE_STEP;
	}

	private static double scaleFromValue(double value) {
		double raw = MIN_FONT_SCALE + value * (MAX_FONT_SCALE - MIN_FONT_SCALE);
		return clampAndSnapScale(raw);
	}

	/** Mirrors LyricsHud's own game mode check, so this screen's layout matches what's actually drawn. */
	private boolean isSurvivalHud() {
		if (minecraft.gameMode == null) return false;
		GameType mode = minecraft.gameMode.getPlayerMode();
		return mode == GameType.SURVIVAL || mode == GameType.ADVENTURE;
	}

	private static String sliderLabel(String label, int gap) {
		return label + ": " + gap + "px above hotbar";
	}

	private static int clampGap(int gap) {
		return Math.clamp(gap, MIN_GAP, MAX_GAP);
	}

	private static int gapFromValue(double value) {
		return MIN_GAP + (int) Math.round(value * (MAX_GAP - MIN_GAP));
	}

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

	/** Switches this screen into the install-consent view (see initInstallConfirm) instead of
	 *  starting the download immediately -- installLibrespot() itself is now only reachable
	 *  after the user's clicked through that. */
	private void openInstallConfirm() {
		showInstallConfirm = true;
		init(width, height);
	}

	/**
	 * Kicks off the shared install (see LibrespotInstaller's class doc for why the build itself
	 * lives there and not on this screen) and attaches this screen's own listeners to it. If a
	 * build from an earlier open of this screen is already running, installLibrespotAsync() just
	 * attaches without starting a second one -- clicking this button is still the user's original
	 * consent to install, given the first time they clicked it.
	 */
	private void installLibrespot() {
		statusMessage = "";
		LibrespotInstaller.installLibrespotAsync(installProgressListener, installCompleteListener);
		// This runs on the render thread already (button press), so no need to hop via
		// minecraft.execute() the way the async completion handlers below do.
		init(width, height);
	}

	/** Stops the in-progress download dead rather than letting it finish -- the completion listener
	 *  still fires with a "canceled" result, which handles the status message and rebuilding the
	 *  layout the same way a failed install would. */
	private void cancelInstall() {
		if (cancelInstallButton != null) cancelInstallButton.active = false;
		LibrespotInstaller.cancelInstall();
	}

	/** Runs on whatever thread the installation executor finishes the build on (success, failure, or
	 *  cancel) -- hop to the render thread before touching config or rebuilding the layout. */
	private void onInstallComplete(LibrespotInstaller.InstallResult result) {
		minecraft.execute(() -> {
			if (result.success()) {
				ModConfig c = ModConfig.get();
				c.librespotPath = result.path();
				c.librespotEnabled = true;
				c.save();
				statusMessage = "librespot installed! Open Spotify and select \"" + c.librespotDeviceName + "\".";
			} else {
				statusMessage = result.message();
			}
			// A finished install (either way) changes which buttons should be showing, so redo the
			// whole layout rather than a bare setMessage -- keeps every widget's position in sync.
			init(width, height);
		});
	}

	/**
	 * Removes the installed librespot binary by deleting it directly (there's no cargo/Rust
	 * toolchain involved anymore, so no package-manager uninstall step to run). First stops any
	 * running librespot process and disables it in config -- otherwise a live process is still
	 * holding the binary open, which fails to delete outright on Windows and is a bad idea to
	 * uninstall out from under regardless of platform. Runs on this screen's own bgExecutor rather
	 * than LibrespotInstaller's singleton one -- unlike a multi-minute build, there's nothing worth
	 * resuming if the screen closes mid-uninstall, so it doesn't need the same cross-screen tracking.
	 */
	private void uninstallLibrespot() {
		if (uninstalling) return;
		uninstalling = true;
		statusMessage = "";
		if (uninstallLibrespotButton != null) {
			uninstallLibrespotButton.active = false;
			uninstallLibrespotButton.setMessage(Component.literal("Uninstalling..."));
		}
		ModConfig c = ModConfig.get();
		// Disabled up front, before we even know whether the uninstallation itself succeeds -- once the
		// user's asked to uninstall, letting maintainAudio() try to restart against a binary that's
		// about to be deleted (or already is) isn't the right fallback either way.
		c.librespotEnabled = false;
		c.save();
		poller.stopAudioAndWait().thenRunAsync(() -> {
			LibrespotInstaller.InstallResult result =
					LibrespotInstaller.uninstallLibrespot(c.librespotPath, this::setStatusMessage);
			if (result.success()) {
				c.librespotPath = "";
				c.save();
				setStatusMessage("librespot uninstalled.");
			} else {
				setStatusMessage("Uninstall failed: " + result.message());
			}
		}, bgExecutor).whenComplete((_, _) -> {
			uninstalling = false;
			minecraft.execute(() -> init(width, height));
		});
	}

	/**
	 * Updates the status line and re-derives its on-screen position, hopping to the render thread
	 * first if called from elsewhere (the installation progress callback runs on bgExecutor). Without
	 * this, statusMessage changes but statusScreenY -- computed only by applyScroll() -- never gets
	 * recalculated, so extractRenderState() keeps skipping the draw and no progress ever appears
	 * until the whole installation finishes and init() happens to run again.
	 */
	private void setStatusMessage(String message) {
		minecraft.execute(() -> {
			statusMessage = message;
			applyScroll();
		});
	}

	/** Heavier fix: full browser-based relogin, for when the refresh token itself is bad/revoked. */
	private void reauthenticate() {
		if (reauthenticating) return;
		reauthenticating = true;
		statusMessage = "";
		if (reauthButton != null) reauthButton.setMessage(Component.literal("Opening browser..."));
		poller.auth.login()
				.thenRun(() -> statusMessage = "Re-connected to Spotify!")
				.exceptionally(ex -> {
					statusMessage = "Re-auth failed: " + ex.getMessage();
					return null;
				})
				.whenComplete((_, _) -> {
					reauthenticating = false;
					if (reauthButton != null) reauthButton.setMessage(Component.literal("Re-authenticate"));
					applyScroll(); // status message just changed; re-check whether it's in view
				});
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		int x = width / 2 - PANEL_WIDTH / 2;
		int y = panelTop;
		graphics.fill(x, y, x + PANEL_WIDTH, y + panelHeight, PANEL_ALPHA);

		super.extractRenderState(graphics, mouseX, mouseY, delta);

		String titleText = this.title.getString();
		int textWidth = this.font.width(titleText);
		graphics.text(this.font, titleText, width / 2 - textWidth / 2, y + 8, 0xFFFFFFFF, true);

		if (installNoteScreenY >= 0) {
			int noteY = installNoteScreenY;
			for (String line : installNoteLines) {
				int lineWidth = this.font.width(line);
				graphics.text(this.font, line, width / 2 - lineWidth / 2, noteY, 0xFF888888, false);
				noteY += STATUS_LINE_HEIGHT;
			}
		}

		if (titlePinnedNoteScreenY >= 0) {
			int noteY = titlePinnedNoteScreenY;
			for (String line : titlePinnedNoteLines) {
				int lineWidth = this.font.width(line);
				graphics.text(this.font, line, width / 2 - lineWidth / 2, noteY, 0xFF888888, false);
				noteY += STATUS_LINE_HEIGHT;
			}
		}

		if (confirmHeadingScreenY >= 0) {
			int noteY = confirmHeadingScreenY;
			for (String line : confirmHeadingLines) {
				int lineWidth = this.font.width(line);
				graphics.text(this.font, line, width / 2 - lineWidth / 2, noteY, 0xFFFFFFFF, true);
				noteY += STATUS_LINE_HEIGHT;
			}
		}

		if (confirmBodyScreenY >= 0) {
			int noteY = confirmBodyScreenY;
			for (String line : confirmBodyLines) {
				int lineWidth = this.font.width(line);
				graphics.text(this.font, line, width / 2 - lineWidth / 2, noteY, 0xFFAAAAAA, false);
				noteY += STATUS_LINE_HEIGHT;
			}
		}

		if (statusScreenY >= 0) {
			int color;
			if (statusMessage.startsWith("Re-auth failed") || statusMessage.startsWith("Install failed")) {
				color = 0xFFFF5555; // red
			} else if (statusMessage.contains("installed") || statusMessage.contains("Re-connected")) {
				color = 0xFF55FF55; // green
			} else {
				color = 0xFFAAAAAA; // neutral -- e.g. a live download-progress line
			}
			List<String> lines = wrapText(statusMessage, PANEL_WIDTH - 20, STATUS_MAX_LINES);
			int statusY = statusScreenY;
			for (String line : lines) {
				int lineWidth = this.font.width(line);
				graphics.text(this.font, line, width / 2 - lineWidth / 2, statusY, color, true);
				statusY += STATUS_LINE_HEIGHT;
			}
		}

		if (maxScroll() > 0) {
			graphics.fill(scrollbarX, scrollbarTrackTop, scrollbarX + SCROLLBAR_WIDTH,
					scrollbarTrackTop + scrollbarTrackHeight, 0x20FFFFFF);
			graphics.fill(scrollbarX, scrollbarThumbY, scrollbarX + SCROLLBAR_WIDTH,
					scrollbarThumbY + scrollbarThumbHeight, scrollbarDragging ? 0x60FFFFFF : 0xD0FFFFFF);
		}
	}

	/**
	 * Word-wraps text to fit a pixel width, across up to maxLines lines. If the text still doesn't
	 * fit after maxLines, the last line is truncated with an ellipsis so nothing renders off-panel.
	 */
	private List<String> wrapText(String s, int maxWidthPx, int maxLines) {
		String[] words = s.split(" ");
		List<String> lines = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		int consumed = 0;
		for (String word : words) {
			String candidate = current.isEmpty() ? word : current + " " + word;
			if (this.font.width(candidate) <= maxWidthPx || current.isEmpty()) {
				current = new StringBuilder(candidate);
				consumed++;
			} else if (lines.size() < maxLines - 1) {
				lines.add(current.toString());
				current = new StringBuilder(word);
				consumed++;
			} else {
				break; // out of lines; whatever's left (including this word) overflows
			}
		}
		boolean overflowed = consumed < words.length;
		if (!current.isEmpty()) lines.add(current.toString());
		int lastIdx = lines.size() - 1;
		if (overflowed && lastIdx >= 0) {
			lines.set(lastIdx, fitText(lines.get(lastIdx), maxWidthPx));
		}
		return lines;
	}

	/** Trims text to fit a pixel width (rather than a fixed char count). Delegates to the same
	 *  (binary-search-optimized) implementation the render package's panels use, instead of keeping
	 *  a second, slower copy of the same algorithm in sync by hand. */
	private String fitText(String s, int maxWidthPx) {
		return TextLayout.fitText(this.font, s, maxWidthPx);
	}

	@Override
	public boolean keyPressed(@NonNull KeyEvent event) {
		if (super.keyPressed(event)) return true;
		// Same reasoning as PlayerControlScreen: the KeyMapping's consumeClick() never fires while
		// a screen is open, so closing has to be handled directly here instead.
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

	/** A scrollable widget paired with its content-local Y (before the scroll offset is applied). */
	private static final class ScrollEntry {
		final AbstractWidget widget;
		final int localY;

		ScrollEntry(AbstractWidget widget, int localY) {
			this.widget = widget;
			this.localY = localY;
		}
	}
}
package xien.jxsh.spotymc.gui;

import org.jspecify.annotations.NonNull;
import xien.jxsh.spotymc.PlaybackPoller;
import xien.jxsh.spotymc.Spotymc;
import xien.jxsh.spotymc.api.PlaybackState;
import xien.jxsh.spotymc.config.ModConfig;
import xien.jxsh.spotymc.gui.browse.BrowseController;
import xien.jxsh.spotymc.gui.layout.PanelLayout;
import xien.jxsh.spotymc.gui.model.ClickableRowHit;
import xien.jxsh.spotymc.gui.render.CenterPanelRenderer;
import xien.jxsh.spotymc.gui.render.DrawUtil;
import xien.jxsh.spotymc.gui.render.HoverTracker;
import xien.jxsh.spotymc.gui.render.LeftPanelRenderer;
import xien.jxsh.spotymc.gui.render.QueuePanelRenderer;
import xien.jxsh.spotymc.gui.render.TextLayout;
import xien.jxsh.spotymc.gui.render.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The F12 overlay: three side-by-side containers.
 * <p>
 * LEFT   -- "Search" / "Library" tabs: search bar + results (tracks and
 *           playlists) for finding and playing songs, or your saved Liked
 *           Songs and own playlists for quick access without typing.
 * CENTER -- "Player": now playing + progress, transport controls, volume,
 *           settings, in-game audio toggle.
 * RIGHT  -- "Queue": the upcoming queue, always visible; each entry is
 *           clickable (plain text, not a button) to skip straight to it,
 *           advancing through the existing queue rather than replacing it.
 * <p>
 * Doesn't pause the game (see Spotymc - this screen is opened without pausing).
 * <p>
 * Ported to Minecraft 26.2 / Mojang mappings (post Yarn-deprecation) and the
 * new extractRenderState() rendering pipeline introduced around 1.21.8+/26.x.
 * <p>
 * This class only handles widget wiring and input; panel geometry lives in
 * {@link PanelLayout}, search/library state and networking in
 * {@link BrowseController}, and the actual pixel-pushing in the {@code render}
 * package (one renderer per panel).
 */
public class PlayerControlScreen extends Screen {

	private final PlaybackPoller poller;
	private final BrowseController browse;
	private PanelLayout layout;

	private Button audioToggleButton;
	private VolumeSlider volumeSlider;
	// Last volume percent we've reflected in the slider -- lets us tell "Spotify's volume changed
	// elsewhere (phone/app)" apart from "we're the one who just set it".
	private int syncedVolumePercent = -1;
	// True while any mouse button is held anywhere on this screen. Blocks the external-volume
	// sync below from fighting an in-progress drag on the slider.
	private boolean mouseButtonDown = false;
	private EditBox clientIdField;
	private EditBox searchField;
	private String statusMessage = "";
	// Cache for the wrapped statusMessage lines drawn in extractRenderState() -- see the wrap call
	// below for why.
	private String lastStatusMessage = null;
	private int lastStatusMessageMaxW = -1;
	private List<String> cachedStatusMessageLines = List.of();

	// One shared background executor for this screen's async actions (play/pause/skip/search/etc).
	// Previously each action spun up its own Executors.newSingleThreadExecutor(), which leaks a
	// non-daemon thread per click and never gets shut down -- this reuses a small fixed pool for
	// the life of the screen and shuts it down cleanly in removed().
	private final ExecutorService bgExecutor = Executors.newFixedThreadPool(2, r -> {
		Thread t = new Thread(r, "spotymc-screen-action");
		t.setDaemon(true);
		return t;
	});

	private final List<ClickableRowHit> queueHits = new ArrayList<>();
	private final List<ClickableRowHit> searchHits = new ArrayList<>(); // same shape, reused for search/library rows

	// Progress bar seeking. Bounds are recomputed each frame by CenterPanelRenderer (alongside
	// all the other center-panel drawing), so click/drag handlers can hit-test against them.
	private CenterPanelRenderer.ProgressBarBounds progressBarBounds;
	private boolean draggingProgress = false;
	private int dragPreviewProgressMs = 0;

	// Left-panel scrollbar dragging. Bounds are recomputed each frame by LeftPanelRenderer
	// (null when the current list doesn't need a scrollbar), alongside the row hit-boxes.
	private LeftPanelRenderer.ScrollbarBounds scrollbarBounds;
	private boolean draggingScrollbar = false;
	// Offset from the thumb's top edge to the mouse position when the drag started, so the thumb
	// doesn't jump to be centered under the cursor the instant you grab it partway down its length.
	private double scrollbarDragGrabOffsetY = 0;

	// Queue panel scrolling. The queue has no BrowseController-style owner, so the scroll offset
	// just lives here; QueuePanelRenderer clamps it each frame (queue length changes as tracks
	// play) and hands back the value actually used, which we store back below. Bounds/dragging
	// mirror the left panel's scrollbar exactly, reusing its ScrollbarBounds geometry type.
	private int queueScrollOffset = 0;
	private LeftPanelRenderer.ScrollbarBounds queueScrollbarBounds;
	private boolean draggingQueueScrollbar = false;
	private double queueScrollbarDragGrabOffsetY = 0;

	// Marquee hover timing for each scrollable list -- see HoverTracker. Owned here (not the
	// static renderers) so a row's "just hovered, hold still for a beat" state actually survives
	// from one frame to the next.
	private final HoverTracker leftListHoverTracker = new HoverTracker();
	private final HoverTracker queueHoverTracker = new HoverTracker();

	public PlayerControlScreen(PlaybackPoller poller) {
		super(Component.literal("Spotify Controls"));
		this.poller = poller;
		this.browse = new BrowseController(poller, bgExecutor);
	}

	@Override
	public void removed() {
		// Deliberately does NOT shut down bgExecutor here. removed() fires any time this screen
		// stops being the active one -- including when it's merely swapped out for a *child*
		// screen (e.g. clicking Settings below), which reuses this exact instance as `parent`
		// and hands it back via minecraft.gui.setScreen(parent) rather than constructing a new
		// PlayerControlScreen. Since bgExecutor (and BrowseController, which was built with it)
		// are final fields set once in the constructor, shutting the pool down here would leave
		// this reused instance permanently unable to run any async action -- play/pause/skip,
		// volume, search, library loads, and queue-skip clicks would all start throwing
		// RejectedExecutionException the moment you navigate back from Settings. Real shutdown
		// happens in onClose() instead, which only fires on an actual close.
		super.removed();
	}

	@Override
	public void onClose() {
		bgExecutor.shutdownNow();
		super.onClose();
	}

	@Override
	protected void init() {
		layout = PanelLayout.compute(width, height);

		if (poller.auth.isLoggedIn()) {
			initLoginView();
			return;
		}

		initCenterPanel();
		initLeftPanel();
		// Right (queue) panel is read-only text, drawn entirely in extractRenderState().
	}

	private void initLoginView() {
		int loginH = 110;
		int cx = width / 2;
		int top = Math.clamp(height / 2 - loginH / 2, 4, height - loginH - 4);

		ModConfig cfg = ModConfig.get();
		clientIdField = new EditBox(this.font, cx - 100, top + 40, 200, 20,
				Component.literal("Spotify Client ID"));
		clientIdField.setValue(cfg.clientId);
		clientIdField.setMaxLength(64);
		addRenderableWidget(clientIdField);

		addRenderableWidget(Button.builder(Component.literal("Save & Log in with Spotify"), _ -> {
			cfg.clientId = clientIdField.getValue().trim();
			cfg.save();
			doLogin();
		}).bounds(cx - 100, top + 66, 200, 20).build());
	}

	private void initCenterPanel() {
		int inner = layout.centerW - 24;
		int rowStep = layout.btnH + 6; // matches the original fixed-size spacing, scaled by the actual button height

		int topOffset = (int) Math.round(38 * layout.vScale); // gap from panelTop to the transport row
		int audioStatusMarginTop = (int) Math.round(8 * layout.vScale);
		layout.audioStatusLineH = Math.max(9, (int) Math.round(9 * layout.vScale));

		// Now that search has moved to the left panel, the player controls no longer fill
		// panelH on their own -- center the whole block vertically instead of leaving the
		// leftover space stranded at the bottom.
		int contentH = topOffset + rowStep * 3 + layout.btnH + audioStatusMarginTop + layout.audioStatusLineH * 2;
		layout.centerContentOffsetY = Math.max(0, (layout.panelH - contentH - (int) Math.round(10 * layout.vScale)) / 2);

		// --- Transport row: icon-only buttons so they scale down cleanly. ---
		// Centered on centerMidX -- the same center point the progress bar below uses --
		// so the three transport buttons line up visually with the bar above them.
		int transportY = layout.panelTop + layout.centerContentOffsetY + topOffset;
		int btnW = Math.max(28, (inner - 12) / 3);
		int startX = layout.centerMidX - (btnW * 3 + 12) / 2;

		addRenderableWidget(Button.builder(Component.literal("⏮"), _ -> runAsync(poller.api::previous))
				.bounds(startX, transportY, btnW, layout.btnH).build());
		addRenderableWidget(Button.builder(Component.literal("⏯"), _ -> togglePlayPause())
				.bounds(startX + btnW + 6, transportY, btnW, layout.btnH).build());
		addRenderableWidget(Button.builder(Component.literal("⏭"), _ -> runAsync(poller.api::next))
				.bounds(startX + (btnW + 6) * 2, transportY, btnW, layout.btnH).build());

		// --- Volume ---
		int sliderY = transportY + rowStep;
		int audioW = (int) (inner * 0.87);
		PlaybackState state = poller.getState();
		double initialVolume = state.volumePercent / 100.0;
		volumeSlider = new VolumeSlider(layout.centerMidX - audioW / 2, sliderY, audioW, layout.btnH, initialVolume);
		addRenderableWidget(volumeSlider);
		syncedVolumePercent = state.volumePercent;

		// --- Settings ---
		int settingsY = sliderY + rowStep;
		int settingsW = (int) (inner * 0.6);
		addRenderableWidget(Button.builder(Component.literal("⚙ Settings"), _ -> minecraft.gui.setScreen(new HudSettingsScreen(this, poller)))
				.bounds(layout.centerMidX - settingsW / 2, settingsY, settingsW, layout.btnH).build());

		// --- In-game audio toggle ---
		int audioToggleY = settingsY + rowStep;
		boolean audioOn = ModConfig.get().librespotEnabled;
		audioToggleButton = Button.builder(
						Component.literal("In-Game Audio: " + (audioOn ? "ON" : "OFF")), _ -> toggleAudio())
				.bounds(layout.centerMidX - audioW / 2, audioToggleY, audioW, layout.btnH).build();
		addRenderableWidget(audioToggleButton);

		// Status line(s) drawn by CenterPanelRenderer, just below the toggle button. A small
		// top margin keeps it from crowding the button, and we reserve room for up to two lines
		// since the "select device" message can be too long to fit on one line at narrow widths.
		layout.audioStatusY = audioToggleY + layout.btnH + audioStatusMarginTop;
	}

	private void initLeftPanel() {
		int listInnerW = layout.leftW - 20;

		// --- Tabs: Search / Library, swap which fills the rest with the left panel ---
		int tabY = layout.panelTop + (int) Math.round(8 * layout.vScale);
		int tabH = Math.max(14, (int) Math.round(14 * layout.vScale));
		int tabW = (listInnerW - 4) / 2;
		addRenderableWidget(Button.builder(Component.literal("Search"),
						_ -> browse.switchMode(BrowseController.Mode.SEARCH, this::refresh))
				.bounds(layout.leftX + 10, tabY, tabW, tabH).build());
		addRenderableWidget(Button.builder(Component.literal("Library"),
						_ -> browse.switchMode(BrowseController.Mode.LIBRARY, this::refresh,
								err -> Minecraft.getInstance().execute(() -> statusMessage = err)))
				.bounds(layout.leftX + 10 + tabW + 4, tabY, tabW, tabH).build());
		int belowTabsY = tabY + tabH + (int) Math.round(6 * layout.vScale);

		if (browse.mode() == BrowseController.Mode.SEARCH) {
			// --- Search bar (+ button, on large enough windows) ---
			int searchBtnW = Math.max(44, (int) Math.round(50 * layout.vScale));
			int searchFieldW = layout.showSearchButton ? Math.max(60, listInnerW - searchBtnW - 6) : listInnerW;

			searchField = new EditBox(this.font, layout.leftX + 10, belowTabsY, searchFieldW, layout.btnH,
					Component.literal("Search songs or playlists"));
			searchField.setMaxLength(100);
			searchField.setValue(browse.searchQuery());
			searchField.setResponder(browse::setSearchQuery);
			addRenderableWidget(searchField);

			if (layout.showSearchButton) {
				addRenderableWidget(Button.builder(
								Component.literal(browse.isSearching() ? "..." : "Search"), _ -> doSearch())
						.bounds(layout.leftX + 10 + searchFieldW + 6, belowTabsY, searchBtnW, layout.btnH).build());
			}
			layout.leftListY = belowTabsY + layout.btnH + (int) Math.round(8 * layout.vScale);
		} else {
			searchField = null;
			// Lazily fetch the library once when the tab is first opened; cheap re-opens after that.
			if (browse.hasLibraryLoaded() && !browse.isLoadingLibrary()) {
				browse.loadLibrary(this::refresh, err -> Minecraft.getInstance().execute(() -> statusMessage = err));
			}
			layout.leftListY = belowTabsY;
		}
		// Results/library rows are drawn as plain clickable text by LeftPanelRenderer, with
		// hit-boxes rebuilt there each frame -- see searchHits.

		// Exact row budget for whatever's actually left below the header down to the panel's
		// bottom border (rather than an approximation), so rows can never spill past it.
		int leftListBottomY = layout.panelTop + layout.panelH - (int) Math.round(6 * layout.vScale);
		layout.leftMaxRows = Math.max(PanelLayout.MIN_LIST_ROWS, (leftListBottomY - layout.leftListY) / layout.rowH);

		layout.listLeftX = layout.leftX + 10;
		layout.leftListLabelBudget = listInnerW - 10;

		layout.listRightX = layout.rightX + 10;
		layout.rightListY = layout.panelTop + (int) Math.round(26 * layout.vScale);
	}

	private void doSearch() {
		browse.doSearch(
				() -> Minecraft.getInstance().execute(this::refresh),
				() -> Minecraft.getInstance().execute(this::refresh),
				err -> statusMessage = err);
	}

	private void refresh() {
		if (Minecraft.getInstance().gui.screen() == this) {
			this.clearWidgets();
			this.init();
		}
	}

	private void doLogin() {
		poller.auth.login()
				.thenRun(() -> statusMessage = "Connected!")
				.exceptionally(ex -> {
					statusMessage = "Login failed: " + ex.getMessage();
					return null;
				});
	}

	private void togglePlayPause() {
		runAsync(() -> {
			if (poller.getState().isPlaying) poller.api.pause();
			else poller.api.play();
		});
	}

	private void toggleAudio() {
		ModConfig cfg = ModConfig.get();
		cfg.librespotEnabled = !cfg.librespotEnabled;
		cfg.save();
		// Don't set a status message here either way -- the audio status line drawn by
		// CenterPanelRenderer already tracks the real, live state (not installed/starting/
		// failed/playing) in friendlier terms, so a one-shot message here would just duplicate
		// (and go stale faster than) that.
		statusMessage = "";
		if (audioToggleButton != null) {
			audioToggleButton.setMessage(Component.literal("In-Game Audio: " + (cfg.librespotEnabled ? "ON" : "OFF")));
		}
	}

	/**
	 * Volume slider that can also be updated from outside a user drag -- e.g. reflecting a volume
	 * change made from the Spotify app or another Connect device, picked up on the next poll (see
	 * {@link #syncVolumeFromState}). setExternalVolume() only moves the knob and relabels it; it
	 * deliberately skips applyValue() so syncing doesn't turn around and fire another setVolume()
	 * call back at Spotify.
	 */
	private final class VolumeSlider extends AbstractSliderButton {
		VolumeSlider(int x, int y, int w, int h, double initialValue) {
			super(x, y, w, h, Component.literal(label(initialValue)), initialValue);
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.literal(label(this.value)));
		}

		@Override
		protected void applyValue() {
			int percent = (int) Math.round(this.value * 100);
			runAsync(() -> poller.api.setVolume(percent));
			// This IS the new true value (we just sent it) -- record it so the next poll tick
			// doesn't see its own echo and treat it as an "external" change to resync against.
			syncedVolumePercent = percent;
		}

		void setExternalVolume(int percent) {
			this.value = Math.clamp(percent / 100.0, 0.0, 1.0);
			updateMessage();
		}

		private static String label(double v) {
			return "Volume: " + Math.round(v * 100) + "%";
		}
	}

	/**
	 * Picks up volume changes made elsewhere (the Spotify app, another Connect device, etc.) and
	 * reflects them on the slider, bounded only by PlaybackPoller's ~2s poll cadence. Skipped
	 * entirely while the mouse is held down, so it can't fight an in-progress drag on the slider.
	 */
	private void syncVolumeFromState() {
		if (volumeSlider == null || mouseButtonDown) return;
		int currentPercent = poller.getState().volumePercent;
		if (currentPercent != syncedVolumePercent) {
			volumeSlider.setExternalVolume(currentPercent);
			syncedVolumePercent = currentPercent;
		}
	}

	private interface ThrowingRunnable {
		void run() throws Exception;
	}

	private void runAsync(ThrowingRunnable action) {
		java.util.concurrent.CompletableFuture.runAsync(() -> {
			try {
				action.run();
				poller.pollSoonBurst();
			} catch (Exception e) {
				statusMessage = "Error: " + e.getMessage();
			}
		}, bgExecutor);
	}

	@Override
	public void extractRenderState(@NonNull GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		if (poller.auth.isLoggedIn()) {
			renderLoginView(graphics);
			super.extractRenderState(graphics, mouseX, mouseY, delta);
			return;
		}

		syncVolumeFromState();

		// Screen title, spanning the full three-panel width.
		int titleCx = layout.leftX + layout.totalBlockW / 2;
		DrawUtil.drawCentered(graphics, this.font, this.title.getString(), titleCx, layout.panelTop - layout.titleH + 2, 0xFFFFFFFF);

		DrawUtil.drawPanel(graphics, layout.leftX, layout.panelTop, layout.leftW, layout.panelH, Theme.SIDE_BG, Theme.BORDER_SIDE);
		int centerBorder = 0x50000000 | (Theme.ACCENT & 0x00FFFFFF);
		DrawUtil.drawPanel(graphics, layout.centerX, layout.panelTop, layout.centerW, layout.panelH, Theme.CENTER_BG, centerBorder);
		DrawUtil.drawPanel(graphics, layout.rightX, layout.panelTop, layout.rightW, layout.panelH, Theme.SIDE_BG, Theme.BORDER_SIDE);

		super.extractRenderState(graphics, mouseX, mouseY, delta);

		progressBarBounds = CenterPanelRenderer.render(graphics, this.font, layout, poller, mouseX, mouseY,
				draggingProgress, dragPreviewProgressMs);

		LeftPanelRenderer.RenderResult leftResult = LeftPanelRenderer.render(graphics, this.font, mouseX, mouseY,
				layout, browse, leftListHoverTracker, searchHits);
		scrollbarBounds = leftResult.scrollbar();

		QueuePanelRenderer.RenderResult queueResult = QueuePanelRenderer.render(graphics, this.font, mouseX, mouseY,
				layout, poller, queueScrollOffset, queueHoverTracker, queueHits);
		queueScrollbarBounds = queueResult.scrollbar();
		queueScrollOffset = queueResult.scrollOffset();

		if (!statusMessage.isEmpty()) {
			int wrapW = layout.centerW - 16;
			// statusMessage only changes on the (comparatively rare) action outcomes, so cache its
			// wrap result instead of re-wrapping the same text on every single frame it's shown.
			if (!statusMessage.equals(lastStatusMessage) || wrapW != lastStatusMessageMaxW) {
				lastStatusMessage = statusMessage;
				lastStatusMessageMaxW = wrapW;
				cachedStatusMessageLines = TextLayout.wrapText(this.font, statusMessage, wrapW);
			}
			int lineH = 10;
			int y = layout.panelTop + layout.panelH - 12 - (cachedStatusMessageLines.size() - 1) * lineH;
			for (String line : cachedStatusMessageLines) {
				DrawUtil.drawCentered(graphics, this.font, line, layout.centerMidX, y, 0xFFFF5555);
				y += lineH;
			}
		}
	}

	private void renderLoginView(GuiGraphicsExtractor graphics) {
		int loginW = 220, loginH = 110;
		int cx = width / 2;
		int top = Math.clamp(height / 2 - loginH / 2, 4, height - loginH - 4);
		DrawUtil.drawPanel(graphics, cx - loginW / 2, top, loginW, loginH, Theme.CENTER_BG, Theme.BORDER_SIDE);
		DrawUtil.drawCentered(graphics, this.font, this.title.getString(), cx, top + 8, 0xFFFFFFFF);
		DrawUtil.drawCentered(graphics, this.font, "Not connected to Spotify yet.", cx, top + 22, Theme.TEXT_LABEL);
		DrawUtil.drawCentered(graphics, this.font, "Paste your Spotify Client ID below:", cx, top + 32, Theme.TEXT_LABEL);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		mouseButtonDown = true;
		double mouseX = event.x();
		double mouseY = event.y();

		PlaybackState state = poller.getState();
		boolean seekable = state.trackId != null && state.durationMs > 0;
		if (seekable && progressBarBounds != null && progressBarBounds.isOverGrabArea(mouseX, mouseY)) {
			draggingProgress = true;
			dragPreviewProgressMs = progressBarBounds.progressMsForMouseX(mouseX, state.durationMs);
			return true;
		}

		if (scrollbarBounds != null) {
			if (scrollbarBounds.isOverThumb(mouseX, mouseY)) {
				draggingScrollbar = true;
				scrollbarDragGrabOffsetY = mouseY - scrollbarBounds.thumbY();
				return true;
			}
			if (scrollbarBounds.isOverTrack(mouseX, mouseY)) {
				// Clicked the track outside the thumb -- jump so the thumb is centered under the
				// cursor, then let mouseDragged carry on from there if the button stays held.
				draggingScrollbar = true;
				scrollbarDragGrabOffsetY = scrollbarBounds.thumbH() / 2.0;
				int targetTop = (int) Math.round(mouseY - scrollbarDragGrabOffsetY);
				int newOffset = scrollbarBounds.scrollOffsetForThumbTopY(targetTop);
				browse.setScrollOffset(newOffset, browse.buildEntries().size(), layout.leftMaxRows);
				return true;
			}
		}

		if (queueScrollbarBounds != null) {
			if (queueScrollbarBounds.isOverThumb(mouseX, mouseY)) {
				draggingQueueScrollbar = true;
				queueScrollbarDragGrabOffsetY = mouseY - queueScrollbarBounds.thumbY();
				return true;
			}
			if (queueScrollbarBounds.isOverTrack(mouseX, mouseY)) {
				draggingQueueScrollbar = true;
				queueScrollbarDragGrabOffsetY = queueScrollbarBounds.thumbH() / 2.0;
				int targetTop = (int) Math.round(mouseY - queueScrollbarDragGrabOffsetY);
				queueScrollOffset = queueScrollbarBounds.scrollOffsetForThumbTopY(targetTop);
				return true;
			}
		}

		for (ClickableRowHit hit : queueHits) {
			if (hit.contains(mouseX, mouseY)) {
				int index = hit.index();
				// Instant feedback: drop it (and everything ahead of it) from the visible queue
				// right away rather than waiting ~1-2s for Spotify to register the change and the
				// next poll to notice. Skipping through next() repeatedly -- rather than starting
				// a brand-new single-track playback context via playTrack() -- keeps the rest of
				// the queue intact instead of wiping it out.
				poller.optimisticAdvanceQueue(index + 1);
				runAsync(() -> poller.api.skipToQueueIndex(index));
				return true;
			}
		}
		for (ClickableRowHit hit : searchHits) {
			if (hit.contains(mouseX, mouseY)) {
				switch (hit.kind()) {
					case TRACK -> {
						String uri = hit.uri();
						runAsync(() -> poller.api.playTrack(uri));
					}
					case PLAYLIST -> {
						String uri = hit.uri();
						runAsync(() -> poller.api.playPlaylist(uri));
					}
					case LIKED_SONGS -> browse.openLikedSongs();
					case BACK -> browse.closeLikedSongs();
					case LIKED_SONG_TRACK -> {
						int idx = hit.index();
						List<PlaybackState.Track> liked = browse.likedSongs();
						if (liked != null && idx >= 0 && idx < liked.size()) {
							// Start at the clicked song and carry the rest of Liked Songs forward
							// as the queue, the same way clicking a song in a real playlist does.
							List<String> uris = liked.subList(idx, liked.size()).stream()
									.map(PlaybackState.Track::uri).toList();
							runAsync(() -> poller.api.playTracks(uris));
						}
					}
					default -> {}
				}
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(@NonNull MouseButtonEvent event, double dragX, double dragY) {
		if (draggingQueueScrollbar) {
			if (queueScrollbarBounds != null) {
				int targetTop = (int) Math.round(event.y() - queueScrollbarDragGrabOffsetY);
				queueScrollOffset = queueScrollbarBounds.scrollOffsetForThumbTopY(targetTop);
			}
			return true;
		}
		if (draggingScrollbar) {
			if (scrollbarBounds != null) {
				int targetTop = (int) Math.round(event.y() - scrollbarDragGrabOffsetY);
				int newOffset = scrollbarBounds.scrollOffsetForThumbTopY(targetTop);
				browse.setScrollOffset(newOffset, browse.buildEntries().size(), layout.leftMaxRows);
			}
			return true;
		}
		if (draggingProgress) {
			PlaybackState state = poller.getState();
			if (state.trackId == null || state.durationMs <= 0) {
				draggingProgress = false; // track ended/changed mid-drag -- bail out cleanly
				return true;
			}
			if (progressBarBounds != null) {
				dragPreviewProgressMs = progressBarBounds.progressMsForMouseX(event.x(), state.durationMs);
			}
			return true;
		}
		return super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(@NonNull MouseButtonEvent event) {
		mouseButtonDown = false;
		if (draggingQueueScrollbar) {
			draggingQueueScrollbar = false;
			return true;
		}
		if (draggingScrollbar) {
			draggingScrollbar = false;
			return true;
		}
		if (draggingProgress) {
			draggingProgress = false;
			int seekMs = dragPreviewProgressMs;
			// Reflect the jump immediately; the real seek call (and the fast-resync burst it
			// triggers) true it up against Spotify shortly after.
			poller.optimisticSeek(seekMs);
			runAsync(() -> poller.api.seek(seekMs));
			return true;
		}
		return super.mouseReleased(event);
	}

	/**
	 * Scrolls the left panel's search/library row list. Uses the classic double-based
	 * mouseScrolled signature -- if this build's Screen has since moved scroll input onto an
	 * event object (the way click/key input has been here), adjust the override to match.
	 */
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		boolean overLeftPanel = mouseX >= layout.leftX && mouseX < layout.leftX + layout.leftW
				&& mouseY >= layout.panelTop && mouseY < layout.panelTop + layout.panelH;
		int total = browse.buildEntries().size();
		if (overLeftPanel && total > layout.leftMaxRows) {
			int direction = scrollY > 0 ? -1 : (scrollY < 0 ? 1 : 0);
			browse.scrollBy(direction, total, layout.leftMaxRows);
			return true;
		}

		boolean overRightPanel = mouseX >= layout.rightX && mouseX < layout.rightX + layout.rightW
				&& mouseY >= layout.panelTop && mouseY < layout.panelTop + layout.panelH;
		int queueTotal = poller.getQueue().size();
		if (overRightPanel && queueTotal > layout.rightMaxRows) {
			int direction = scrollY > 0 ? -1 : (scrollY < 0 ? 1 : 0);
			int maxScroll = Math.max(0, queueTotal - layout.rightMaxRows);
			queueScrollOffset = Math.clamp(maxScroll, 0, queueScrollOffset + direction);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean keyPressed(@NonNull KeyEvent event) {
		if (super.keyPressed(event)) return true;
		// Handled here rather than via the KeyMapping's consumeClick() -- vanilla only processes a
		// mapping's queued clicks while no screen is open, so that path can open this screen but
		// never close it again. keyPressed always fires regardless, the same way Esc already does.
		if (event.key() == Spotymc.TOGGLE_KEYCODE) {
			this.onClose();
			return true;
		}
		if (searchField != null && searchField.isFocused()
				&& (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER)) {
			doSearch();
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
}
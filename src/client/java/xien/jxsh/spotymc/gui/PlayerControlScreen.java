package xien.jxsh.spotymc.gui;

import org.jspecify.annotations.NonNull;
import xien.jxsh.spotymc.PlaybackPoller;
import xien.jxsh.spotymc.Spotymc;
import xien.jxsh.spotymc.api.PlaybackState;
import xien.jxsh.spotymc.config.ModConfig;
import xien.jxsh.spotymc.gui.async.ScreenActionRunner;
import xien.jxsh.spotymc.gui.async.ThrowingRunnable;
import xien.jxsh.spotymc.gui.browse.BrowseController;
import xien.jxsh.spotymc.gui.input.PlayerControlInput;
import xien.jxsh.spotymc.gui.layout.PanelLayout;
import xien.jxsh.spotymc.gui.model.ClickableRowHit;
import xien.jxsh.spotymc.gui.render.CenterPanelRenderer;
import xien.jxsh.spotymc.gui.render.DrawUtil;
import xien.jxsh.spotymc.gui.render.HoverTracker;
import xien.jxsh.spotymc.gui.render.LeftPanelRenderer;
import xien.jxsh.spotymc.gui.render.QueuePanelRenderer;
import xien.jxsh.spotymc.gui.render.StatusBanner;
import xien.jxsh.spotymc.gui.render.Theme;
import xien.jxsh.spotymc.gui.widget.VolumeSlider;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

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
 * This class only handles widget wiring, lifecycle, and gluing the view-model pieces together.
 * Panel geometry lives in {@link PanelLayout}; search/library state and networking in
 * {@link BrowseController}; mouse drag/scroll/click handling in {@link PlayerControlInput}; the
 * action-outcome banner in {@link StatusBanner}; the background executor in
 * {@link ScreenActionRunner}; and the actual pixel-pushing in the {@code render} package (one
 * renderer per panel).
 */
public class PlayerControlScreen extends Screen {

	private final PlaybackPoller poller;
	private final ScreenActionRunner actions;
	private final BrowseController browse;
	private final PlayerControlInput input = new PlayerControlInput();
	private final StatusBanner statusBanner = new StatusBanner();
	private PanelLayout layout;

	private Button audioToggleButton;
	private VolumeSlider volumeSlider;
	// Last volume percent we've reflected in the slider -- lets us tell "Spotify's volume changed
	// elsewhere (phone/app)" apart from "we're the one who just set it".
	private int syncedVolumePercent = -1;
	private EditBox clientIdField;
	private EditBox searchField;

	private final List<ClickableRowHit> queueHits = new ArrayList<>();
	private final List<ClickableRowHit> searchHits = new ArrayList<>(); // same shape, reused for search/library rows

	// Marquee hover timing for each scrollable list -- see HoverTracker. Owned here (not the
	// static renderers) so a row's "just hovered, hold still for a beat" state actually survives
	// from one frame to the next.
	private final HoverTracker leftListHoverTracker = new HoverTracker();
	private final HoverTracker queueHoverTracker = new HoverTracker();

	public PlayerControlScreen(PlaybackPoller poller) {
		super(Component.literal("Spotify Controls"));
		this.poller = poller;
		// One shared background executor for this screen's async actions (play/pause/skip/search/
		// etc), reused for the life of the screen and shut down cleanly in onClose().
		this.actions = new ScreenActionRunner("spotymc-screen-action", poller::pollSoonBurst, this::setStatusMessage);
		this.browse = new BrowseController(poller, actions.executor());
	}

	@Override
	public void removed() {
		// Deliberately does NOT shut down `actions` here. removed() fires any time this screen
		// stops being the active one -- including when it's merely swapped out for a *child*
		// screen (e.g. clicking Settings below), which reuses this exact instance as `parent`
		// and hands it back via minecraft.gui.setScreen(parent) rather than constructing a new
		// PlayerControlScreen. Since `actions` (and BrowseController, which was built with its
		// executor) are final fields set once in the constructor, shutting the pool down here
		// would leave this reused instance permanently unable to run any async action --
		// play/pause/skip, volume, search, library loads, and queue-skip clicks would all start
		// throwing RejectedExecutionException the moment you navigate back from Settings. Real
		// shutdown happens in onClose() instead, which only fires on an actual close.
		super.removed();
	}

	@Override
	public void onClose() {
		actions.shutdown();
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
		layout.computeCenterPanelGeometry();

		// --- Transport row: icon-only buttons so they scale down cleanly. ---
		addRenderableWidget(Button.builder(Component.literal("⏮"), _ -> runAsync(poller.api::previous))
				.bounds(layout.startX, layout.transportY, layout.btnW, layout.btnH).build());
		addRenderableWidget(Button.builder(Component.literal("⏯"), _ -> togglePlayPause())
				.bounds(layout.startX + layout.btnW + 6, layout.transportY, layout.btnW, layout.btnH).build());
		addRenderableWidget(Button.builder(Component.literal("⏭"), _ -> runAsync(poller.api::next))
				.bounds(layout.startX + (layout.btnW + 6) * 2, layout.transportY, layout.btnW, layout.btnH).build());

		// --- Volume ---
		PlaybackState state = poller.getState();
		double initialVolume = state.volumePercent / 100.0;
		volumeSlider = new VolumeSlider(layout.centerMidX - layout.audioW / 2, layout.sliderY, layout.audioW, layout.btnH,
				initialVolume, percent -> {
			runAsync(() -> poller.api.setVolume(percent));
			// This IS the new true value (we just sent it) -- record it so the next poll
			// tick doesn't see its own echo and treat it as an "external" change to
			// resync against.
			syncedVolumePercent = percent;
		});
		addRenderableWidget(volumeSlider);
		syncedVolumePercent = state.volumePercent;

		// --- Settings ---
		addRenderableWidget(Button.builder(Component.literal("⚙ Settings"), _ -> minecraft.gui.setScreen(new HudSettingsScreen(this, poller)))
				.bounds(layout.centerMidX - layout.settingsW / 2, layout.settingsY, layout.settingsW, layout.btnH).build());

		// --- In-game audio toggle ---
		boolean audioOn = ModConfig.get().librespotEnabled;
		audioToggleButton = Button.builder(
						Component.literal("In-Game Audio: " + (audioOn ? "ON" : "OFF")), _ -> toggleAudio())
				.bounds(layout.centerMidX - layout.audioW / 2, layout.audioToggleY, layout.audioW, layout.btnH).build();
		addRenderableWidget(audioToggleButton);
	}

	private void initLeftPanel() {
		boolean searchMode = browse.mode() == BrowseController.Mode.SEARCH;
		layout.computeLeftPanelGeometry(searchMode);

		// --- Tabs: Search / Library, swap which fills the rest with the left panel ---
		addRenderableWidget(Button.builder(Component.literal("Search"),
						_ -> browse.switchMode(BrowseController.Mode.SEARCH, this::refresh))
				.bounds(layout.leftX + 10, layout.tabY, layout.tabW, layout.tabH).build());
		addRenderableWidget(Button.builder(Component.literal("Library"),
						_ -> browse.switchMode(BrowseController.Mode.LIBRARY, this::refresh,
								err -> Minecraft.getInstance().execute(() -> setStatusMessage(err))))
				.bounds(layout.leftX + 10 + layout.tabW + 4, layout.tabY, layout.tabW, layout.tabH).build());

		if (searchMode) {
			// --- Search bar (+ button, on large enough windows) ---
			searchField = new EditBox(this.font, layout.leftX + 10, layout.belowTabsY, layout.searchFieldW, layout.btnH,
					Component.literal("Search songs or playlists"));
			searchField.setMaxLength(100);
			searchField.setValue(browse.searchQuery());
			searchField.setResponder(browse::setSearchQuery);
			addRenderableWidget(searchField);

			if (layout.showSearchButton) {
				addRenderableWidget(Button.builder(
								Component.literal(browse.isSearching() ? "..." : "Search"), _ -> doSearch())
						.bounds(layout.leftX + 10 + layout.searchFieldW + 6, layout.belowTabsY, layout.searchBtnW, layout.btnH).build());
			}
		} else {
			searchField = null;
			// Lazily fetch the library once when the tab is first opened; cheap re-opens after that.
			if (browse.hasLibraryLoaded() && !browse.isLoadingLibrary()) {
				browse.loadLibrary(this::refresh, err -> Minecraft.getInstance().execute(() -> setStatusMessage(err)));
			}
		}
		// Results/library rows are drawn as plain clickable text by LeftPanelRenderer, with
		// hit-boxes rebuilt there each frame -- see searchHits.
	}

	private void doSearch() {
		browse.doSearch(
				() -> Minecraft.getInstance().execute(this::refresh),
				() -> Minecraft.getInstance().execute(this::refresh),
				err -> setStatusMessage(err));
	}

	/** Sets the banner text and stamps when it was shown, so extractRenderState() can auto-clear it. */
	private void setStatusMessage(String msg) {
		statusBanner.set(msg);
	}

	private void refresh() {
		if (Minecraft.getInstance().gui.screen() == this) {
			this.clearWidgets();
			this.init();
		}
	}

	private void doLogin() {
		poller.auth.login()
				.thenRun(() -> Minecraft.getInstance().execute(() -> {
					setStatusMessage("Connected!");
					// Login just flipped isLoggedIn() from true to false, but this screen's widgets
					// (clientIdField, "Save & Log in with Spotify") and layout (audioStatusY,
					// leftListY, etc.) were only ever built for the login view -- without rebuilding
					// here, those stale widgets stay registered and render on top of the now-active
					// full panel view, and layout fields that initCenterPanel()/initLeftPanel() never
					// got to set stay at their zero defaults.
					refresh();
				}))
				.exceptionally(ex -> {
					Minecraft.getInstance().execute(() -> setStatusMessage("Login failed: " + ex.getMessage()));
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
		statusBanner.clear();
		if (audioToggleButton != null) {
			audioToggleButton.setMessage(Component.literal("In-Game Audio: " + (cfg.librespotEnabled ? "ON" : "OFF")));
		}
	}

	/**
	 * Picks up volume changes made elsewhere (the Spotify app, another Connect device, etc.) and
	 * reflects them on the slider, bounded only by PlaybackPoller's ~2s poll cadence. Skipped
	 * entirely while the mouse is held down, so it can't fight an in-progress drag on the slider.
	 */
	private void syncVolumeFromState() {
		if (volumeSlider == null || input.isMouseButtonDown()) return;
		int currentPercent = poller.getState().volumePercent;
		if (currentPercent != syncedVolumePercent) {
			volumeSlider.setExternalVolume(currentPercent);
			syncedVolumePercent = currentPercent;
		}
	}

	private void runAsync(ThrowingRunnable action) {
		actions.run(action);
	}

	@Override
	public void extractRenderState(@NonNull GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		if (poller.auth.isLoggedIn()) {
			renderLoginView(graphics);
			super.extractRenderState(graphics, mouseX, mouseY, delta);
			return;
		}

		syncVolumeFromState();
		statusBanner.tick();

		// Screen title, spanning the full three-panel width.
		int titleCx = layout.leftX + layout.totalBlockW / 2;
		DrawUtil.drawCentered(graphics, this.font, this.title.getString(), titleCx, layout.panelTop - layout.titleH + 2, 0xFFFFFFFF);

		DrawUtil.drawPanel(graphics, layout.leftX, layout.panelTop, layout.leftW, layout.panelH, Theme.SIDE_BG, Theme.BORDER_SIDE);
		int centerBorder = 0x50000000 | (Theme.ACCENT & 0x00FFFFFF);
		DrawUtil.drawPanel(graphics, layout.centerX, layout.panelTop, layout.centerW, layout.panelH, Theme.CENTER_BG, centerBorder);
		DrawUtil.drawPanel(graphics, layout.rightX, layout.panelTop, layout.rightW, layout.panelH, Theme.SIDE_BG, Theme.BORDER_SIDE);

		super.extractRenderState(graphics, mouseX, mouseY, delta);

		input.setProgressBarBounds(CenterPanelRenderer.render(graphics, this.font, layout, poller, mouseX, mouseY,
				input.isDraggingProgress(), input.dragPreviewProgressMs(), !statusBanner.isEmpty()));

		LeftPanelRenderer.RenderResult leftResult = LeftPanelRenderer.render(graphics, this.font, mouseX, mouseY,
				layout, browse, leftListHoverTracker, searchHits);
		input.setScrollbarBounds(leftResult.scrollbar());

		QueuePanelRenderer.RenderResult queueResult = QueuePanelRenderer.render(graphics, this.font, mouseX, mouseY,
				layout, poller, input.queueScrollOffset(), queueHoverTracker, queueHits);
		input.setQueueScrollbarBounds(queueResult.scrollbar());
		input.setQueueScrollOffset(queueResult.scrollOffset());

		statusBanner.render(graphics, this.font, layout.centerMidX, layout.centerW - 16, layout.panelTop + layout.panelH - 12);
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
		if (input.mouseClicked(event, poller, browse, layout, queueHits, searchHits, this::runAsync)) {
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(@NonNull MouseButtonEvent event, double dragX, double dragY) {
		if (input.mouseDragged(event, poller, browse, layout)) {
			return true;
		}
		return super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(@NonNull MouseButtonEvent event) {
		if (input.mouseReleased(event, poller, this::runAsync)) {
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (input.mouseScrolled(mouseX, mouseY, scrollY, poller, browse, layout)) {
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
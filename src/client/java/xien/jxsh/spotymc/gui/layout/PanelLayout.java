package xien.jxsh.spotymc.gui.layout;

/**
 * Computed geometry for the F12 overlay's three side-by-side panels (search/library, player,
 * queue). Panel sizes are a fraction of the current window, clamped to sane min/max bounds, so
 * the overlay grows and shrinks with the game window instead of staying a fixed size.
 * <p>
 * {@link #compute(int, int)} fills in everything that only depends on the window size.
 * {@link #computeCenterPanelGeometry()} and {@link #computeLeftPanelGeometry(boolean)} fill in
 * the rest -- the screen calls those from its {@code initCenterPanel()}/{@code initLeftPanel()}
 * once it knows which left-panel header is showing (search bar vs. library tabs), since that's
 * screen state this class has no business guessing at. Everything after that is pure geometry,
 * kept here so the screen's init methods are just widget wiring.
 */
public final class PanelLayout {

	// --- geometry tuning constants ---
	private static final double TOTAL_W_FRACTION = 0.70;
	private static final int MIN_TOTAL_W = 460;
	private static final int MAX_TOTAL_W = 1320; // was 1100 -- that cap is what was clipping left/queue titles at large window sizes
	private static final double LEFT_SHARE = 0.315;  // was 0.29
	private static final double CENTER_SHARE = 0.37; // was 0.42 -- center's controls don't need the extra room the text lists do
	// RIGHT_SHARE is whatever remains after LEFT_SHARE + CENTER_SHARE, so the three always sum exactly.

	private static final double PANEL_H_FRACTION = 0.58;
	private static final int MIN_PANEL_H = 210;
	private static final int MAX_PANEL_H = 420;
	private static final int MIN_TITLE_H = 16;
	private static final int MAX_TITLE_H = 24;
	private static final int BASELINE_PANEL_H = 220; // panelH at which vScale == 1.0
	public static final int MIN_LIST_ROWS = 3;

	// --- panel positions/sizes ---
	public int totalBlockW;
	public int leftW, centerW, rightW, gap;
	public int leftX, centerX, rightX;
	public int panelTop, panelH, titleH;
	public int centerMidX;

	// --- control scaling ---
	public double vScale;   // how much taller than baseline the panel is; grows control sizing/spacing to match
	public int btnH;        // main control button/slider/field height, scaled by vScale
	public int rowH;        // vertical spacing between list rows, scaled (but capped) by vScale
	public boolean showSearchButton; // small windows hide the button; Enter still searches
	public int rightMaxRows;

	// --- filled in by computeLeftPanelGeometry(), once the active header (search bar vs. tabs) is known ---
	public int listLeftX, listRightX, leftListY, rightListY;
	public int leftListLabelBudget; // pixel width available for left-panel row/status text
	public int leftMaxRows;
	public int tabY, tabH, tabW, belowTabsY;
	public int searchBtnW, searchFieldW; // only meaningful in search mode

	// --- filled in by computeCenterPanelGeometry() ---
	public int centerContentOffsetY; // vertical offset that centers the player controls within panelH
	public int audioStatusY;
	public int audioStatusLineH;
	public int transportY, btnW, startX; // transport row (prev/play-pause/next)
	public int sliderY, audioW;          // volume slider (audioW also reused for the audio-toggle button)
	public int settingsY, settingsW;
	public int audioToggleY;

	private PanelLayout() {}

	/** Works out panel widths/heights/positions as a proportion of the current window/game size. */
	public static PanelLayout compute(int width, int height) {
		PanelLayout g = new PanelLayout();

		g.totalBlockW = (int) Math.round(width * TOTAL_W_FRACTION);
		g.totalBlockW = Math.clamp(g.totalBlockW, MIN_TOTAL_W, MAX_TOTAL_W);
		g.totalBlockW = Math.min(g.totalBlockW, width - 16); // never exceed the actual window

		// Below this, the Search button gets cramped next to the field -- hide it and let
		// Enter trigger the search instead. Above it (large window / fullscreen), show it.
		g.showSearchButton = g.totalBlockW >= 700;

		g.gap = Math.clamp(g.totalBlockW / 55, 8, 18);
		int content = g.totalBlockW - g.gap * 2;
		g.leftW = (int) Math.round(content * LEFT_SHARE);
		g.centerW = (int) Math.round(content * CENTER_SHARE);
		g.rightW = content - g.leftW - g.centerW; // remainder, so the three always sum to `content` exactly

		g.leftX = width / 2 - g.totalBlockW / 2;
		g.centerX = g.leftX + g.leftW + g.gap;
		g.rightX = g.centerX + g.centerW + g.gap;
		g.centerMidX = g.centerX + g.centerW / 2;

		g.panelH = (int) Math.round(height * PANEL_H_FRACTION);
		g.panelH = Math.clamp(g.panelH, MIN_PANEL_H, MAX_PANEL_H);
		g.titleH = (int) Math.round(height * 0.035);
		g.titleH = Math.clamp(g.titleH, MIN_TITLE_H, MAX_TITLE_H);
		g.panelH = Math.min(g.panelH, height - g.titleH - 8); // never overflow the actual window, however tall we'd like

		int blockH = g.titleH + g.panelH;
		int blockTop = Math.clamp(height / 2 - blockH / 2, 4, height - blockH - 4);
		g.panelTop = blockTop + g.titleH;

		// Grow control sizing/spacing along with a taller panel so the extra room actually gets used,
		// instead of just leaving empty space below a fixed-size control block.
		g.vScale = Math.clamp(g.panelH / (double) BASELINE_PANEL_H, 1.0, 1.3);
		g.btnH = (int) Math.round(20 * g.vScale);
		g.btnH = Math.clamp(g.btnH, 20, 27);
		g.rowH = (int) Math.round(15 * Math.min(g.vScale, 1.15));
		g.rowH = Math.clamp(g.rowH, 15, 18);

		// leftMaxRows is set by computeLeftPanelGeometry() instead, once the tab/search-bar header
		// height (which varies by mode) is actually known -- this fixed formula used to under-count
		// that header, letting rows overflow past the panel's bottom border.
		g.rightMaxRows = Math.max(MIN_LIST_ROWS, (g.panelH - (int) Math.round(26 * g.vScale)) / g.rowH);

		return g;
	}

	/**
	 * Lays out the player controls (transport row, volume, settings, audio toggle) and centers the
	 * whole stack vertically within panelH. Depends only on fields {@link #compute} already filled
	 * in, so the screen can call this before creating any widgets.
	 */
	public void computeCenterPanelGeometry() {
		int inner = centerW - 24;
		int rowStep = btnH + 6; // matches the original fixed-size spacing, scaled by the actual button height

		int topOffset = (int) Math.round(38 * vScale); // gap from panelTop to the transport row
		int audioStatusMarginTop = (int) Math.round(8 * vScale);
		audioStatusLineH = Math.max(9, (int) Math.round(9 * vScale));

		// Now that search has moved to the left panel, the player controls no longer fill panelH
		// on their own -- center the whole block vertically instead of leaving the leftover space
		// stranded at the bottom.
		int contentH = topOffset + rowStep * 3 + btnH + audioStatusMarginTop + audioStatusLineH * 2;
		centerContentOffsetY = Math.max(0, (panelH - contentH - (int) Math.round(10 * vScale)) / 2);

		// Transport row: centered on centerMidX -- the same center point the progress bar above it
		// uses -- so the three transport buttons line up visually with the bar above them.
		transportY = panelTop + centerContentOffsetY + topOffset;
		btnW = Math.max(28, (inner - 12) / 3);
		startX = centerMidX - (btnW * 3 + 12) / 2;

		sliderY = transportY + rowStep;
		audioW = (int) (inner * 0.87);

		settingsY = sliderY + rowStep;
		settingsW = (int) (inner * 0.6);

		audioToggleY = settingsY + rowStep;
		// Status line(s) drawn by CenterPanelRenderer, just below the toggle button. A small top
		// margin keeps it from crowding the button, and room is reserved for up to two lines since
		// the "select device" message can be too long to fit on one line at narrow widths.
		audioStatusY = audioToggleY + btnH + audioStatusMarginTop;
	}

	/**
	 * Lays out the left panel's Search/Library tabs, header (search bar or nothing, depending on
	 * mode), and the row budget for whatever's left below down to the panel's bottom border.
	 *
	 * @param searchMode true if the Search tab is active (shows the search bar + optional button),
	 *                    false for Library (no header row below the tabs)
	 */
	public void computeLeftPanelGeometry(boolean searchMode) {
		int listInnerW = leftW - 20;

		tabY = panelTop + (int) Math.round(8 * vScale);
		tabH = Math.max(14, (int) Math.round(14 * vScale));
		tabW = (listInnerW - 4) / 2;
		belowTabsY = tabY + tabH + (int) Math.round(6 * vScale);

		if (searchMode) {
			searchBtnW = Math.max(44, (int) Math.round(50 * vScale));
			searchFieldW = showSearchButton ? Math.max(60, listInnerW - searchBtnW - 6) : listInnerW;
			leftListY = belowTabsY + btnH + (int) Math.round(8 * vScale);
		} else {
			leftListY = belowTabsY;
		}

		// Exact row budget for whatever's actually left below the header down to the panel's
		// bottom border (rather than an approximation), so rows can never spill past it.
		int leftListBottomY = panelTop + panelH - (int) Math.round(6 * vScale);
		leftMaxRows = Math.max(MIN_LIST_ROWS, (leftListBottomY - leftListY) / rowH);

		listLeftX = leftX + 10;
		leftListLabelBudget = listInnerW - 10;

		listRightX = rightX + 10;
		rightListY = panelTop + (int) Math.round(26 * vScale);
	}
}
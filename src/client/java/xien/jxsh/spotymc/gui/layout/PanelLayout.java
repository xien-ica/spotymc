package xien.jxsh.spotymc.gui.layout;

/**
 * Computed geometry for the F12 overlay's three side-by-side panels (search/library, player,
 * queue). Panel sizes are a fraction of the current window, clamped to sane min/max bounds, so
 * the overlay grows and shrinks with the game window instead of staying a fixed size.
 * <p>
 * {@link #compute(int, int)} fills in everything that only depends on the window size. A few
 * fields further down depend on which left-panel header is showing (search bar vs. library
 * tabs) or how the center controls stack vertically -- the screen fills those in directly, once
 * {@code init()} knows that, rather than this class guessing at it.
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

	// --- filled in by the screen's initLeftPanel(), once the active header (search bar vs. tabs) is known ---
	public int listLeftX, listRightX, leftListY, rightListY;
	public int leftListLabelBudget; // pixel width available for left-panel row/status text
	public int leftMaxRows;

	// --- filled in by the screen's initCenterPanel(), once the control stack's offset is known ---
	public int centerContentOffsetY; // vertical offset that centers the player controls within panelH
	public int audioStatusY;
	public int audioStatusLineH;

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

		// leftMaxRows is set by initLeftPanel() instead, once the tab/search-bar header height
		// (which varies by mode) is actually known -- this fixed formula used to under-count that
		// header, letting rows overflow past the panel's bottom border.
		g.rightMaxRows = Math.max(MIN_LIST_ROWS, (g.panelH - (int) Math.round(26 * g.vScale)) / g.rowH);

		return g;
	}
}
package xien.jxsh.spotymc.gui.layout;

/**
 * Geometry and layout constants for {@link xien.jxsh.spotymc.gui.HudSettingsScreen}.
 * Mirrors the role of {@link PanelLayout} for the main controls screen: pure numbers,
 * no widgets or side-effects.
 */
public final class HudSettingsLayout {

    public static final int PANEL_ALPHA = 0xB0000000;
    public static final int PANEL_WIDTH = 320;
    public static final int NOMINAL_PANEL_HEIGHT = 350;
    public static final int MIN_PANEL_HEIGHT = 220;
    public static final int STATUS_MAX_LINES = 3;
    public static final int STATUS_LINE_HEIGHT = 10;
    public static final int WIDGET_HEIGHT = 20;
    public static final int CONTENT_TOP_PADDING = 8;
    public static final int CONTENT_BOTTOM_PADDING = 8;
    public static final int HEADER_HEIGHT = 34;
    public static final int FOOTER_HEIGHT = 38;
    public static final int SCROLLBAR_WIDTH = 4;
    public static final int SCROLLBAR_MARGIN = 6;
    public static final int SCROLL_STEP_PX = 18;

    public final int panelTop;
    public final int panelHeight;
    public final int viewportTop;
    public final int viewportBottom;
    public final int viewportHeight;
    public final int centerX;

    private HudSettingsLayout(int panelTop, int panelHeight, int centerX) {
        this.panelTop = panelTop;
        this.panelHeight = panelHeight;
        this.centerX = centerX;
        this.viewportTop = panelTop + HEADER_HEIGHT;
        this.viewportBottom = panelTop + panelHeight - FOOTER_HEIGHT;
        this.viewportHeight = Math.max(WIDGET_HEIGHT, viewportBottom - viewportTop);
    }

    /**
     * Computes panel geometry for the current window size, shrinking below the nominal
     * height when the window / GUI scale leaves insufficient vertical room.
     */
    public static HudSettingsLayout compute(int screenWidth, int screenHeight) {
        int available = Math.max(MIN_PANEL_HEIGHT, screenHeight - 8);
        int panelHeight = Math.min(NOMINAL_PANEL_HEIGHT, available);
        int panelTop = Math.max(4, (screenHeight - panelHeight) / 2);
        return new HudSettingsLayout(panelTop, panelHeight, screenWidth / 2);
    }

    public int panelLeft(int screenWidth) {
        return screenWidth / 2 - PANEL_WIDTH / 2;
    }

    public int panelRight(int screenWidth) {
        return screenWidth / 2 + PANEL_WIDTH / 2;
    }

    public int backButtonY() {
        return panelTop + panelHeight - 30;
    }
}
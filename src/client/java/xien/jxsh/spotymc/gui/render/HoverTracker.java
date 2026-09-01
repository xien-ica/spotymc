package xien.jxsh.spotymc.gui.render;

/**
 * Tracks how long the mouse has continuously hovered the *same* row across frames, so a row that
 * just became hovered can hold still for a beat before its marquee starts, instead of picking up
 * mid-scroll or mid-pause at whatever phase the wall clock happens to be at.
 * One instance covers a single scrollable list -- {@link LeftPanelRenderer} and
 * {@link QueuePanelRenderer} each get their own, owned by the screen and passed in each frame,
 * since the renderers themselves are static/stateless.
 */
public final class HoverTracker {

    private static final int NONE = -1;

    private int hoveredKey = NONE;
    private long hoverStartMs;

    /**
     * Call once per frame with whichever row (identified by its index in the full list) is
     * hovered this frame, or a negative value (both callers already use -1) if nothing is.
     *
     * @return how long (ms) that row has been continuously hovered, or -1 if nothing's hovered
     *         this frame.
     */
    public long update(int keyThisFrame, long nowMs) {
        if (keyThisFrame < 0) {
            hoveredKey = NONE;
            return -1;
        }
        if (keyThisFrame != hoveredKey) {
            hoveredKey = keyThisFrame;
            hoverStartMs = nowMs;
        }
        return nowMs - hoverStartMs;
    }
}
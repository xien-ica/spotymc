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

    private Object hoveredKey;
    private long hoverStartMs;

    /**
     * Call once per frame with whichever row (identified by any stable, equals()-able key -- e.g.
     * its index in the full list) is hovered this frame, or {@code null} if nothing is.
     *
     * @return how long (ms) that row has been continuously hovered, or -1 if nothing's hovered
     *         this frame.
     */
    public long update(Object keyThisFrame, long nowMs) {
        if (keyThisFrame == null) {
            hoveredKey = null;
            return -1;
        }
        if (!keyThisFrame.equals(hoveredKey)) {
            hoveredKey = keyThisFrame;
            hoverStartMs = nowMs;
        }
        return nowMs - hoverStartMs;
    }
}
package xien.jxsh.spotymc.gui.layout;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.input.MouseButtonEvent;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Owns content-local coordinate scrolling for a fixed viewport: registers widgets with a
 * local Y (measured from the top of the scrollable content), re-derives their real screen Y
 * whenever the offset changes, and culls anything that would only be partially visible so
 * nothing overlaps the header or footer chrome.
 * <p>
 * Also tracks scrollbar geometry for drawing and thumb dragging. Non-widget content (notes,
 * status lines, confirm text) can query {@link #screenYFor(int)} to decide whether they are
 * currently in view.
 */
public final class ScrollablePanel {

    private final List<ScrollEntry> entries = new ArrayList<>();
    private int contentHeight;
    private int scrollOffset;
    private int viewportTop;
    private int viewportBottom;
    private int viewportHeight;

    // Scrollbar geometry (recomputed by applyScroll)
    private int scrollbarX;
    private int scrollbarTrackTop;
    private int scrollbarTrackHeight;
    private int scrollbarThumbY;
    private int scrollbarThumbHeight;
    private boolean scrollbarDragging;

    public void clear() {
        entries.clear();
        contentHeight = 0;
        scrollOffset = 0;
        scrollbarDragging = false;
    }

    public void setViewport(int top, int bottom) {
        this.viewportTop = top;
        this.viewportBottom = bottom;
        this.viewportHeight = Math.max(HudSettingsLayout.WIDGET_HEIGHT, bottom - top);
    }

    public void setContentHeight(int height) {
        this.contentHeight = height;
        clampOffset();
    }

    public int contentHeight() {
        return contentHeight;
    }

    public int scrollOffset() {
        return scrollOffset;
    }

    public int maxScroll() {
        return Math.max(0, contentHeight - viewportHeight);
    }

    public void add(AbstractWidget widget, int localY) {
        entries.add(new ScrollEntry(widget, localY));
    }

    /**
     * Converts a content-local Y into the corresponding screen Y after the current scroll
     * offset is applied. Callers still need to decide visibility themselves for multi-line
     * text blocks.
     */
    public int screenYFor(int localY) {
        return viewportTop + localY - scrollOffset;
    }

    /**
     * True when a block of {@code blockHeight} pixels starting at {@code localY} fits fully
     * inside the viewport.
     */
    public boolean isFullyVisible(int localY, int blockHeight) {
        int screenY = screenYFor(localY);
        return screenY >= viewportTop && screenY + blockHeight <= viewportBottom;
    }

    public void applyScroll() {
        for (ScrollEntry entry : entries) {
            int screenY = screenYFor(entry.localY);
            boolean visible = screenY >= viewportTop
                    && screenY + HudSettingsLayout.WIDGET_HEIGHT <= viewportBottom;
            entry.widget.setY(screenY);
            entry.widget.visible = visible;
        }

        int max = maxScroll();
        // Caller supplies the right edge of the panel so the scrollbar sits inside it.
        // We store the last values used for hit-testing; geometry is refreshed every apply.
        if (max > 0) {
            double visibleFraction = viewportHeight / (double) contentHeight;
            scrollbarThumbHeight = Math.max(12, (int) Math.round(viewportHeight * visibleFraction));
            int travel = Math.max(1, scrollbarTrackHeight - scrollbarThumbHeight);
            scrollbarThumbY = scrollbarTrackTop + (int) Math.round(travel * (scrollOffset / (double) max));
        } else {
            scrollbarThumbHeight = scrollbarTrackHeight;
            scrollbarThumbY = scrollbarTrackTop;
        }
    }

    /**
     * Must be called after {@link #setViewport} (and after the panel's right edge is known)
     * so the scrollbar track sits flush with the right side of the panel.
     */
    public void setScrollbarGeometry(int panelRight) {
        scrollbarX = panelRight - HudSettingsLayout.SCROLLBAR_MARGIN - HudSettingsLayout.SCROLLBAR_WIDTH;
        scrollbarTrackTop = viewportTop;
        scrollbarTrackHeight = viewportHeight;
        applyScroll(); // recompute thumb against the new track
    }

    public void scrollBy(int deltaPx) {
        scrollOffset = Math.clamp(scrollOffset + deltaPx, 0, maxScroll());
        applyScroll();
    }

    public void setScrollOffset(int offset) {
        scrollOffset = Math.clamp(offset, 0, maxScroll());
        applyScroll();
    }

    private void clampOffset() {
        scrollOffset = Math.clamp(scrollOffset, 0, maxScroll());
    }

    // --- Input -------------------------------------------------------------------------------

    public boolean mouseScrolled(double scrollY) {
        if (maxScroll() <= 0) return false;
        scrollBy((int) Math.round(-scrollY * HudSettingsLayout.SCROLL_STEP_PX));
        return true;
    }

    public boolean mouseClicked(MouseButtonEvent event) {
        if (event.button() == 0 && isOverThumb(event.x(), event.y())) {
            scrollbarDragging = true;
            return true;
        }
        return false;
    }

    public boolean mouseDragged(@NonNull MouseButtonEvent event) {
        if (!scrollbarDragging) return false;
        int max = maxScroll();
        if (max > 0) {
            int travel = Math.max(1, scrollbarTrackHeight - scrollbarThumbHeight);
            double t = (event.y() - scrollbarTrackTop - scrollbarThumbHeight / 2.0) / travel;
            scrollOffset = (int) Math.round(Math.clamp(t, 0, 1) * max);
            applyScroll();
        }
        return true;
    }

    public boolean mouseReleased() {
        if (!scrollbarDragging) return false;
        scrollbarDragging = false;
        return true;
    }

    private boolean isOverThumb(double mouseX, double mouseY) {
        return maxScroll() > 0
                && mouseX >= scrollbarX && mouseX <= scrollbarX + HudSettingsLayout.SCROLLBAR_WIDTH
                && mouseY >= scrollbarThumbY && mouseY <= scrollbarThumbY + scrollbarThumbHeight;
    }

    // --- Drawing -----------------------------------------------------------------------------

    public void renderScrollbar(GuiGraphicsExtractor graphics) {
        if (maxScroll() <= 0) return;
        graphics.fill(scrollbarX, scrollbarTrackTop,
                scrollbarX + HudSettingsLayout.SCROLLBAR_WIDTH,
                scrollbarTrackTop + scrollbarTrackHeight, 0x20FFFFFF);
        graphics.fill(scrollbarX, scrollbarThumbY,
                scrollbarX + HudSettingsLayout.SCROLLBAR_WIDTH,
                scrollbarThumbY + scrollbarThumbHeight,
                scrollbarDragging ? 0x60FFFFFF : 0xD0FFFFFF);
    }

    // --- Internal ----------------------------------------------------------------------------

    private static final class ScrollEntry {
        final AbstractWidget widget;
        final int localY;

        ScrollEntry(AbstractWidget widget, int localY) {
            this.widget = widget;
            this.localY = localY;
        }
    }
}
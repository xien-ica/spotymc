package xien.jxsh.spotymc.gui.layout;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.Collections;
import java.util.List;

/**
 * A multi-line text block that lives in content-local coordinates and is only drawn when it
 * fits fully inside the {@link ScrollablePanel} viewport. Used for the install note, the
 * survival title-pin note, the install-confirm heading/body, etc.
 */
public final class ScrollTextBlock {

    public static final ScrollTextBlock EMPTY = new ScrollTextBlock(-1, Collections.emptyList(), 0, false);

    private final int localY;
    private final List<String> lines;
    private final int color;
    private final boolean shadow;

    public ScrollTextBlock(int localY, List<String> lines, int color, boolean shadow) {
        this.localY = localY;
        this.lines = lines == null ? Collections.emptyList() : List.copyOf(lines);
        this.color = color;
        this.shadow = shadow;
    }

    public boolean isPresent() {
        return localY >= 0 && !lines.isEmpty();
    }

    public int localY() {
        return localY;
    }

    public int blockHeight() {
        return lines.size() * HudSettingsLayout.STATUS_LINE_HEIGHT;
    }

    /**
     * Draws the block centred horizontally if it is fully visible in the panel.
     * No-op when absent or scrolled out of view.
     */
    public void render(GuiGraphicsExtractor graphics, Font font, ScrollablePanel scroll, int screenCenterX) {
        if (!isPresent()) return;
        if (!scroll.isFullyVisible(localY, blockHeight())) return;

        int y = scroll.screenYFor(localY);
        for (String line : lines) {
            int lineWidth = font.width(line);
            graphics.text(font, line, screenCenterX - lineWidth / 2, y, color, shadow);
            y += HudSettingsLayout.STATUS_LINE_HEIGHT;
        }
    }
}
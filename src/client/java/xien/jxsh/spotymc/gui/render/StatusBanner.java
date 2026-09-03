package xien.jxsh.spotymc.gui.render;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;

/**
 * Owns the action-outcome banner text (errors, "Connected!", etc.) shown at the bottom of the
 * center panel: when it was set, auto-clearing it a few seconds later, and caching its wrapped
 * lines so extractRenderState() doesn't re-wrap the same text on every single frame it's shown.
 */
public final class StatusBanner {

    private static final long DURATION_MS = 3000;
    private static final int LINE_H = 10;

    private String message = "";
    private long setAtMs = 0;

    private String lastWrapped = null;
    private int lastWrapMaxW = -1;
    private List<String> cachedLines = List.of();

    public void set(String msg) {
        this.message = msg;
        this.setAtMs = System.currentTimeMillis();
    }

    public void clear() {
        this.message = "";
    }

    public boolean isEmpty() {
        return message.isEmpty();
    }

    /** Call once per frame before rendering -- auto-dismisses the banner once it's aged out. */
    public void tick() {
        if (!message.isEmpty() && System.currentTimeMillis() - setAtMs >= DURATION_MS) {
            message = "";
        }
    }

    /** @param bottomY the y at which the *last* line should end, i.e. the anchor used when the banner is a single line */
    public void render(GuiGraphicsExtractor graphics, Font font, int centerMidX, int wrapW, int bottomY) {
        if (message.isEmpty()) return;
        if (!message.equals(lastWrapped) || wrapW != lastWrapMaxW) {
            lastWrapped = message;
            lastWrapMaxW = wrapW;
            cachedLines = TextLayout.wrapText(font, message, wrapW);
        }
        int y = bottomY - (cachedLines.size() - 1) * LINE_H;
        for (String line : cachedLines) {
            DrawUtil.drawCentered(graphics, font, line, centerMidX, y, 0xFFFF5555);
            y += LINE_H;
        }
    }
}
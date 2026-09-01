package xien.jxsh.spotymc.gui.render;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Small shared drawing helpers used across the overlay's panels. */
public final class DrawUtil {

	private DrawUtil() {}

	public static void drawCentered(GuiGraphicsExtractor graphics, Font font, String text, int centerX, int y, int argbColor) {
		int textWidth = font.width(text);
		graphics.text(font, text, centerX - textWidth / 2, y, argbColor, true);
	}

	/** Fills a panel background and draws a 1px border around it. */
	public static void drawPanel(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int bg, int border) {
		graphics.fill(x, y, x + w, y + h, bg);
		graphics.fill(x, y, x + w, y + 1, border);
		graphics.fill(x, y + h - 1, x + w, y + h, border);
		graphics.fill(x, y, x + 1, y + h, border);
		graphics.fill(x + w - 1, y, x + w, y + h, border);
	}
}
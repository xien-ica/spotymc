package xien.jxsh.spotymc.gui.render;

import net.minecraft.client.gui.Font;

import java.util.ArrayList;
import java.util.List;

/** Pixel-width-aware text helpers, since labels need to scale with the panel size rather than a fixed char count. */
public final class TextLayout {

	private TextLayout() {}

	/** Trims text to fit a pixel width (rather than a fixed char count) so labels scale with panel size. */
	public static String fitText(Font font, String s, int maxWidthPx) {
		if (font.width(s) <= maxWidthPx) return s;
		String ellipsis = "...";
		int ellipsisW = font.width(ellipsis);
		int budget = maxWidthPx - ellipsisW;
		if (budget <= 0) return ellipsis;

		// Longest prefix whose width fits under budget. Width only grows as the prefix grows, so
		// binary-searching the cut point is exact (same result the old one-char-at-a-time scan
		// produced) but needs O(log n) width measurements instead of O(n).
		int lo = 0, hi = s.length();
		while (lo < hi) {
			int mid = (lo + hi + 1) >>> 1;
			if (font.width(s.substring(0, mid)) <= budget) {
				lo = mid;
			} else {
				hi = mid - 1;
			}
		}
		return s.substring(0, lo) + ellipsis;
	}

	/** Wraps text onto as many lines as needed (breaking on spaces) so it fits a pixel width. */
	public static List<String> wrapText(Font font, String s, int maxWidthPx) {
		String[] words = s.split(" ");
		List<String> lines = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		for (String word : words) {
			String candidate = current.isEmpty() ? word : current + " " + word;
			if (font.width(candidate) > maxWidthPx && !current.isEmpty()) {
				lines.add(current.toString());
				current = new StringBuilder(word);
			} else {
				current = new StringBuilder(candidate);
			}
		}
		if (!current.isEmpty()) lines.add(current.toString());
		return lines;
	}

	/**
	 * X-offset (px) used to scroll a hovered row's text (or an auto-marquee title).
	 * Returns 0 when the text fits, so no scrolling is needed.
	 * -
	 * If the text is too long, it waits for an initial hold, then scrolls left with
	 * smooth ease-in-out motion, pauses at the end, and repeats with a shorter pause
	 * on subsequent laps.
	 * -
	 * The scroll is based on {@code hoverElapsedMs} (or wall time since a title changed),
	 * so every row/title starts from the beginning when its identity changes.
	 * -
	 * Callers should draw the full text at {@code (originalX - offset)}.
	 * The existing scissor rect will clip the text correctly.
	 */
	public static int marqueeOffset(long hoverElapsedMs, int textWidthPx, int maxWidthPx) {
		int overflow = textWidthPx - maxWidthPx;
		if (overflow <= 0) return 0;

		// How long a freshly-hovered row (or new title) sits still before the marquee kicks in.
		final long initialHoldMs = 850;
		// Shorter pause on each subsequent lap, once it's already been established the text scrolls.
		final long loopPauseMs = 700;
		final int pxPerSecond = 30;

		if (hoverElapsedMs < initialHoldMs) return 0;

		long scrollMs = Math.round(overflow / (double) pxPerSecond * 1000);
		long cycle = loopPauseMs * 2 + scrollMs;

		long t = (hoverElapsedMs - initialHoldMs) % cycle;
		if (t < loopPauseMs) return 0;
		if (t < loopPauseMs + scrollMs) {
			double p = (t - loopPauseMs) / (double) scrollMs; // 0..1 linear
			// Smoothstep ease-in-out: slow start, accelerate, decelerate into the end pause.
			double eased = p * p * (3.0 - 2.0 * p);
			return (int) Math.round(eased * overflow);
		}
		return overflow;
	}
}
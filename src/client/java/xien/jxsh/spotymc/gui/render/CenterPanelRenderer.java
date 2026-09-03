package xien.jxsh.spotymc.gui.render;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import xien.jxsh.spotymc.PlaybackPoller;
import xien.jxsh.spotymc.api.PlaybackState;
import xien.jxsh.spotymc.config.ModConfig;
import xien.jxsh.spotymc.gui.layout.PanelLayout;

import java.util.List;

/** Draws the center panel's now-playing text, progress bar, and in-game audio status line(s). */
public final class CenterPanelRenderer {

	private static final int PROGRESS_BAR_HIT_PAD_Y = 4; // the bar itself is only 3px tall -- pad the grab area

	/** Tracks the last now-playing string so the auto-marquee can reset when the track changes. */
	private static String lastTitle = "";
	private static long titleMarqueeStartMs;

	// Audio-status text is usually unchanged from one frame to the next (it only flips on real
	// state transitions), so cache its word-wrap result keyed on the text + wrap width rather than
	// re-wrapping it every single frame.
	private static String lastAudioStatus = null;
	private static int lastAudioStatusMaxW = -1;
	private static List<String> cachedAudioStatusLines = List.of();

	private CenterPanelRenderer() {}

	/** Bounds of the progress bar as drawn this frame, kept by the screen for hit-testing clicks/drags. */
	public record ProgressBarBounds(int x, int y, int w) {
		public boolean isOverGrabArea(double mouseX, double mouseY) {
			return mouseX >= x && mouseX < x + w
					&& mouseY >= y - PROGRESS_BAR_HIT_PAD_Y && mouseY < y + 3 + PROGRESS_BAR_HIT_PAD_Y;
		}

		/** Maps a mouse x-coordinate onto a track position, clamped to these bounds. */
		public int progressMsForMouseX(double mouseX, int durationMs) {
			double ratio = (mouseX - x) / (double) w;
			ratio = Math.clamp(ratio, 0.0, 1.0);
			return (int) Math.round(ratio * durationMs);
		}
	}

	/**
	 * @param suppressAudioStatus true while the screen's own transient action-outcome banner
	 *                            (e.g. a play-click error) is being shown at the bottom of this
	 *                            same panel -- skips the persistent audio-status line so the two
	 *                            don't render on top of each other.
	 */
	public static ProgressBarBounds render(GuiGraphicsExtractor graphics, Font font, PanelLayout layout,
	                                       PlaybackPoller poller, int mouseX, int mouseY, boolean draggingProgress,
	                                       int dragPreviewProgressMs, boolean suppressAudioStatus) {
		PlaybackState state = poller.getState();

		// When the Web API tier is unusable (no/invalid Client ID), show why instead of a
		// blank "Nothing playing" -- audio (librespot) keeps running independently either way.
		String webApiIssue = poller.getWebApiDisabledReason();
		String nowPlaying = webApiIssue != null ? webApiIssue
				: state.trackId != null ? state.title + " — " + state.artists : "Nothing playing";
		int titleColor = webApiIssue != null ? 0xFFFFD966 : Theme.ACCENT; // same amber as the audio-status warning below
		int maxW = layout.centerW - 16;
		int titleY = layout.panelTop + layout.centerContentOffsetY + 6;
		int titleLeft = layout.centerMidX - maxW / 2;

		// Auto-marquee (not hover-based): reset the cycle whenever the title string changes so a
		// new track always starts from the left after the initial hold, same timing curve as the
		// row marquees in LeftPanelRenderer / QueuePanelRenderer.
		long nowMs = System.currentTimeMillis();
		if (!nowPlaying.equals(lastTitle)) {
			lastTitle = nowPlaying;
			titleMarqueeStartMs = nowMs;
		}
		int textW = font.width(nowPlaying);
		int offset = TextLayout.marqueeOffset(nowMs - titleMarqueeStartMs, textW, maxW);

		if (textW <= maxW) {
			DrawUtil.drawCentered(graphics, font, nowPlaying, layout.centerMidX, titleY, titleColor);
		} else {
			// Clip to the title viewport so the scrolling full string never paints outside the
			// center panel's content area (same idea as the list scissor rects).
			graphics.enableScissor(titleLeft, titleY - 1, titleLeft + maxW, titleY + 10);
			graphics.text(font, nowPlaying, titleLeft - offset, titleY, titleColor, true);
			graphics.disableScissor();
		}

		// Progress bar, interpolated locally between polls so it doesn't visibly stutter.
		// Draggable: click-drag anywhere on it to scrub, release to seek.
		int barX = layout.centerX + 12, barW = layout.centerW - 24, barY = layout.panelTop + layout.centerContentOffsetY + 19;
		ProgressBarBounds bounds = new ProgressBarBounds(barX, barY, barW);
		boolean seekable = state.trackId != null && state.durationMs > 0;
		boolean hovering = seekable && bounds.isOverGrabArea(mouseX, mouseY);

		int barH = draggingProgress || hovering ? 4 : 3; // thicken slightly to read as interactive
		int trackTop = barY - (barH - 3);
		graphics.fill(barX, trackTop, barX + barW, trackTop + barH, 0xFF303030);

		if (seekable) {
			int progress = draggingProgress ? dragPreviewProgressMs : state.estimatedProgressMs();
			int filled = (int) (barW * Math.min(1.0, progress / (double) state.durationMs));
			filled = Math.clamp(filled, 0, barW);
			int fillColor = draggingProgress ? 0xFFFFFFFF : Theme.ACCENT;
			graphics.fill(barX, trackTop, barX + filled, trackTop + barH, fillColor);

			// Handle: a small bright tick at the current play head, visible on hover/drag so the
			// bar reads as something you can grab, not just a static meter.
			if (draggingProgress || hovering) {
				int handleX = barX + filled;
				graphics.fill(Math.max(barX, handleX - 1), barY - 3, Math.min(barX + barW, handleX + 1), barY + 6, 0xFFFFFFFF);
			}

			String elapsed = formatTime(progress);
			String total = formatTime(state.durationMs);
			graphics.text(font, elapsed, barX, barY + 6, Theme.TEXT_DIM, false);
			int totalTextW = font.width(total);
			graphics.text(font, total, barX + barW - totalTextW, barY + 6, Theme.TEXT_DIM, false);
		}

		if (!suppressAudioStatus) {
			renderAudioStatus(graphics, font, layout, poller);
		}
		return bounds;
	}

	private static void renderAudioStatus(GuiGraphicsExtractor graphics, Font font, PanelLayout layout, PlaybackPoller poller) {
		ModConfig cfg = ModConfig.get();
		if (!cfg.librespotEnabled) return;

		String audioStatus;
		int color;
		if (!poller.isAudioRunning()) {
			String err = poller.getAudioError();
			audioStatus = err != null ? "⚠ " + err : "Starting librespot...";
			color = 0xFFFF5555;
		} else if (poller.isPlayingThroughMinecraft()) {
			audioStatus = "✓ Playing through Minecraft";
			color = 0xFF55FF55;
		} else {
			audioStatus = "⚠ Select \"" + cfg.librespotDeviceName + "\" in your Spotify app";
			color = 0xFFFFD966;
		}
		int wrapW = layout.centerW - 16;
		if (!audioStatus.equals(lastAudioStatus) || wrapW != lastAudioStatusMaxW) {
			lastAudioStatus = audioStatus;
			lastAudioStatusMaxW = wrapW;
			cachedAudioStatusLines = TextLayout.wrapText(font, audioStatus, wrapW);
		}
		int y = layout.audioStatusY;
		for (String line : cachedAudioStatusLines) {
			DrawUtil.drawCentered(graphics, font, line, layout.centerMidX, y, color);
			y += layout.audioStatusLineH;
		}
	}

	private static String formatTime(int ms) {
		int totalSeconds = Math.max(0, ms) / 1000;
		int minutes = totalSeconds / 60;
		int seconds = totalSeconds % 60;
		return minutes + ":" + (seconds < 10 ? "0" + seconds : String.valueOf(seconds));
	}
}
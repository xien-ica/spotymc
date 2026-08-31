package xien.jxsh.spotymc.gui.render;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import xien.jxsh.spotymc.PlaybackPoller;
import xien.jxsh.spotymc.api.PlaybackState;
import xien.jxsh.spotymc.gui.layout.PanelLayout;
import xien.jxsh.spotymc.gui.model.ClickableRowHit;
import xien.jxsh.spotymc.gui.model.RowKind;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the read-only queue panel: each entry is clickable plain text to skip straight to it.
 * Scrolls/clips and grows a scrollbar identically to {@link LeftPanelRenderer} once the queue
 * has more entries than fit, reusing its {@link LeftPanelRenderer.ScrollbarBounds} geometry type
 * since both panels' scrollbars behave the same way.
 */
public final class QueuePanelRenderer {

	private QueuePanelRenderer() {}

	/**
	 * Everything this frame's queue-panel render produced: clickable rows, optional scrollbar
	 * geometry, and the scroll offset actually used this frame (clamped to the current queue
	 * size/row budget) so the screen can keep its own scroll field in sync as the queue changes.
	 */
	public record RenderResult(List<ClickableRowHit> hits, LeftPanelRenderer.ScrollbarBounds scrollbar, int scrollOffset) {
		public static RenderResult hitsOnly(List<ClickableRowHit> hits, int scrollOffset) {
			return new RenderResult(hits, null, scrollOffset);
		}
	}

	public static RenderResult render(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY,
	                                  PanelLayout layout, PlaybackPoller poller, int scrollOffset, HoverTracker hoverTracker) {
		List<ClickableRowHit> hits = new ArrayList<>();
		graphics.text(font, "QUEUE", layout.listRightX, layout.panelTop + 8, Theme.ACCENT, false);

		List<PlaybackState.QueueItem> queue = poller.getQueue();
		if (queue.isEmpty()) {
			graphics.text(font, "Queue is empty", layout.listRightX, layout.rightListY, Theme.TEXT_DIM, false);
			hoverTracker.update(null, System.currentTimeMillis());
			return RenderResult.hitsOnly(hits, 0);
		}

		int maxScroll = Math.max(0, queue.size() - layout.rightMaxRows);
		scrollOffset = Math.clamp(scrollOffset, 0, maxScroll);

		int listBottomY = layout.panelTop + layout.panelH - (int) Math.round(6 * layout.vScale);
		boolean needsScrollbar = queue.size() > layout.rightMaxRows;
		int fullLabelBudget = layout.rightW - 20;
		int labelBudget = needsScrollbar ? fullLabelBudget - 6 : fullLabelBudget;
		int listRowH = layout.rowH - 1;

		int shown = Math.min(queue.size() - scrollOffset, layout.rightMaxRows);

		// Figure out which row (if any) is hovered before drawing -- same two-pass approach as
		// LeftPanelRenderer, keyed by real queue index so HoverTracker sees a stable identity.
		int hoveredQueueIndex = -1;
		for (int i = 0; i < shown; i++) {
			int rowY = layout.rightListY + i * layout.rowH;
			boolean hovered = mouseX >= layout.listRightX && mouseX < layout.listRightX + labelBudget
					&& mouseY >= rowY && mouseY < rowY + listRowH;
			if (hovered) {
				hoveredQueueIndex = scrollOffset + i;
				break;
			}
		}
		long now = System.currentTimeMillis();
		long hoverElapsedMs = hoverTracker.update(hoveredQueueIndex >= 0 ? hoveredQueueIndex : null, now);

		// Clip strictly to the list's content area -- and to the *narrowed* labelBudget (not
		// fullLabelBudget) when a scrollbar is showing, so a marquee-scrolling row can never paint
		// over the scrollbar track/thumb next to it. Same reasoning as LeftPanelRenderer.
		int scissorRightX = layout.listRightX + labelBudget + 2;
		graphics.enableScissor(layout.listRightX, layout.rightListY, scissorRightX, listBottomY);
		for (int i = 0; i < shown; i++) {
			int queueIndex = scrollOffset + i;
			PlaybackState.QueueItem q = queue.get(queueIndex);
			int rowY = layout.rightListY + i * layout.rowH;
			boolean hovered = queueIndex == hoveredQueueIndex;

			// Only the hovered row builds the full string + measures its width; every other row
			// keeps taking the cheap fitText path exactly as before.
			String label;
			int textX = layout.listRightX;
			if (hovered) {
				label = q.title() + " - " + q.artists();
				textX -= TextLayout.marqueeOffset(hoverElapsedMs, font.width(label), labelBudget);
			} else {
				label = TextLayout.fitText(font, q.title() + " - " + q.artists(), labelBudget);
			}
			graphics.text(font, label, textX, rowY, hovered ? Theme.ACCENT : Theme.TEXT_NORMAL, false);
			// index carries the row's real position in the full queue (not its on-screen slot),
			// since that's what's needed to skip to the right spot once the list can scroll.
			hits.add(new ClickableRowHit(layout.listRightX, rowY, labelBudget, listRowH,
					"spotify:track:" + q.id(), RowKind.QUEUE_TRACK, queueIndex));
		}
		graphics.disableScissor();

		if (!needsScrollbar) {
			return RenderResult.hitsOnly(hits, scrollOffset);
		}

		int trackX = layout.listRightX + fullLabelBudget - 2;
		int trackTop = layout.rightListY;
		int trackH = listBottomY - layout.rightListY;
		graphics.fill(trackX, trackTop, trackX + 3, trackTop + trackH, 0x40FFFFFF);

		int thumbH = Math.max(10, trackH * layout.rightMaxRows / queue.size());
		int thumbY = trackTop + (maxScroll == 0 ? 0 : (trackH - thumbH) * scrollOffset / maxScroll);
		graphics.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, 0xB0FFFFFF);

		return new RenderResult(hits, new LeftPanelRenderer.ScrollbarBounds(trackX, trackTop, trackH, thumbY, thumbH, maxScroll), scrollOffset);
	}
}
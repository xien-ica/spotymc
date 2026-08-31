package xien.jxsh.spotymc.gui.render;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import xien.jxsh.spotymc.gui.browse.BrowseController;
import xien.jxsh.spotymc.gui.layout.PanelLayout;
import xien.jxsh.spotymc.gui.model.ClickableRowHit;
import xien.jxsh.spotymc.gui.model.RowEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the left panel: either a "loading"/empty message, or a scrollable, clipped list of rows
 * (search results or library entries) with click hit-boxes and a scrollbar when there's more
 * content than fits. Search and Library tabs share this so they scroll/clip identically.
 */
public final class LeftPanelRenderer {

	private LeftPanelRenderer() {}

	/**
	 * Geometry of the scrollbar drawn alongside the row list, computed fresh each frame. Lets the
	 * screen hit-test clicks/drags against it the same way {@link CenterPanelRenderer.ProgressBarBounds}
	 * does for the progress bar. {@code maxScroll} is the highest valid scroll offset for the list
	 * this frame, so the screen can map a drag position back into an offset without recomputing it.
	 */
	public record ScrollbarBounds(int trackX, int trackTop, int trackH, int thumbY, int thumbH, int maxScroll) {
		// The visual track is only 3px wide -- widen the grabbable area a little so it's not a
		// pixel-hunt to grab, matching the same idea as the progress bar's vertical hit padding.
		private static final int HIT_PAD_X = 3;

		public boolean isOverThumb(double mouseX, double mouseY) {
			return mouseX >= trackX - HIT_PAD_X && mouseX < trackX + 3 + HIT_PAD_X
					&& mouseY >= thumbY && mouseY < thumbY + thumbH;
		}

		public boolean isOverTrack(double mouseX, double mouseY) {
			return mouseX >= trackX - HIT_PAD_X && mouseX < trackX + 3 + HIT_PAD_X
					&& mouseY >= trackTop && mouseY < trackTop + trackH;
		}

		/** Maps a candidate thumb-top y-coordinate onto a scroll offset, clamped to this list's range. */
		public int scrollOffsetForThumbTopY(double thumbTopY) {
			if (maxScroll <= 0) return 0;
			int travel = trackH - thumbH;
			if (travel <= 0) return 0;
			double ratio = (thumbTopY - trackTop) / (double) travel;
			ratio = Math.clamp(ratio, 0.0, 1.0);
			return (int) Math.round(ratio * maxScroll);
		}
	}

	/** Everything this frame's left-panel render produced: clickable rows plus optional scrollbar geometry. */
	public record RenderResult(List<ClickableRowHit> hits, ScrollbarBounds scrollbar) {
		public static RenderResult hitsOnly(List<ClickableRowHit> hits) {
			return new RenderResult(hits, null);
		}
	}

	/** Renders the left panel for the current frame and returns this frame's clickable row hit-boxes. */
	public static RenderResult render(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY,
	                                  PanelLayout layout, BrowseController browse, HoverTracker hoverTracker) {
		List<ClickableRowHit> hits = new ArrayList<>();

		if (browse.mode() == BrowseController.Mode.LIBRARY) {
			if (browse.isLoadingLibrary() && browse.hasLibraryLoaded()) {
				graphics.text(font, "Loading your library...", layout.listLeftX, layout.leftListY, Theme.TEXT_DIM, false);
				return RenderResult.hitsOnly(hits);
			}
			return renderRowList(graphics, font, mouseX, mouseY, layout, browse, browse.buildEntries(), "Nothing here yet", hits, hoverTracker);
		}

		if (!browse.hasSearchResults()) {
			int y = layout.leftListY;
			for (String line : TextLayout.wrapText(font, "Search for a song or playlist to get started", layout.leftListLabelBudget)) {
				graphics.text(font, line, layout.listLeftX, y, Theme.TEXT_DIM, false);
				y += layout.rowH;
			}
			return RenderResult.hitsOnly(hits);
		}

		return renderRowList(graphics, font, mouseX, mouseY, layout, browse, browse.buildEntries(), "No results found", hits, hoverTracker);
	}

	private static RenderResult renderRowList(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY,
	                                          PanelLayout layout, BrowseController browse, List<RowEntry> entries, String emptyMessage,
	                                          List<ClickableRowHit> hits, HoverTracker hoverTracker) {
		if (entries.isEmpty()) {
			graphics.text(font, TextLayout.fitText(font, emptyMessage, layout.leftListLabelBudget),
					layout.listLeftX, layout.leftListY, Theme.TEXT_DIM, false);
			hoverTracker.update(null, System.currentTimeMillis());
			return RenderResult.hitsOnly(hits);
		}

		browse.clampScroll(entries.size(), layout.leftMaxRows);
		int scrollOffset = browse.scrollOffset();

		int listBottomY = layout.panelTop + layout.panelH - (int) Math.round(6 * layout.vScale);
		int maxScroll = Math.max(0, entries.size() - layout.leftMaxRows);

		boolean needsScrollbar = entries.size() > layout.leftMaxRows;
		int labelBudget = needsScrollbar ? layout.leftListLabelBudget - 6 : layout.leftListLabelBudget;
		int listRowH = layout.rowH - 1;

		int shown = Math.min(entries.size() - scrollOffset, layout.leftMaxRows);

		// Figure out which row (if any) is hovered *before* drawing, keyed by its position in the
		// full entries list -- stable across frames as long as scrollOffset doesn't change mid-hover,
		// which is what lets HoverTracker tell "still the same row" apart from "a different row
		// happens to occupy this screen slot now".
		int hoveredEntryIndex = -1;
		for (int i = 0; i < shown; i++) {
			int rowY = layout.leftListY + i * layout.rowH;
			boolean hovered = mouseX >= layout.listLeftX && mouseX < layout.listLeftX + labelBudget
					&& mouseY >= rowY && mouseY < rowY + listRowH;
			if (hovered) {
				hoveredEntryIndex = scrollOffset + i;
				break; // only one row can be hovered by a single mouse position
			}
		}
		long now = System.currentTimeMillis();
		long hoverElapsedMs = hoverTracker.update(hoveredEntryIndex >= 0 ? hoveredEntryIndex : null, now);

		// Clip strictly to the list's content area -- and, when a scrollbar is showing, to the
		// *narrowed* labelBudget rather than the full one, so a marquee-scrolling row's untruncated
		// text can never paint over the scrollbar track/thumb next to it.
		int scissorRightX = layout.listLeftX + labelBudget + 2;
		graphics.enableScissor(layout.listLeftX, layout.leftListY, scissorRightX, listBottomY);
		for (int i = 0; i < shown; i++) {
			RowEntry e = entries.get(scrollOffset + i);
			int rowY = layout.leftListY + i * layout.rowH;
			boolean hovered = (scrollOffset + i) == hoveredEntryIndex;

			// Only the (at most one) hovered row pays for a full-width measurement + the
			// untruncated string; every other row still takes the cheap fitText path exactly as
			// before, so this doesn't add per-row cost to the common case.
			String text;
			int textX = layout.listLeftX;
			if (hovered) {
				text = e.label();
				textX -= TextLayout.marqueeOffset(hoverElapsedMs, font.width(text), labelBudget);
			} else {
				text = TextLayout.fitText(font, e.label(), labelBudget);
			}
			graphics.text(font, text, textX, rowY, hovered ? Theme.ACCENT : Theme.TEXT_NORMAL, false);
			hits.add(new ClickableRowHit(layout.listLeftX, rowY, labelBudget, listRowH, e.uri(), e.kind(), e.index()));
		}
		graphics.disableScissor();

		if (!needsScrollbar) {
			return RenderResult.hitsOnly(hits);
		}

		int trackX = layout.listLeftX + layout.leftListLabelBudget - 2;
		int trackTop = layout.leftListY;
		int trackH = listBottomY - layout.leftListY;
		graphics.fill(trackX, trackTop, trackX + 3, trackTop + trackH, 0x40FFFFFF);

		int thumbH = Math.max(10, trackH * layout.leftMaxRows / entries.size());
		int thumbY = trackTop + (maxScroll == 0 ? 0 : (trackH - thumbH) * scrollOffset / maxScroll);
		graphics.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, 0xB0FFFFFF);

		return new RenderResult(hits, new ScrollbarBounds(trackX, trackTop, trackH, thumbY, thumbH, maxScroll));
	}
}
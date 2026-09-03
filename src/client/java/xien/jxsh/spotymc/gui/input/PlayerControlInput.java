package xien.jxsh.spotymc.gui.input;

import org.jspecify.annotations.NonNull;
import xien.jxsh.spotymc.PlaybackPoller;
import xien.jxsh.spotymc.api.PlaybackState;
import xien.jxsh.spotymc.gui.async.ThrowingRunnable;
import xien.jxsh.spotymc.gui.browse.BrowseController;
import xien.jxsh.spotymc.gui.layout.PanelLayout;
import xien.jxsh.spotymc.gui.model.ClickableRowHit;
import xien.jxsh.spotymc.gui.render.CenterPanelRenderer;
import xien.jxsh.spotymc.gui.render.LeftPanelRenderer;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.List;
import java.util.function.Consumer;

/**
 * Owns mouse input for {@code PlayerControlScreen}'s three panels: progress-bar seeking, the two
 * scrollbars (left panel list, queue panel -- same {@link LeftPanelRenderer.ScrollbarBounds}
 * geometry, dragged independently), and click routing for queue-skip and search/library rows.
 * <p>
 * Bounds fields ({@link #setProgressBarBounds}, {@link #setScrollbarBounds},
 * {@link #setQueueScrollbarBounds}) are recomputed every frame by the renderers alongside their
 * drawing. The screen is responsible for calling those setters from extractRenderState() right
 * after each renderer runs, so click/drag handlers here always hit-test against the current
 * frame's geometry; this class only owns what happens once a click/drag lands on them.
 */
public final class PlayerControlInput {

    private boolean mouseButtonDown = false;

    private CenterPanelRenderer.ProgressBarBounds progressBarBounds;
    private boolean draggingProgress = false;
    private int dragPreviewProgressMs = 0;

    private LeftPanelRenderer.ScrollbarBounds scrollbarBounds;
    private boolean draggingScrollbar = false;
    // Offset from the thumb's top edge to the mouse position when the drag started, so the thumb
    // doesn't jump to be centered under the cursor the instant you grab it partway down its length.
    private double scrollbarDragGrabOffsetY = 0;

    // The queue has no BrowseController-style owner, so the scroll offset just lives here;
    // QueuePanelRenderer clamps it each frame (queue length changes as tracks play) and hands back
    // the value actually used, which the screen stores back via setQueueScrollOffset().
    private int queueScrollOffset = 0;
    private LeftPanelRenderer.ScrollbarBounds queueScrollbarBounds;
    private boolean draggingQueueScrollbar = false;
    private double queueScrollbarDragGrabOffsetY = 0;

    // --- Frame-fresh geometry, set by the screen right after each panel renders ---

    public void setProgressBarBounds(CenterPanelRenderer.ProgressBarBounds bounds) {
        this.progressBarBounds = bounds;
    }

    public void setScrollbarBounds(LeftPanelRenderer.ScrollbarBounds bounds) {
        this.scrollbarBounds = bounds;
    }

    public void setQueueScrollbarBounds(LeftPanelRenderer.ScrollbarBounds bounds) {
        this.queueScrollbarBounds = bounds;
    }

    // --- State the screen/renderers need to read back ---

    public boolean isMouseButtonDown() {
        return mouseButtonDown;
    }

    public boolean isDraggingProgress() {
        return draggingProgress;
    }

    public int dragPreviewProgressMs() {
        return dragPreviewProgressMs;
    }

    public int queueScrollOffset() {
        return queueScrollOffset;
    }

    public void setQueueScrollOffset(int offset) {
        this.queueScrollOffset = offset;
    }

    // --- Mouse handlers, called straight from the Screen overrides ---

    public boolean mouseClicked(@NonNull MouseButtonEvent event, PlaybackPoller poller, BrowseController browse,
                                PanelLayout layout, List<ClickableRowHit> queueHits, List<ClickableRowHit> searchHits,
                                Consumer<ThrowingRunnable> runAsync) {
        mouseButtonDown = true;
        double mouseX = event.x();
        double mouseY = event.y();

        PlaybackState state = poller.getState();
        boolean seekable = state.trackId != null && state.durationMs > 0;
        if (seekable && progressBarBounds != null && progressBarBounds.isOverGrabArea(mouseX, mouseY)) {
            draggingProgress = true;
            dragPreviewProgressMs = progressBarBounds.progressMsForMouseX(mouseX, state.durationMs);
            return true;
        }

        if (scrollbarBounds != null) {
            if (scrollbarBounds.isOverThumb(mouseX, mouseY)) {
                draggingScrollbar = true;
                scrollbarDragGrabOffsetY = mouseY - scrollbarBounds.thumbY();
                return true;
            }
            if (scrollbarBounds.isOverTrack(mouseX, mouseY)) {
                // Clicked the track outside the thumb -- jump so the thumb is centered under the
                // cursor, then let mouseDragged carry on from there if the button stays held.
                draggingScrollbar = true;
                scrollbarDragGrabOffsetY = scrollbarBounds.thumbH() / 2.0;
                int targetTop = (int) Math.round(mouseY - scrollbarDragGrabOffsetY);
                int newOffset = scrollbarBounds.scrollOffsetForThumbTopY(targetTop);
                browse.setScrollOffset(newOffset, browse.buildEntries().size(), layout.leftMaxRows);
                return true;
            }
        }

        if (queueScrollbarBounds != null) {
            if (queueScrollbarBounds.isOverThumb(mouseX, mouseY)) {
                draggingQueueScrollbar = true;
                queueScrollbarDragGrabOffsetY = mouseY - queueScrollbarBounds.thumbY();
                return true;
            }
            if (queueScrollbarBounds.isOverTrack(mouseX, mouseY)) {
                draggingQueueScrollbar = true;
                queueScrollbarDragGrabOffsetY = queueScrollbarBounds.thumbH() / 2.0;
                int targetTop = (int) Math.round(mouseY - queueScrollbarDragGrabOffsetY);
                queueScrollOffset = queueScrollbarBounds.scrollOffsetForThumbTopY(targetTop);
                return true;
            }
        }

        for (ClickableRowHit hit : queueHits) {
            if (hit.contains(mouseX, mouseY)) {
                int index = hit.index();
                // Instant feedback: drop it (and everything ahead of it) from the visible queue
                // right away rather than waiting ~1-2s for Spotify to register the change and the
                // next poll to notice. Skipping through next() repeatedly -- rather than starting
                // a brand-new single-track playback context via playTrack() -- keeps the rest of
                // the queue intact instead of wiping it out.
                poller.optimisticAdvanceQueue(index + 1);
                runAsync.accept(() -> poller.api.skipToQueueIndex(index));
                return true;
            }
        }
        for (ClickableRowHit hit : searchHits) {
            if (hit.contains(mouseX, mouseY)) {
                switch (hit.kind()) {
                    case TRACK -> {
                        String uri = hit.uri();
                        runAsync.accept(() -> poller.api.playTrack(uri));
                    }
                    case PLAYLIST -> {
                        String uri = hit.uri();
                        runAsync.accept(() -> poller.api.playPlaylist(uri));
                    }
                    case LIKED_SONGS -> browse.openLikedSongs();
                    case BACK -> browse.closeLikedSongs();
                    case LIKED_SONG_TRACK -> {
                        int idx = hit.index();
                        List<PlaybackState.Track> liked = browse.likedSongs();
                        if (liked != null && idx >= 0 && idx < liked.size()) {
                            // Start at the clicked song and carry the rest of Liked Songs forward
                            // as the queue, the same way clicking a song in a real playlist does.
                            List<String> uris = liked.subList(idx, liked.size()).stream()
                                    .map(PlaybackState.Track::uri).toList();
                            runAsync.accept(() -> poller.api.playTracks(uris));
                        }
                    }
                    default -> {}
                }
                return true;
            }
        }
        return false;
    }

    public boolean mouseDragged(@NonNull MouseButtonEvent event, PlaybackPoller poller, BrowseController browse, PanelLayout layout) {
        if (draggingQueueScrollbar) {
            if (queueScrollbarBounds != null) {
                int targetTop = (int) Math.round(event.y() - queueScrollbarDragGrabOffsetY);
                queueScrollOffset = queueScrollbarBounds.scrollOffsetForThumbTopY(targetTop);
            }
            return true;
        }
        if (draggingScrollbar) {
            if (scrollbarBounds != null) {
                int targetTop = (int) Math.round(event.y() - scrollbarDragGrabOffsetY);
                int newOffset = scrollbarBounds.scrollOffsetForThumbTopY(targetTop);
                browse.setScrollOffset(newOffset, browse.buildEntries().size(), layout.leftMaxRows);
            }
            return true;
        }
        if (draggingProgress) {
            PlaybackState state = poller.getState();
            if (state.trackId == null || state.durationMs <= 0) {
                draggingProgress = false; // track ended/changed mid-drag -- bail out cleanly
                return true;
            }
            if (progressBarBounds != null) {
                dragPreviewProgressMs = progressBarBounds.progressMsForMouseX(event.x(), state.durationMs);
            }
            return true;
        }
        return false;
    }

    public boolean mouseReleased(@NonNull MouseButtonEvent event, PlaybackPoller poller, Consumer<ThrowingRunnable> runAsync) {
        mouseButtonDown = false;
        if (draggingQueueScrollbar) {
            draggingQueueScrollbar = false;
            return true;
        }
        if (draggingScrollbar) {
            draggingScrollbar = false;
            return true;
        }
        if (draggingProgress) {
            draggingProgress = false;
            int seekMs = dragPreviewProgressMs;
            // Reflect the jump immediately; the real seek call (and the fast-resync burst it
            // triggers) true it up against Spotify shortly after.
            poller.optimisticSeek(seekMs);
            runAsync.accept(() -> poller.api.seek(seekMs));
            return true;
        }
        return false;
    }

    /**
     * Scrolls whichever of the left panel's row list or the queue panel the mouse is over. Uses the
     * classic double-based mouseScrolled signature -- if this build's Screen has since moved scroll
     * input onto an event object (the way click/key input has been here), adjust the Screen
     * override to match and pass the values through unchanged.
     */
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY, PlaybackPoller poller,
                                 BrowseController browse, PanelLayout layout) {
        boolean overLeftPanel = mouseX >= layout.leftX && mouseX < layout.leftX + layout.leftW
                && mouseY >= layout.panelTop && mouseY < layout.panelTop + layout.panelH;
        int total = browse.buildEntries().size();
        if (overLeftPanel && total > layout.leftMaxRows) {
            int direction = scrollY > 0 ? -1 : (scrollY < 0 ? 1 : 0);
            browse.scrollBy(direction, total, layout.leftMaxRows);
            return true;
        }

        boolean overRightPanel = mouseX >= layout.rightX && mouseX < layout.rightX + layout.rightW
                && mouseY >= layout.panelTop && mouseY < layout.panelTop + layout.panelH;
        int queueTotal = poller.getQueue().size();
        if (overRightPanel && queueTotal > layout.rightMaxRows) {
            int direction = scrollY > 0 ? -1 : (scrollY < 0 ? 1 : 0);
            int maxScroll = Math.max(0, queueTotal - layout.rightMaxRows);
            queueScrollOffset = Math.clamp(queueScrollOffset + direction, 0, maxScroll);
            return true;
        }
        return false;
    }
}
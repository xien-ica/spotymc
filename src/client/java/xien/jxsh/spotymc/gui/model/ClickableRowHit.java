package xien.jxsh.spotymc.gui.model;

/**
 * Clickable hit-box for a single row rendered as plain text (not a button). {@code index} is
 * meaningful for QUEUE_TRACK (position in the queue) and LIKED_SONG_TRACK (position in the
 * liked-songs list), so a click can carry the rest of that list forward into playback rather
 * than just the one row that was clicked.
 */
public record ClickableRowHit(int x, int y, int w, int h, String uri, RowKind kind, int index) {
	public boolean contains(double mouseX, double mouseY) {
		return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
	}
}

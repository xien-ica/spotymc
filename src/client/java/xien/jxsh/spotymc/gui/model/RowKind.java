package xien.jxsh.spotymc.gui.model;

/**
 * What a clicked row should do. LIKED_SONGS has no single uri -- clicking it drills into the
 * list of individual liked songs (see LIKED_SONG_TRACK) rather than playing anything itself.
 * BACK returns from that drill-down to the library root.
 */
public enum RowKind {
	TRACK, PLAYLIST, LIKED_SONGS, LIKED_SONG_TRACK, BACK, QUEUE_TRACK
}

package xien.jxsh.spotymc.lyrics;

/**
 * Immutable timed lyric line. Kept as a record so the poller can hand out a single
 * reference that the HUD simply reads -- no copying or rebuilding on the render path.
 */
public record LyricLine(int timeMs, String text) {
}
package xien.jxsh.spotymc.lyrics;

/**
 * Formats a lyric line's display text according to the "Notes" HUD setting -- when enabled,
 * wraps the line as "♪ text ♪"; when disabled (the default), the line is shown as-is.
 * <p>
 * Pure function. The only allocation occurs when notes are enabled (one new String). Callers
 * that invoke this every frame (LyricsHud) already cache the final display text when the
 * underlying lyric line and notes setting are stable, so the allocation is rare in practice.
 */
public final class LyricsFormat {
    private static final String NOTE = "♪";
    private static final String NOTE_PREFIX = NOTE + " ";
    private static final String NOTE_SUFFIX = " " + NOTE;

    private LyricsFormat() {}

    public static String display(String text, boolean notesEnabled) {
        if (text == null || text.isBlank()) return text;
        return notesEnabled ? NOTE_PREFIX + text + NOTE_SUFFIX : text;
    }
}
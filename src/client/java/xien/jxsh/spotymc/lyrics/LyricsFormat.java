package xien.jxsh.spotymc.lyrics;

/**
 * Formats a lyric line's display text according to the "Notes" HUD setting -- when enabled,
 * wraps the line as "\u266A text \u266A" (e.g. "\u266A kahel na langit~ \u266A"); when disabled
 * (the default), the line is shown as-is.
 */
public final class LyricsFormat {
    private static final String NOTE = "\u266A";

    private LyricsFormat() {}

    public static String display(String text, boolean notesEnabled) {
        if (text == null || text.isBlank()) return text;
        return notesEnabled ? NOTE + " " + text + " " + NOTE : text;
    }
}

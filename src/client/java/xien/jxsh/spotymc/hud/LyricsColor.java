package xien.jxsh.spotymc.hud;

/**
 * A small, vanilla-friendly color palette for the lyric HUD line. These are the same 16 fixed
 * RGB values Minecraft has always used for its chat/sign/book text colors (§f, §e, etc.),
 * hardcoded here rather than pulled from {@code ChatFormatting} at runtime since that class's
 * color-lookup method has moved around between versions.
 * <p>
 * All values are compile-time constants; {@link #argb()} is a pure bit operation and
 * {@link #byName(String)} is an enum lookup. Both are safe to call every frame, but callers
 * that already know the name is stable (e.g. LyricsHud) should still cache the result.
 */
public enum LyricsColor {
    WHITE("White", 0xFFFFFF),
    YELLOW("Yellow", 0xFFFF55),
    AQUA("Aqua", 0x55FFFF),
    GREEN("Green", 0x55FF55),
    GOLD("Gold", 0xFFAA00),
    LIGHT_PURPLE("Pink", 0xFF55FF),
    RED("Red", 0xFF5555),
    GRAY("Gray", 0xAAAAAA);

    public final String label;
    private final int rgb;

    LyricsColor(String label, int rgb) {
        this.label = label;
        this.rgb = rgb;
    }

    /** The vanilla ARGB color for this entry, ready to hand straight to graphics.text(...). */
    public int argb() {
        return 0xFF000000 | rgb;
    }

    /** Next entry in the palette, wrapping around -- used by the settings screen's cycle button. */
    public LyricsColor next() {
        LyricsColor[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

    /** Looks up a palette entry by its enum name (as stored in ModConfig), falling back to WHITE. */
    public static LyricsColor byName(String name) {
        if (name == null) return WHITE;
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return WHITE;
        }
    }
}
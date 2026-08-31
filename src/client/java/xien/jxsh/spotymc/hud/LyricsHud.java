package xien.jxsh.spotymc.hud;

import xien.jxsh.spotymc.PlaybackPoller;
import xien.jxsh.spotymc.api.PlaybackState;
import xien.jxsh.spotymc.config.ModConfig;
import xien.jxsh.spotymc.lyrics.LyricLine;
import xien.jxsh.spotymc.lyrics.LyricsFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Font;
import net.minecraft.world.level.GameType;

public class LyricsHud {
    // Vanilla's own hotbar decorations, which our lines need to clear on top of the user's
    // configurable gap -- otherwise the gap sliders mean different actual distances depending on
    // gamemode/HUD state, and in survival our text ends up drawn over the health/hunger/XP rows.
    // Not pixel-perfect (vanilla's exact heights/padding aren't exposed to us), just generous
    // enough that our text never lands on top of them.
    private static final int HOTBAR_HEIGHT = 22; // vanilla hotbar row itself
    private static final int ITEM_NAME_POPUP_RESERVE = 10; // the "Cooked Steak"-style popup on slot change; can flash in any gamemode
    private static final int SURVIVAL_ROW_RESERVE = 10; // health/hunger/armor row -- hidden in creative/spectator
    private static final int XP_BAR_RESERVE = 9; // XP bar (or mount jump bar) -- also hidden in creative/spectator

    // In survival/adventure, the space above the hotbar is already crowded (health, hunger, armor,
    // XP), so the title/artist line moves to the top of the screen instead of fighting for room down
    // there -- with a slightly smaller scale so it reads as a secondary status line, not a banner.
    // Lyrics stay above the hotbar either way; only this line relocates.
    private static final int SURVIVAL_TITLE_TOP_MARGIN = 10;
    private static final float SURVIVAL_TITLE_FONT_SCALE = 0.8f;

    // In creative/spectator, the title stays above the hotbar. The item-name popup still needs
    // clearing there, but it sits closer to the hotbar than lyrics' full ITEM_NAME_POPUP_RESERVE
    // implies -- using that same reserve put the title right on top of the popup instead of above
    // it, so the title gets its own smaller value here.
    private static final int CREATIVE_TITLE_POPUP_CLEARANCE = 4;

    private final PlaybackPoller poller;

    public LyricsHud(PlaybackPoller poller) {
        this.poller = poller;
    }

    public void render(GuiGraphicsExtractor graphics, Minecraft client) {
        if (poller.auth.isLoggedIn()) return;

        PlaybackState playback = poller.getState();
        if (playback.trackId == null) return;

        ModConfig cfg = ModConfig.get();
        boolean showTitle = cfg.nowPlayingEnabled;
        boolean showLyrics = cfg.lyricsEnabled;
        if (!showTitle && !showLyrics) return; // nothing to draw at all

        int screenWidth = client.getWindow().getGuiScaledWidth();
        int screenHeight = client.getWindow().getGuiScaledHeight();
        int hotbarTop = screenHeight - HOTBAR_HEIGHT;
        boolean survivalHud = showsSurvivalHudElements(client);
        int vanillaReserve = vanillaHudReserve(survivalHud);
        int lyricY = hotbarTop - vanillaReserve - cfg.effectiveLyricsHudGap(survivalHud) - 10;

        if (showTitle) {
            String titleLine = playback.title + "  —  " + playback.artists; // em dash separator
            if (survivalHud) {
                drawScaledCentered(graphics, client.font, titleLine, screenWidth / 2, SURVIVAL_TITLE_TOP_MARGIN,
                        0xFFFFFFFF, SURVIVAL_TITLE_FONT_SCALE);
            } else {
                int titleY = hotbarTop - CREATIVE_TITLE_POPUP_CLEARANCE - cfg.effectiveTitleArtistHudGap() - 10;
                drawCentered(graphics, client.font, titleLine, screenWidth / 2, titleY, 0xFFFFFFFF, true);
            }
        }

        if (showLyrics) {
            LyricLine line = poller.getCurrentLyricLine();
            boolean hasLine = line != null;
            String lyricText = hasLine ? line.text() : (poller.hasLyricsForCurrentTrack() ? "" : "♫ no synced lyrics found");
            if (!lyricText.isEmpty()) {
                // Only wrap real lyric lines in notes -- not the "no synced lyrics found" placeholder,
                // which already carries its own note glyph.
                String displayText = hasLine ? LyricsFormat.display(lyricText, cfg.lyricsNotesEnabled) : lyricText;
                int color = hasLine ? LyricsColor.byName(cfg.lyricsColorName).argb() : 0xFFFFD966;
                drawScaledCentered(graphics, client.font, displayText, screenWidth / 2, lyricY, color, cfg.lyricsFontScale);
            }
        }
    }

    /**
     * How much extra vertical space above the hotbar our lyrics line needs to clear this frame,
     * based on which vanilla decorations are actually showing. Creative and spectator hide the
     * health/hunger/armor row and the XP bar entirely, so only the item-name popup reserve applies
     * there; survival and adventure show all of it, so lyrics need to sit further up or they'll be
     * drawn right through the hearts and hunger icons.
     */
    private int vanillaHudReserve(boolean survivalHud) {
        int reserve = ITEM_NAME_POPUP_RESERVE;
        if (survivalHud) {
            reserve += SURVIVAL_ROW_RESERVE + XP_BAR_RESERVE;
        }
        return reserve;
    }

    private boolean showsSurvivalHudElements(Minecraft client) {
        if (client.gameMode == null) return true; // be conservative if we can't tell -- reserve the space
        GameType mode = client.gameMode.getPlayerMode();
        return mode == GameType.SURVIVAL || mode == GameType.ADVENTURE;
    }

    private void drawCentered(GuiGraphicsExtractor graphics, Font font, String text, int centerX, int y,
                              int argbColor, boolean shadow) {
        int textWidth = font.width(text);
        graphics.text(font, text, centerX - textWidth / 2, y, argbColor, shadow);
    }

    /**
     * Like {@link #drawCentered}, but scales the text around its own center point via the pose
     * stack -- vanilla's {@link Font} has no built-in size parameter, so this is the standard way
     * to draw bitmap-font text larger/smaller without a custom font renderer.
     */
    private void drawScaledCentered(GuiGraphicsExtractor graphics, Font font, String text, int centerX, int y,
                                    int argbColor, float scale) {
        if (scale == 1.0f) {
            drawCentered(graphics, font, text, centerX, y, argbColor, true);
            return;
        }
        int textWidth = font.width(text);
        graphics.pose().pushMatrix();
        graphics.pose().translate(centerX, y);
        graphics.pose().scale(scale, scale);
        graphics.text(font, text, -textWidth / 2, 0, argbColor, true);
        graphics.pose().popMatrix();
    }
}
package xien.jxsh.spotymc;

import xien.jxsh.spotymc.config.ModConfig;
import xien.jxsh.spotymc.gui.PlayerControlScreen;
import xien.jxsh.spotymc.hud.LyricsHud;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.resources.Identifier;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

/**
 * Client entry point. Registers the F12 overlay key, global Right-Ctrl+arrow hotkeys,
 * lifecycle hooks for the poller, and the lyrics HUD element.
 * <p>
 * The per-tick work ({@link #handleGlobalHotkeys}) is deliberately allocation-free and
 * runs at only 20 TPS, so the focus is on correct edge detection and not fighting text fields
 * rather than micro-optimising arithmetic.
 */
public class Spotymc implements ClientModInitializer {
    private static final String MOD_ID = "spotymc";
    public static final int TOGGLE_KEYCODE = GLFW.GLFW_KEY_F12;

    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath(MOD_ID, "general"));

    private final PlaybackPoller poller = new PlaybackPoller();
    private LyricsHud hud;
    private KeyMapping openControlsKey;

    // --- Global Right Ctrl + Arrow hotkeys (prev/next track, volume +-5%) ---
    // Each physical key gets its own KeyMapping so KeyMapping#isDown can be polled every tick --
    // vanilla KeyMapping only tracks a single physical key, so the "Right Ctrl held" state and
    // each arrow key are tracked as separate mappings and combined by hand.
    private KeyMapping rightCtrlKey;
    private KeyMapping prevTrackArrowKey;
    private KeyMapping nextTrackArrowKey;
    private KeyMapping volumeUpArrowKey;
    private KeyMapping volumeDownArrowKey;

    // Edge-detection flags: a held combo fires once on press (prev/next) or with key-repeat
    // behaviour (volume).
    private boolean prevTrackComboDown = false;
    private boolean nextTrackComboDown = false;
    private boolean volumeUpComboDown = false;
    private boolean volumeDownComboDown = false;

    // Volume key-repeat: fire immediately on press, then every REPEAT_INTERVAL_TICKS after
    // REPEAT_INITIAL_DELAY_TICKS. Counts ticks since the combo was last pressed so the interval
    // stays constant instead of drifting.
    private int volumeUpHeldTicks = 0;
    private int volumeDownHeldTicks = 0;
    private static final int REPEAT_INITIAL_DELAY_TICKS = 10; // 500 ms at 20 TPS
    private static final int REPEAT_INTERVAL_TICKS = 3;       // 150 ms between repeats once ramped up

    // Pause-music-with-game: only resume if *we* paused Spotify when the game paused,
    // so a manually-paused track stays paused after the player unpauses Minecraft.
    private boolean wasGamePaused = false;
    private boolean musicPausedByMod = false;

    @Override
    public void onInitializeClient() {
        hud = new LyricsHud(poller);

        openControlsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.spotymc.open_controls",
                InputConstants.Type.KEYSYM,
                TOGGLE_KEYCODE,
                CATEGORY
        ));

        rightCtrlKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.spotymc.modifier", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_CONTROL, CATEGORY));
        prevTrackArrowKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.spotymc.previous_track", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT, CATEGORY));
        nextTrackArrowKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.spotymc.next_track", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT, CATEGORY));
        volumeUpArrowKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.spotymc.volume_up", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UP, CATEGORY));
        volumeDownArrowKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.spotymc.volume_down", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_DOWN, CATEGORY));

        ClientLifecycleEvents.CLIENT_STARTED.register(_ -> poller.start());
        ClientLifecycleEvents.CLIENT_STOPPING.register(_ -> poller.stop());

        // Track world membership so maintainAudio() can tear librespot down on "Save and Quit
        // to Title" instead of leaving it streaming into an empty title screen.
        ClientPlayConnectionEvents.JOIN.register((_, _, _) -> poller.setInWorld(true));
        ClientPlayConnectionEvents.DISCONNECT.register((_, _) -> poller.setInWorld(false));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Only ever needs to handle opening -- consumeClick() simply won't fire while a
            // screen is already showing, so closing is handled directly in the screens' keyPressed.
            while (openControlsKey.consumeClick()) {
                if (client.gui.screen() == null) {
                    client.gui.setScreen(new PlayerControlScreen(poller));
                }
            }
            handleGlobalHotkeys(client);
            handlePauseMusicWithGame(client);
        });

        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(MOD_ID, "lyrics_hud"),
                (graphics, _) -> {
                    // Skip the entire HUD path while any screen is open (F12 overlay, inventory,
                    // chat, etc.) -- the lyrics are meant for the in-world view only.
                    if (Minecraft.getInstance().gui.screen() == null) {
                        hud.render(graphics, Minecraft.getInstance());
                    }
                });
    }

    /**
     * Polls the Right Ctrl + arrow-key combos every tick.
     * Skipped entirely while a text field has focus so we don't fight Ctrl+Left/Right word-jump
     * in EditBoxes. Runs whether or not the F12 screen is open.
     */
    private void handleGlobalHotkeys(Minecraft client) {
        if (isTypingInTextField(client)) {
            // Reset edge state so a combo held while typing doesn't fire the moment focus leaves.
            prevTrackComboDown = false;
            nextTrackComboDown = false;
            volumeUpComboDown = false;
            volumeDownComboDown = false;
            volumeUpHeldTicks = 0;
            volumeDownHeldTicks = 0;
            return;
        }

        boolean rightCtrl = rightCtrlKey.isDown();

        boolean prevTrackCombo = rightCtrl && prevTrackArrowKey.isDown();
        if (prevTrackCombo && !prevTrackComboDown) {
            poller.previousTrack();
        }
        prevTrackComboDown = prevTrackCombo;

        boolean nextTrackCombo = rightCtrl && nextTrackArrowKey.isDown();
        if (nextTrackCombo && !nextTrackComboDown) {
            poller.nextTrack();
        }
        nextTrackComboDown = nextTrackCombo;

        boolean volumeUpCombo = rightCtrl && volumeUpArrowKey.isDown();
        volumeUpHeldTicks = tickVolumeRepeat(volumeUpCombo, volumeUpComboDown, volumeUpHeldTicks, +5);
        volumeUpComboDown = volumeUpCombo;

        boolean volumeDownCombo = rightCtrl && volumeDownArrowKey.isDown();
        volumeDownHeldTicks = tickVolumeRepeat(volumeDownCombo, volumeDownComboDown, volumeDownHeldTicks, -5);
        volumeDownComboDown = volumeDownCombo;
    }

    /**
     * Advances one combo's held-tick counter and fires {@code poller.adjustVolume} on the
     * appropriate ticks to produce key-repeat behaviour.
     * @return the updated held-tick count for the caller to store back
     */
    private int tickVolumeRepeat(boolean comboDown, boolean wasComboDown, int heldTicks, int deltaPercent) {
        if (!comboDown) return 0;
        if (!wasComboDown) {
            // Rising edge -- fire once immediately.
            poller.adjustVolume(deltaPercent);
            return 0;
        }
        heldTicks++;
        if (heldTicks >= REPEAT_INITIAL_DELAY_TICKS
                && (heldTicks - REPEAT_INITIAL_DELAY_TICKS) % REPEAT_INTERVAL_TICKS == 0) {
            poller.adjustVolume(deltaPercent);
        }
        return heldTicks;
    }

    /** True while a text-entry widget (search box, chat, sign, anvil name field, etc.) has focus. */
    private boolean isTypingInTextField(Minecraft client) {
        return client.gui.screen() != null && client.gui.screen().getFocused() instanceof EditBox;
    }

    /**
     * When {@link ModConfig#pauseMusicWithGame} is on, pauses Spotify as the game enters a
     * paused state and resumes only if this mod was the one that paused it.
     * Uses rising/falling edges of {@link Minecraft#isPaused()} so we never spam the API.
     */
    private void handlePauseMusicWithGame(Minecraft client) {
        boolean paused = client.isPaused();
        if (!ModConfig.get().pauseMusicWithGame) {
            wasGamePaused = paused;
            musicPausedByMod = false;
            return;
        }
        if (paused && !wasGamePaused) {
            if (poller.getState().isPlaying) {
                poller.pausePlayback();
                musicPausedByMod = true;
            }
        } else if (!paused && wasGamePaused) {
            if (musicPausedByMod) {
                poller.resumePlayback();
                musicPausedByMod = false;
            }
        }
        wasGamePaused = paused;
    }
}
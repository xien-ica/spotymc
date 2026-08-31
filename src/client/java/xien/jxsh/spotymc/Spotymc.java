package xien.jxsh.spotymc;

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
    // each arrow key are tracked as separate mappings and combined by hand, rather than trying
    // to express the combo as one binding.
    private KeyMapping rightCtrlKey;
    private KeyMapping prevTrackArrowKey;
    private KeyMapping nextTrackArrowKey;
    private KeyMapping volumeUpArrowKey;
    private KeyMapping volumeDownArrowKey;

    // Tracks whether each combo was already down last tick, so a held combo fires its action
    // once on press instead of repeating every tick it's held.
    private boolean prevTrackComboDown = false;
    private boolean nextTrackComboDown = false;
    private boolean volumeUpComboDown = false;
    private boolean volumeDownComboDown = false;

    @Override
    public void onInitializeClient() {
        hud = new LyricsHud(poller);

        openControlsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.spotymc.open_controls",
                InputConstants.Type.KEYSYM,
                TOGGLE_KEYCODE,
                CATEGORY
        ));

        // The modifier and each arrow get registered as ordinary (rebindable) key mappings --
        // only the combination of "Right Ctrl down" + "an arrow just pressed" actually triggers
        // an action, handled in handleGlobalHotkeys() below.
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

        // "Save and Quit to Title" (and disconnecting from a server) doesn't stop librespot on
        // its own -- it just keeps streaming into a title screen no one's listening to. Track
        // world membership explicitly so maintainAudio() knows to actually tear it down instead
        // of only tearing it down when the whole game closes.
        ClientPlayConnectionEvents.JOIN.register((_, _, _) -> poller.setInWorld(true));
        ClientPlayConnectionEvents.DISCONNECT.register((_, _) -> poller.setInWorld(false));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Only ever needs to handle opening -- consumeClick() simply won't fire while a screen
            // is already showing, so closing is handled directly in the screens' keyPressed instead.
            while (openControlsKey.consumeClick()) {
                if (client.gui.screen() == null) {
                    client.gui.setScreen(new PlayerControlScreen(poller));
                }
            }
            handleGlobalHotkeys(client);
        });

        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(MOD_ID, "lyrics_hud"),
                (graphics, _) -> {
                    Minecraft client = Minecraft.getInstance();
                    if (client.gui.screen() == null) {
                        hud.render(graphics, client);
                    }
                });
    }

    /**
     * Polls the Right Ctrl + arrow-key combos every tick via their KeyMapping#isDown() states:
     * Right Ctrl + Left/Right = previous/next track, Right Ctrl + Up/Down = volume +-5%.
     * Skipped entirely while a text field (search box, chat, sign editor, etc.) has focus, so
     * this doesn't fight the Ctrl+Left/Right word-jump vanilla's EditBox already does with
     * either Ctrl key. Runs regardless of whether the F12 screen is open or the player is just
     * in the world, so the hotkeys work anywhere.
     */
    private void handleGlobalHotkeys(Minecraft client) {
        if (isTypingInTextField(client)) {
            prevTrackComboDown = false;
            nextTrackComboDown = false;
            volumeUpComboDown = false;
            volumeDownComboDown = false;
            return;
        }

        boolean rightCtrl = rightCtrlKey.isDown();

        boolean prevTrackCombo = rightCtrl && prevTrackArrowKey.isDown();
        if (prevTrackCombo && !prevTrackComboDown) poller.previousTrack();
        prevTrackComboDown = prevTrackCombo;

        boolean nextTrackCombo = rightCtrl && nextTrackArrowKey.isDown();
        if (nextTrackCombo && !nextTrackComboDown) poller.nextTrack();
        nextTrackComboDown = nextTrackCombo;

        boolean volumeUpCombo = rightCtrl && volumeUpArrowKey.isDown();
        if (volumeUpCombo && !volumeUpComboDown) poller.adjustVolume(5);
        volumeUpComboDown = volumeUpCombo;

        boolean volumeDownCombo = rightCtrl && volumeDownArrowKey.isDown();
        if (volumeDownCombo && !volumeDownComboDown) poller.adjustVolume(-5);
        volumeDownComboDown = volumeDownCombo;
    }

    /** True while a text-entry widget (search box, chat, sign, anvil name field, etc.) has focus. */
    private boolean isTypingInTextField(Minecraft client) {
        return client.gui.screen() != null && client.gui.screen().getFocused() instanceof EditBox;
    }
}
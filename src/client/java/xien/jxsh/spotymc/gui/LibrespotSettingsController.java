package xien.jxsh.spotymc.gui;

import xien.jxsh.spotymc.PlaybackPoller;
import xien.jxsh.spotymc.audio.LibrespotInstaller;
import xien.jxsh.spotymc.config.ModConfig;
import xien.jxsh.spotymc.gui.layout.HudSettingsLayout;
import xien.jxsh.spotymc.gui.layout.ScrollTextBlock;
import xien.jxsh.spotymc.gui.render.TextLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Owns the librespot install / uninstall / consent UI and the associated async actions for
 * {@link HudSettingsScreen}. The screen only calls {@link #buildSection} or
 * {@link #buildConfirmView} during layout and forwards button presses; all state, listeners,
 * and status reactions live here.
 */
public final class LibrespotSettingsController {

    /** Rebuild the whole settings screen (clears widgets + re-runs init). */
    private final Runnable rebuild;
    /** Show a transient status line (already hops to the render thread). */
    private final Consumer<String> setStatus;
    /** Register a scrollable widget with the parent screen + panel. */
    private final BiConsumer<AbstractWidget, Integer> addScrollable;
    private final PlaybackPoller poller;
    private final ExecutorService bgExecutor;
    private final Font font;

    private boolean showInstallConfirm = false;
    private boolean uninstalling = false;

    private Button cancelInstallButton;
    private Button uninstallLibrespotButton;

    // Text blocks produced by the last build* call; the screen renders them.
    private ScrollTextBlock installNote = ScrollTextBlock.EMPTY;
    private ScrollTextBlock confirmHeading = ScrollTextBlock.EMPTY;
    private ScrollTextBlock confirmBody = ScrollTextBlock.EMPTY;

    // Bound listener instances so the screen can unregister the exact same references.
    private final Consumer<String> progressListener;
    private final Consumer<LibrespotInstaller.InstallResult> completeListener;

    public LibrespotSettingsController(Runnable rebuild,
                                       Consumer<String> setStatus,
                                       BiConsumer<AbstractWidget, Integer> addScrollable,
                                       PlaybackPoller poller,
                                       ExecutorService bgExecutor,
                                       Font font) {
        this.rebuild = rebuild;
        this.setStatus = setStatus;
        this.addScrollable = addScrollable;
        this.poller = poller;
        this.bgExecutor = bgExecutor;
        this.font = font;
        this.progressListener = setStatus;
        this.completeListener = this::onInstallComplete;
    }

    public Consumer<String> progressListener() {
        return progressListener;
    }

    public Consumer<LibrespotInstaller.InstallResult> completeListener() {
        return completeListener;
    }

    public boolean isShowingConfirm() {
        return showInstallConfirm;
    }

    public ScrollTextBlock installNote() {
        return installNote;
    }

    public ScrollTextBlock confirmHeading() {
        return confirmHeading;
    }

    public ScrollTextBlock confirmBody() {
        return confirmBody;
    }

    /** Clears text blocks that belong only to the normal settings view. */
    public void clearNormalText() {
        installNote = ScrollTextBlock.EMPTY;
        cancelInstallButton = null;
        uninstallLibrespotButton = null;
    }

    /** Clears text blocks that belong only to the confirm view. */
    public void clearConfirmText() {
        confirmHeading = ScrollTextBlock.EMPTY;
        confirmBody = ScrollTextBlock.EMPTY;
    }

    // =========================================================================================
    // Layout: normal settings (install / uninstall row)
    // =========================================================================================

    /**
     * Adds the librespot install / cancel / uninstall controls and the accompanying note.
     * Returns the content-local Y for the row after this section.
     */
    public int buildSection(int centerX, int curY, ModConfig cfg) {
        clearConfirmText();
        boolean installRunning = LibrespotInstaller.isInstalling();
        boolean installed = LibrespotInstaller.isInstalled(cfg.librespotPath);

        if (installRunning) {
            Button installing = Button.builder(Component.literal("Installing librespot..."), _ -> {})
                    .bounds(centerX - 130, 0, 190, 20).build();
            installing.active = false;
            addScrollable.accept(installing, curY);

            cancelInstallButton = Button.builder(Component.literal("Cancel"), _ -> cancelInstall())
                    .bounds(centerX + 65, 0, 65, 20).build();
            addScrollable.accept(cancelInstallButton, curY);
            uninstallLibrespotButton = null;

            List<String> lines = wrapText(installNoteText(), HudSettingsLayout.PANEL_WIDTH - 40, 3);
            installNote = new ScrollTextBlock(curY + 22, lines, 0xFF888888, false);
            return curY + 38 + (lines.size() - 1) * HudSettingsLayout.STATUS_LINE_HEIGHT;
        }

        if (!installed) {
            Button install = Button.builder(Component.literal("Install librespot"), _ -> openConfirm())
                    .bounds(centerX - 130, 0, 260, 20).build();
            addScrollable.accept(install, curY);
            cancelInstallButton = null;
            uninstallLibrespotButton = null;

            List<String> lines = wrapText(installNoteText(), HudSettingsLayout.PANEL_WIDTH - 40, 3);
            installNote = new ScrollTextBlock(curY + 22, lines, 0xFF888888, false);
            return curY + 38 + (lines.size() - 1) * HudSettingsLayout.STATUS_LINE_HEIGHT;
        }

        // Installed
        cancelInstallButton = null;
        installNote = ScrollTextBlock.EMPTY;
        uninstallLibrespotButton = Button.builder(
                        Component.literal(uninstalling ? "Uninstalling..." : "Uninstall librespot"),
                        _ -> uninstall())
                .bounds(centerX - 65, 0, 130, 20).build();
        uninstallLibrespotButton.active = !uninstalling;
        addScrollable.accept(uninstallLibrespotButton, curY);
        return curY + 26;
    }

    // =========================================================================================
    // Layout: install-consent confirmation view
    // =========================================================================================

    /**
     * Lays out the consent screen (heading + body + Install/Cancel). Returns the content-local
     * Y after the buttons so the caller can place the status area.
     */
    public int buildConfirmView(int centerX, int curY, ModConfig cfg) {
        clearNormalText();

        List<String> heading = wrapText("Install librespot?", HudSettingsLayout.PANEL_WIDTH - 40, 1);
        confirmHeading = new ScrollTextBlock(curY, heading, 0xFFFFFFFF, true);
        curY += heading.size() * HudSettingsLayout.STATUS_LINE_HEIGHT + 10;

        String body = "This downloads a small (~20 MB) file to let Minecraft play Spotify. "
                + "The file is checked to make sure it's safe. After that, open Spotify and choose \""
                + cfg.librespotDeviceName + "\" as your playback device.";
        List<String> bodyLines = wrapText(body, HudSettingsLayout.PANEL_WIDTH - 40, 8);
        confirmBody = new ScrollTextBlock(curY, bodyLines, 0xFFAAAAAA, false);
        curY += bodyLines.size() * HudSettingsLayout.STATUS_LINE_HEIGHT + 16;

        Button confirmInstall = Button.builder(Component.literal("Install librespot"), _ -> confirmInstall())
                .bounds(centerX - 130, 0, 120, 20).build();
        addScrollable.accept(confirmInstall, curY);

        Button confirmCancel = Button.builder(Component.literal("Cancel"), _ -> cancelConfirm())
                .bounds(centerX + 10, 0, 120, 20).build();
        addScrollable.accept(confirmCancel, curY);

        return curY + 26;
    }

    // =========================================================================================
    // Actions
    // =========================================================================================

    private void openConfirm() {
        showInstallConfirm = true;
        rebuild.run();
    }

    private void confirmInstall() {
        showInstallConfirm = false;
        setStatus.accept("");
        LibrespotInstaller.installLibrespotAsync(progressListener, completeListener);
        rebuild.run();
    }

    /** Cancels the consent view and returns to the normal settings layout. */
    public void cancelConfirm() {
        showInstallConfirm = false;
        rebuild.run();
    }

    private void cancelInstall() {
        if (cancelInstallButton != null) cancelInstallButton.active = false;
        LibrespotInstaller.cancelInstall();
    }

    private void onInstallComplete(LibrespotInstaller.InstallResult result) {
        Minecraft.getInstance().execute(() -> {
            if (result.success()) {
                ModConfig c = ModConfig.get();
                c.librespotPath = result.path();
                c.librespotEnabled = true;
                c.save();
                setStatus.accept("librespot installed! Open Spotify and select \"" + c.librespotDeviceName + "\".");
            } else {
                setStatus.accept(result.message());
            }
            rebuild.run();
        });
    }

    private void uninstall() {
        if (uninstalling) return;
        uninstalling = true;
        setStatus.accept("");
        if (uninstallLibrespotButton != null) {
            uninstallLibrespotButton.active = false;
            uninstallLibrespotButton.setMessage(Component.literal("Uninstalling..."));
        }
        ModConfig c = ModConfig.get();
        c.librespotEnabled = false;
        c.save();
        poller.stopAudioAndWait().thenRunAsync(() -> {
            LibrespotInstaller.InstallResult result =
                    LibrespotInstaller.uninstallLibrespot(c.librespotPath, setStatus);
            if (result.success()) {
                c.librespotPath = "";
                c.save();
                setStatus.accept("librespot uninstalled.");
            } else {
                setStatus.accept("Uninstall failed: " + result.message());
            }
        }, bgExecutor).whenComplete((_, _) -> {
            uninstalling = false;
            Minecraft.getInstance().execute(rebuild);
        });
    }

    // =========================================================================================
    // Helpers
    // =========================================================================================

    private static String installNoteText() {
        return "A small, verified file (" + LibrespotInstaller.estimateLabel() + ") will be downloaded "
                + "to enable Spotify playback in-game. It usually takes just 10-30 seconds, and no "
                + "additional setup is required.";
    }

    private List<String> wrapText(String s, int maxWidthPx, int maxLines) {
        List<String> full = TextLayout.wrapText(font, s, maxWidthPx);
        if (full.size() <= maxLines) return full;
        java.util.ArrayList<String> truncated = new java.util.ArrayList<>(full.subList(0, maxLines));
        int last = maxLines - 1;
        truncated.set(last, TextLayout.fitText(font, truncated.get(last), maxWidthPx));
        return truncated;
    }
}
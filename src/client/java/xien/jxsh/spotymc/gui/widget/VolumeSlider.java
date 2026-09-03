package xien.jxsh.spotymc.gui.widget;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;

/**
 * Volume slider that can also be updated from outside a user drag -- e.g. reflecting a volume
 * change made from the Spotify app or another Connect device, picked up on the owning screen's
 * next poll. {@link #setExternalVolume} only moves the knob and relabels it; it deliberately
 * skips {@link #applyValue()} so syncing doesn't turn around and fire another setVolume() call
 * back at Spotify.
 */
public final class VolumeSlider extends AbstractSliderButton {

    private final IntConsumer onApply;

    /**
     * @param onApply called with the new volume percent (0-100) whenever the user changes the
     *                slider's value themselves (never called from {@link #setExternalVolume})
     */
    public VolumeSlider(int x, int y, int w, int h, double initialValue, IntConsumer onApply) {
        super(x, y, w, h, Component.literal(label(initialValue)), initialValue);
        this.onApply = onApply;
    }

    @Override
    protected void updateMessage() {
        setMessage(Component.literal(label(this.value)));
    }

    @Override
    protected void applyValue() {
        onApply.accept((int) Math.round(this.value * 100));
    }

    public void setExternalVolume(int percent) {
        this.value = Math.clamp(percent / 100.0, 0.0, 1.0);
        updateMessage();
    }

    private static String label(double v) {
        return "Volume: " + Math.round(v * 100) + "%";
    }
}
package xien.jxsh.spotymc.gui.widget;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

import java.util.function.DoubleConsumer;

/**
 * Lyrics font-scale slider snapped to 0.25 steps between 0.75× and 2.0×.
 * Saves on every drag so the HUD updates live.
 */
public final class FontScaleSlider extends AbstractSliderButton {

    public static final double MIN_SCALE = 0.75;
    public static final double MAX_SCALE = 2.0;
    public static final double STEP = 0.25;

    private final DoubleConsumer onChange;

    public FontScaleSlider(int x, int y, int width, int height, double currentScale, DoubleConsumer onChange) {
        super(x, y, width, height, Component.literal(label(clampAndSnap(currentScale))),
                (clampAndSnap(currentScale) - MIN_SCALE) / (MAX_SCALE - MIN_SCALE));
        this.onChange = onChange;
    }

    @Override
    protected void updateMessage() {
        setMessage(Component.literal(label(scaleFromValue(this.value))));
    }

    @Override
    protected void applyValue() {
        onChange.accept(scaleFromValue(this.value));
    }

    private static double clampAndSnap(double scale) {
        double clamped = Math.clamp(scale, MIN_SCALE, MAX_SCALE);
        return MIN_SCALE + Math.round((clamped - MIN_SCALE) / STEP) * STEP;
    }

    private static double scaleFromValue(double value) {
        double raw = MIN_SCALE + value * (MAX_SCALE - MIN_SCALE);
        return clampAndSnap(raw);
    }

    private static String label(double scale) {
        return "Lyrics Font Size: " + trimTrailingZero(scale) + "x";
    }

    /** Formats 1.0 / 1.25 / 1.5 etc. without a trailing ".00" / ".0" for whole values. */
    private static String trimTrailingZero(double scale) {
        String s = String.format(java.util.Locale.ROOT, "%.2f", scale);
        if (s.endsWith("00")) return s.substring(0, s.length() - 3);
        if (s.endsWith("0")) return s.substring(0, s.length() - 1);
        return s;
    }
}
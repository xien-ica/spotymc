package xien.jxsh.spotymc.gui.widget;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;

/**
 * 0–150 px “height above hotbar” slider used by the HUD settings screen.
 * Saves on every drag so the HUD can update live.
 */
public final class GapSlider extends AbstractSliderButton {

    public static final int MIN_GAP = 0;
    public static final int MAX_GAP = 150;

    private final String label;
    private final IntConsumer onChange;

    public GapSlider(int x, int y, int width, int height, String label, int currentGap, IntConsumer onChange) {
        super(x, y, width, height, Component.literal(sliderLabel(label, clamp(currentGap))),
                (clamp(currentGap) - MIN_GAP) / (double) (MAX_GAP - MIN_GAP));
        this.label = label;
        this.onChange = onChange;
    }

    @Override
    protected void updateMessage() {
        setMessage(Component.literal(sliderLabel(label, gapFromValue(this.value))));
    }

    @Override
    protected void applyValue() {
        onChange.accept(gapFromValue(this.value));
    }

    private static int clamp(int gap) {
        return Math.clamp(gap, MIN_GAP, MAX_GAP);
    }

    private static int gapFromValue(double value) {
        return MIN_GAP + (int) Math.round(value * (MAX_GAP - MIN_GAP));
    }

    private static String sliderLabel(String label, int gap) {
        return label + ": " + gap + "px above hotbar";
    }
}
package xien.jxsh.spotymc.gui.async;

/** A runnable that's allowed to throw -- used for API calls fired off in the background. */
@FunctionalInterface
public interface ThrowingRunnable {
    void run() throws Exception;
}
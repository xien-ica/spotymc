package xien.jxsh.spotymc.gui.async;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Owns the single background executor a screen uses for its async actions (play/pause/skip/
 * search/etc), and the boilerplate around running something on it.
 * <p>
 * Previously each action on {@code PlayerControlScreen} spun up its own
 * {@code Executors.newSingleThreadExecutor()}, which leaks a non-daemon thread per click and
 * never gets shut down -- this reuses a small fixed pool for the life of the screen instead.
 * <p>
 * Lifecycle note: construct this once per screen instance and only {@link #shutdown()} it on a
 * real close, not on every {@code removed()} -- a screen that gets reused as a {@code parent}
 * (e.g. swapped out for a child Settings screen and handed back) must keep the same executor
 * usable for its whole life. See {@code PlayerControlScreen.removed()} / {@code onClose()}.
 */
public final class ScreenActionRunner {

    private final ExecutorService executor;
    private final Runnable onActionSucceeded;
    private final Consumer<String> onActionFailed;

    /**
     * @param threadNamePrefix   name for the daemon threads backing this pool, for easier profiling
     * @param onActionSucceeded  called after a successful action, on the executor thread (e.g. to
     *                           trigger a fast-resync poll)
     * @param onActionFailed     called with the failure message when an action throws, on the
     *                           executor thread (e.g. to surface a status banner)
     */
    public ScreenActionRunner(String threadNamePrefix, Runnable onActionSucceeded, Consumer<String> onActionFailed) {
        this.onActionSucceeded = onActionSucceeded;
        this.onActionFailed = onActionFailed;
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, threadNamePrefix);
            t.setDaemon(true);
            return t;
        });
    }

    /** The executor backing this runner, for other components (e.g. BrowseController) that need one. */
    public ExecutorService executor() {
        return executor;
    }

    public void run(ThrowingRunnable action) {
        CompletableFuture.runAsync(() -> {
            try {
                action.run();
                onActionSucceeded.run();
            } catch (Exception e) {
                // No "Error: " prefix -- the message itself is written to stand alone (and reads
                // fine on its own), and the red text already signals something went wrong.
                onActionFailed.accept(e.getMessage() != null ? e.getMessage() : "Something went wrong.");
            }
        }, executor);
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
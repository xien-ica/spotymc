package xien.jxsh.spotymc.api;

public class PlaybackState {
    public final boolean isPlaying;
    public final String trackId;
    public final String title;
    public final String artists;
    public final String albumName;
    public final int progressMs;
    public final int durationMs;
    public final int volumePercent;
    /** Spotify Connect device currently active, e.g. "Minecraft" once librespot is selected. Null if unknown. */
    public final String deviceId;
    public final String deviceName;
    /** Timestamp (System.currentTimeMillis) this state was fetched, used to interpolate progress locally. */
    public final long fetchedAtMillis;

    public PlaybackState(boolean isPlaying, String trackId, String title, String artists, String albumName,
                         int progressMs, int durationMs, int volumePercent, String deviceId, String deviceName,
                         long fetchedAtMillis) {
        this.isPlaying = isPlaying;
        this.trackId = trackId;
        this.title = title;
        this.artists = artists;
        this.albumName = albumName;
        this.progressMs = progressMs;
        this.durationMs = durationMs;
        this.volumePercent = volumePercent;
        this.deviceId = deviceId;
        this.deviceName = deviceName;
        this.fetchedAtMillis = fetchedAtMillis;
    }

    /** Empty/idle state, e.g. when nothing is playing or the user isn't logged in yet. */
    public static final PlaybackState NOTHING_PLAYING =
            new PlaybackState(false, null, null, null, null, 0, 0, 0, null, null, System.currentTimeMillis());

    /** Estimated current progress, interpolated between polls so the HUD doesn't visibly stutter. */
    public int estimatedProgressMs() {
        if (!isPlaying) return progressMs;
        long elapsed = System.currentTimeMillis() - fetchedAtMillis;
        return (int) Math.min(durationMs, progressMs + elapsed);
    }

    /**
     * Copy of this state with progress overridden and the interpolation clock reset to now.
     * Used to reflect a seek instantly in the UI, ahead of the next real poll confirming it.
     */
    public PlaybackState withOptimisticProgress(int newProgressMs) {
        int clamped = Math.clamp(durationMs, 0, newProgressMs);
        return new PlaybackState(isPlaying, trackId, title, artists, albumName,
                clamped, durationMs, volumePercent, deviceId, deviceName, System.currentTimeMillis());
    }

    public record QueueItem(String id, String title, String artists) {}
    public record Playlist(String id, String uri, String name, int trackCount) {}
    public record Track(String id, String uri, String title, String artists) {}
}
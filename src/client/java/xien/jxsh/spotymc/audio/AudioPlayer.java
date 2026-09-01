package xien.jxsh.spotymc.audio;

import javax.sound.sampled.*;
import java.io.IOException;
import java.io.InputStream;

/**
 * Plays a raw PCM stream (44100 Hz, 16-bit signed, stereo, little-endian —
 * librespot's default pipe format) through the system's default audio output.
 * Runs its own read/write loop on a daemon thread so it never touches the
 * render thread. The SourceDataLine's internal buffer applies natural
 * back-pressure on read(), keeping playback speed correct even though
 * librespot writes to the pipe as fast as it can decode.
 * <p>
 * Normal volume changes (in-game slider or Spotify app) are already applied
 * upstream by librespot's mixer. {@link #setVolume(float)} is an extra
 * software multiplier on top of that (used for the output-cap feature).
 * Applied by scaling PCM samples directly rather than via
 * {@code FloatControl.Type.MASTER_GAIN}, which is not present on every
 * mixer/platform.
 */
public class AudioPlayer {
    private static final AudioFormat FORMAT =
            new AudioFormat(44100f, 16, 2, true, false); // signed, little-endian
    private static final int FRAME_SIZE = FORMAT.getFrameSize(); // 4 bytes
    private static final int BUFFER_SIZE = 4096;

    private Thread playbackThread;
    private volatile boolean running = false;
    private volatile SourceDataLine line;
    /** True only once the SourceDataLine is open and the read/write loop is going. */
    private volatile boolean active = false;
    /** Set when the playback loop dies from something other than a normal stream close. */
    private volatile String lastError;
    /** Software gain multiplier (0.0–1.0) applied to every sample in playLoop. */
    private volatile float gain = 1.0f;

    /** Starts consuming {@code pcmStream} and playing it. Call {@link #stop()} first if a previous stream is active. */
    public synchronized void start(InputStream pcmStream) {
        stop();
        if (pcmStream == null) return;
        running = true;
        active = false;
        lastError = null;
        playbackThread = new Thread(() -> playLoop(pcmStream), "spotymc-audio-player");
        playbackThread.setDaemon(true);
        playbackThread.start();
    }

    public synchronized void stop() {
        running = false;
        active = false;
        SourceDataLine current = line;
        if (current != null) {
            current.stop();
            current.close();
            line = null;
        }
        if (playbackThread != null) {
            playbackThread.interrupt();
            playbackThread = null;
        }
    }

    /** True once the SourceDataLine is open and the read/write loop is actually running. */
    public boolean isActive() {
        return active;
    }

    /** Message from the last unexpected playback-loop death, or null. */
    public String getLastError() {
        return lastError;
    }

    /**
     * Optional extra gain multiplier, 0.0–1.0, applied on top of Spotify Connect volume.
     * Stored and applied by playLoop as a direct multiply on each PCM sample.
     */
    public void setVolume(float volume01) {
        gain = Math.clamp(volume01, 0f, 1f);
    }

    /**
     * Multiplies every 16-bit signed little-endian sample in {@code buffer[0, count)} by the
     * current gain, in place. Skipped entirely when gain ≈ 1.0 (the common case) so we pay
     * nothing on every buffer when no attenuation is active.
     */
    private void applyGain(byte[] buffer, int count) {
        float g = gain;
        if (g >= 0.999f) return;
        for (int i = 0; i + 1 < count; i += 2) {
            short sample = (short) ((buffer[i] & 0xFF) | (buffer[i + 1] << 8));
            int scaled = Math.round(sample * g);
            scaled = Math.clamp(scaled, Short.MIN_VALUE, Short.MAX_VALUE);
            buffer[i] = (byte) (scaled & 0xFF);
            buffer[i + 1] = (byte) ((scaled >> 8) & 0xFF);
        }
    }

    private void playLoop(InputStream pcmStream) {
        SourceDataLine newLine = null;
        try {
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, FORMAT);
            newLine = (SourceDataLine) AudioSystem.getLine(info);
            newLine.open(FORMAT);
            newLine.start();
            line = newLine;
            active = true;

            // pcmStream.read() has no concept of frame boundaries — it can return a byte count
            // that isn't a multiple of the frame size. SourceDataLine.write() requires whole
            // frames, so we only write the aligned portion and carry any leftover partial
            // frame to the front of the buffer for the next read to complete.
            byte[] buffer = new byte[BUFFER_SIZE];
            int filled = 0;
            int read;
            while (running && (read = pcmStream.read(buffer, filled, buffer.length - filled)) != -1) {
                int total = filled + read;
                int writable = (total / FRAME_SIZE) * FRAME_SIZE;
                if (writable > 0) {
                    applyGain(buffer, writable);
                    newLine.write(buffer, 0, writable);
                }
                int leftover = total - writable;
                if (leftover > 0) {
                    System.arraycopy(buffer, writable, buffer, 0, leftover);
                }
                filled = leftover;
            }
        } catch (LineUnavailableException e) {
            lastError = "No audio output line available: " + e.getMessage();
            System.out.println("[spotymc] " + lastError);
        } catch (IOException e) {
            // stream closed because librespot stopped/was killed — not an error
        } catch (Exception e) {
            lastError = "Audio playback failed: " + e.getMessage();
            System.out.println("[spotymc] " + lastError);
        } finally {
            active = false;
            if (newLine != null) {
                newLine.drain();
                newLine.stop();
                newLine.close();
            }
            line = null;
        }
    }
}

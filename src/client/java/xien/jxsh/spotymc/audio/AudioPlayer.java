package xien.jxsh.spotymc.audio;

import javax.sound.sampled.*;
import java.io.IOException;
import java.io.InputStream;

/**
 * Plays a raw PCM stream (44100Hz, 16-bit signed, stereo, little-endian --
 * libre spot's default pipe format) through the system's default audio
 * output. Runs its own read/write loop on a daemon thread, so it never
 * touches the render thread. The SourceDataLine's internal buffer applies
 * natural backpressure on read(), which is what keeps playback speed correct
 * even though librespot itself writes to the pipe as fast as it can decode.
 * <p>
 * Note: normal volume changes (the in-game slider, or the Spotify app) are
 * already applied upstream by libre spot's own mixer before the audio reaches
 * this class -- setVolume() here is an optional *additional* multiplier on
 * top of that, useful later if you want to duck music under game sounds.
 * Applied by scaling the raw PCM samples directly (see playLoop) rather than
 * via the SourceDataLine's own gain control: that control (FloatControl.Type.
 * MASTER_GAIN) simply isn't present on every mixer/platform, and silently
 * doing nothing when it's missing is worse than the extra bit of math here.
 */
public class AudioPlayer {
    private static final AudioFormat FORMAT =
            new AudioFormat(44100f, 16, 2, true, false); // signed, little-endian

    private Thread playbackThread;
    private volatile boolean running = false;
    private volatile SourceDataLine line;
    /** True only once the SourceDataLine is actually open and the read/write loop is going. */
    private volatile boolean active = false;
    /** Set when the playback loop dies from something other than a normal stream close. */
    private volatile String lastError;
    /** Software gain multiplier (0.0-1.0) applied to every sample in playLoop -- see setVolume(). */
    private volatile float gain = 1.0f;

    /** Starts consuming pcmStream and playing it. Call stop() first if a previous stream is active. */
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

    /** True once the SourceDataLine is open and the read/write loop is actually running -- i.e. sound should be audible. */
    public boolean isActive() {
        return active;
    }

    /** Message from the last time the playback loop died unexpectedly (e.g. no audio line available), or null. */
    public String getLastError() {
        return lastError;
    }

    /**
     * Optional extra gain multiplier, 0.0-1.0, applied on top of Spotify Connect's own volume.
     * Stored and applied by playLoop as a direct multiply on each PCM sample -- deliberately NOT
     * routed through the SourceDataLine's own MASTER_GAIN control, since that control isn't
     * guaranteed to be supported by every mixer/platform: when unsupported, getControl() throws
     * and the old code here just silently did nothing, so the slider had zero effect. Software
     * scaling always works, at the cost of a little extra math per buffer.
     */
    public void setVolume(float volume01) {
        gain = Math.clamp(volume01, 0f, 1f);
    }

    /**
     * Multiplies every 16-bit signed little-endian sample in buffer[0, count) by the current
     * gain, in place. Skipped entirely at gain == 1.0 (the common case: full volume) to avoid
     * paying this cost on every buffer when nothing's actually being scaled down.
     */
    private void applyGain(byte[] buffer, int count) {
        float g = gain;
        if (g >= 0.999f) return; // full volume -- nothing to scale
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

            // pcmStream.read() has no concept of frame boundaries -- it can (and does) hand back
            // a byte count that isn't a multiple of the frame size (4 bytes: 2 channels x 16-bit).
            // SourceDataLine.write() requires whole frames, so we only ever write the aligned
            // portion and carry any leftover partial frame over to the front of the buffer for
            // the next read to complete.
            int frameSize = FORMAT.getFrameSize();
            byte[] buffer = new byte[4096];
            int filled = 0;
            int read;
            while (running && (read = pcmStream.read(buffer, filled, buffer.length - filled)) != -1) {
                int total = filled + read;
                int writable = (total / frameSize) * frameSize;
                if (writable > 0) {
                    applyGain(buffer, writable);
                    newLine.write(buffer, 0, writable);
                }
                int leftover = total - writable;
                if (leftover > 0) System.arraycopy(buffer, writable, buffer, 0, leftover);
                filled = leftover;
            }
        } catch (LineUnavailableException e) {
            lastError = "No audio output line available: " + e.getMessage();
            System.out.println("[spotymc] " + lastError);
        } catch (IOException e) {
            // stream closed because librespot stopped/was killed -- not an error condition
        } catch (Exception e) {
            // Catch-all so a bug here (like the frame-alignment one this replaced) shows up as a
            // visible status in the F12 UI instead of silently killing the thread with no trace.
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
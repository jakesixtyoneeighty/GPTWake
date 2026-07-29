package dev.desmond.gptwakeprobe;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 16 kHz mono capture loop. Buffers are preallocated once and reused; short reads are
 * accumulated into whole 80 ms frames before anything is handed to the keyword spotter.
 */
public final class AudioProbe {

    public static final int SAMPLE_RATE = 16000;
    public static final int FRAME_SAMPLES = 1280;   // 80 ms

    public interface WakeListener {
        void onKeyword(String keyword);
        void onFirstFrame();
    }

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final AtomicBoolean FEEDING = new AtomicBoolean(false);
    private static volatile Thread worker;
    private static volatile String lastResult = "none";
    private static volatile KwsEngine engine;
    private static volatile WakeListener listener;

    public static boolean isRunning() {
        return RUNNING.get();
    }

    public static String lastResult() {
        return lastResult;
    }

    public static void bind(KwsEngine e, WakeListener l) {
        engine = e;
        listener = l;
    }

    /** Stops handing frames to the spotter without tearing down the capture loop. */
    public static void setFeeding(boolean on) {
        FEEDING.set(on);
    }

    public static synchronized void start(String who) {
        if (!RUNNING.compareAndSet(false, true)) {
            L.i("AUDIO_ALREADY_RUNNING who=" + who);
            return;
        }
        worker = new Thread(() -> loop(who), "kws-capture");
        worker.setPriority(Thread.MAX_PRIORITY - 1);
        worker.start();
    }

    public static synchronized void stop() {
        RUNNING.set(false);
        Thread t = worker;
        if (t != null) {
            try {
                t.join(3000);
                L.i("AUDIO_JOIN_OK alive=" + t.isAlive());
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        worker = null;
        L.i("AUDIO_STOPPED");
    }

    private static void loop(String who) {
        AudioRecord ar = null;
        final short[] pcm = new short[FRAME_SAMPLES];
        final float[] frame = new float[FRAME_SAMPLES];
        final short[] acc = new short[FRAME_SAMPLES];
        int accLen = 0;

        long frames = 0, droppedFrames = 0, decodes = 0;
        double decodeSum = 0, decodeMax = 0;
        final double[] decodeSamples = new double[512];
        int decodeIdx = 0;
        long statWindow = System.currentTimeMillis();
        boolean firstFrameSent = false;

        try {
            int min = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) {
                lastResult = "MIN_BUFFER_BAD";
                L.i("AUDIO_MIN_BUFFER_BAD=" + min + " who=" + who);
                RUNNING.set(false);
                return;
            }

            ar = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build())
                    .setBufferSizeInBytes(Math.max(min * 4, FRAME_SAMPLES * 2 * 8))
                    .build();

            L.i("AUDIO_BUILT who=" + who + " state=" + ar.getState());
            if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
                lastResult = "INIT_FAIL";
                L.i("AUDIO_INIT_FAIL state=" + ar.getState() + " who=" + who);
                RUNNING.set(false);
                return;
            }

            ar.startRecording();
            int rs = ar.getRecordingState();
            L.i("AUDIO_STARTRECORDING who=" + who + " recordingState="
                    + (rs == AudioRecord.RECORDSTATE_RECORDING ? "RECORDSTATE_RECORDING" : rs));
            if (rs != AudioRecord.RECORDSTATE_RECORDING) {
                lastResult = "START_FAIL";
                L.i("AUDIO_START_FAIL recState=" + rs + " who=" + who);
                RUNNING.set(false);
                return;
            }
            L.i("DIRECT_AUDIORECORD_OK who=" + who);

            double rms = 0;
            while (RUNNING.get()) {
                int want = FRAME_SAMPLES - accLen;
                int n = ar.read(pcm, 0, want);
                if (n < 0) {
                    lastResult = "READ_ERR" + n;
                    L.i("AUDIO_READ_ERR n=" + n + " who=" + who);
                    break;
                }
                if (n == 0) continue;

                System.arraycopy(pcm, 0, acc, accLen, n);
                accLen += n;
                if (accLen < FRAME_SAMPLES) continue;   // wait for a whole frame
                accLen = 0;
                frames++;

                double sq = 0;
                for (int i = 0; i < FRAME_SAMPLES; i++) {
                    short s = acc[i];
                    frame[i] = s / 32768.0f;
                    sq += (double) s * s;
                }
                rms = Math.sqrt(sq / FRAME_SAMPLES);

                if (!firstFrameSent) {
                    firstFrameSent = true;
                    L.i("KWS_AUDIO_FIRST_FRAME rms=" + String.format("%.1f", rms) + " who=" + who);
                    WakeListener l = listener;
                    if (l != null) l.onFirstFrame();
                }

                KwsEngine e = engine;
                if (e != null && e.isLoaded() && FEEDING.get()) {
                    long d0 = System.nanoTime();
                    String hit = e.accept(frame, SAMPLE_RATE);
                    double ms = (System.nanoTime() - d0) / 1e6;
                    decodes++;
                    decodeSum += ms;
                    if (ms > decodeMax) decodeMax = ms;
                    decodeSamples[decodeIdx++ % decodeSamples.length] = ms;
                    if (ms > 80) droppedFrames++;   // slower than real time

                    if (hit != null) {
                        L.i("KWS_HIT keyword=" + hit + " timestamp=" + System.currentTimeMillis()
                                + " rms=" + String.format("%.1f", rms));
                        FEEDING.set(false);         // stop feeding immediately
                        WakeListener l = listener;
                        if (l != null) l.onKeyword(hit);
                    }
                }

                long now = System.currentTimeMillis();
                if (now - statWindow >= 60_000) {
                    statWindow = now;
                    int cnt = (int) Math.min(decodeIdx, decodeSamples.length);
                    double[] copy = Arrays.copyOf(decodeSamples, cnt);
                    Arrays.sort(copy);
                    double p95 = cnt > 0 ? copy[(int) Math.min(cnt - 1, Math.round(cnt * 0.95) - 1 < 0 ? 0 : Math.round(cnt * 0.95) - 1)] : 0;
                    L.i(String.format(
                            "KWS_STATS frames=%d decodes=%d decodeAvgMs=%.2f decodeP95Ms=%.2f maxDecodeMs=%.2f "
                                    + "droppedFrames=%d rms=%.1f %s rssKb=%d cpuMs=%d",
                            frames, decodes, decodes > 0 ? decodeSum / decodes : 0, p95, decodeMax,
                            droppedFrames, rms, AudioStateMonitor.ownCaptureState(),
                            Sys.rssKb(), Sys.cpuMs()));
                    decodeSum = 0;
                    decodes = 0;
                    decodeMax = 0;
                }
                lastResult = "RECORDING rms=" + String.format("%.1f", rms);
            }
        } catch (SecurityException se) {
            lastResult = "SECURITY_EXCEPTION";
            L.e("AUDIO_SECURITY_EXCEPTION who=" + who, se);
        } catch (Throwable t) {
            lastResult = "EXCEPTION";
            L.e("DIRECT_AUDIORECORD_FAIL who=" + who, t);
        } finally {
            try {
                if (ar != null) {
                    if (ar.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) ar.stop();
                    ar.release();
                    L.i("AUDIO_RELEASED who=" + who + " frames=" + frames);
                }
            } catch (Throwable ignored) {
            }
            RUNNING.set(false);
            L.i("AUDIO_LOOP_EXIT who=" + who);
        }
    }
}

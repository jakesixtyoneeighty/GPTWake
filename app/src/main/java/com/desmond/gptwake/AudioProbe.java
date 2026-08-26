package com.desmond.gptwake;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 16 kHz mono capture loop. Buffers are preallocated once and reused; short reads are
 * accumulated into whole 80 ms frames before anything is handed to the keyword spotter.
 *
 * <p>Pixel phones (Gemini / Hey Google always-on hotword) silence a privacy-sensitive
 * {@code VOICE_RECOGNITION} capture rather than failing {@code startRecording()}. The
 * app then "runs" with digital-zero audio and the wake word never fires. We therefore
 * open {@code MIC} first, mark the capture not privacy-sensitive so it can share with
 * the privileged hotword, and fall back through {@code UNPROCESSED} then
 * {@code VOICE_RECOGNITION} if a source comes up silenced.
 */
public final class AudioProbe {

    public static final int SAMPLE_RATE = 16000;
    public static final int FRAME_SAMPLES = 1280;   // 80 ms

    /** RMS below this is digital silence, not a quiet room (thermal noise is typically 10–80). */
    private static final double DEAD_RMS = 1.0;

    /**
     * Pixel / Gemini silences {@code VOICE_RECOGNITION} by default. {@code MIC} shares with
     * the privileged hotword once {@code setPrivacySensitive(false)} is set.
     */
    private static final int[] SOURCE_CANDIDATES = {
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
    };

    public interface WakeListener {
        void onKeyword(String keyword);
        void onFirstFrame();
    }

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final AtomicBoolean FEEDING = new AtomicBoolean(false);
    private static final AtomicBoolean SILENCED = new AtomicBoolean(false);
    private static volatile Thread worker;
    private static volatile String lastResult = "none";
    private static volatile KwsEngine engine;
    private static volatile WakeListener listener;
    private static volatile long captureThreadCpuMs = -1;
    private static volatile double lastRms = 0;
    private static volatile int activeSource = MediaRecorder.AudioSource.MIC;
    private static volatile int audioSessionId;
    /** Survives {@link #stop()} so handoff can wait for this session to leave the capture list. */
    private static volatile int lastSessionId;

    public static double lastRms() {
        return lastRms;
    }

    public static long captureThreadCpuMs() {
        return captureThreadCpuMs;
    }

    public static boolean isRunning() {
        return RUNNING.get();
    }

    public static boolean isSilenced() {
        return SILENCED.get();
    }

    /** True when the capture is up but the samples are digital zeros. */
    public static boolean isHearingSilence() {
        return RUNNING.get() && lastRms < DEAD_RMS;
    }

    public static String lastResult() {
        return lastResult;
    }

    public static int audioSessionId() {
        return audioSessionId;
    }

    public static int lastAudioSessionId() {
        return lastSessionId;
    }

    public static int activeSource() {
        return activeSource;
    }

    public static String activeSourceName() {
        return AudioStateMonitor.sourceName(activeSource);
    }

    public static void bind(KwsEngine e, WakeListener l) {
        engine = e;
        listener = l;
    }

    /** Stops handing frames to the spotter without tearing down the capture loop. */
    public static void setFeeding(boolean on) {
        FEEDING.set(on);
    }

    public static synchronized void start(Context ctx, String who) {
        if (!RUNNING.compareAndSet(false, true)) {
            L.i("AUDIO_ALREADY_RUNNING who=" + who);
            return;
        }
        Context app = ctx.getApplicationContext();
        worker = new Thread(() -> {
            ThreadCpu.publish("audio-capture");
            loop(app, who);
        }, "kws-capture");
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
        audioSessionId = 0;
        SILENCED.set(false);
        L.i("AUDIO_STOPPED");
    }

    private static double pct(double[] sorted, int cnt, double q) {
        if (cnt <= 0) return 0;
        int i = (int) Math.ceil(q * cnt) - 1;
        if (i < 0) i = 0;
        if (i >= cnt) i = cnt - 1;
        return sorted[i];
    }

    private static void loop(Context ctx, String who) {
        AudioRecord ar = null;
        final short[] pcm = new short[FRAME_SAMPLES];
        final float[] frame = new float[FRAME_SAMPLES];
        final short[] acc = new short[FRAME_SAMPLES];
        int accLen = 0;

        long frames = 0, droppedFrames = 0, decodes = 0;
        double decodeSum = 0, decodeMax = 0;
        final double[] decodeSamples = new double[2048];
        int decodeIdx = 0;
        long statWindow = System.currentTimeMillis();
        long statCpuMs = android.os.Process.getElapsedCpuTime();
        long statThreadCpuNs = android.os.Debug.threadCpuTimeNanos();
        long statFrames = 0;
        boolean firstFrameSent = false;
        boolean lastSilenced = false;

        try {
            int min = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) {
                lastResult = "MIN_BUFFER_BAD";
                L.i("AUDIO_MIN_BUFFER_BAD=" + min + " who=" + who);
                RUNNING.set(false);
                return;
            }

            ar = openBestRecord(ctx, who, min);
            if (ar == null) {
                lastResult = "INIT_FAIL";
                L.i("AUDIO_INIT_FAIL who=" + who);
                RUNNING.set(false);
                return;
            }

            L.i("DIRECT_AUDIORECORD_OK who=" + who
                    + " src=" + activeSourceName()
                    + " sess=" + audioSessionId
                    + " silenced=" + SILENCED.get());

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
                lastRms = rms;

                if (!firstFrameSent) {
                    firstFrameSent = true;
                    L.i("KWS_AUDIO_FIRST_FRAME rms=" + String.format("%.1f", rms)
                            + " src=" + activeSourceName()
                            + " who=" + who);
                    WakeListener l = listener;
                    if (l != null) l.onFirstFrame();
                }

                if (frames % 25 == 0) {
                    boolean sil = isSessionSilenced(ar.getAudioSessionId());
                    SILENCED.set(sil);
                    if (sil != lastSilenced) {
                        L.i("AUDIO_SILENCED=" + sil
                                + " src=" + activeSourceName()
                                + " rms=" + String.format("%.1f", rms)
                                + " sess=" + ar.getAudioSessionId());
                        lastSilenced = sil;
                    }
                }

                KwsEngine e = engine;
                if (e != null && e.isLoaded() && FEEDING.get() && !Cfg.micOnly) {
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
                    long wallDelta = now - statWindow;
                    long cpuNow = android.os.Process.getElapsedCpuTime();
                    long threadCpuNow = android.os.Debug.threadCpuTimeNanos();
                    long cpuDelta = cpuNow - statCpuMs;
                    long threadCpuDeltaMs = (threadCpuNow - statThreadCpuNs) / 1_000_000L;
                    captureThreadCpuMs = threadCpuNow / 1_000_000L;
                    ThreadCpu.publish("audio-capture");

                    int cnt = (int) Math.min(decodeIdx, decodeSamples.length);
                    double[] copy = Arrays.copyOf(decodeSamples, cnt);
                    Arrays.sort(copy);
                    double p50 = pct(copy, cnt, 0.50);
                    double p95 = pct(copy, cnt, 0.95);
                    double p99 = pct(copy, cnt, 0.99);

                    L.i(String.format(
                            "KWS_STATS mode=%s frames=%d framesDelta=%d decodes=%d "
                                    + "decodeAvgMs=%.2f decodeP50Ms=%.2f decodeP95Ms=%.2f decodeP99Ms=%.2f maxDecodeMs=%.2f "
                                    + "droppedFrames=%d rms=%.1f src=%s silenced=%s %s "
                                    + "wallDeltaMs=%d cpuDeltaMs=%d cpuOneCorePct=%.2f "
                                    + "captureThreadCpuDeltaMs=%d captureThreadPct=%.2f rssKb=%d",
                            Cfg.micOnly ? "MIC_ONLY_MODEL_LOADED" : (Cfg.evalMode ? "KWS_EVAL" : "KWS_FULL"),
                            frames, frames - statFrames, decodes,
                            decodes > 0 ? decodeSum / decodes : 0, p50, p95, p99, decodeMax,
                            droppedFrames, rms, activeSourceName(), SILENCED.get(),
                            AudioStateMonitor.ownCaptureState(),
                            wallDelta, cpuDelta, wallDelta > 0 ? cpuDelta * 100.0 / wallDelta : 0,
                            threadCpuDeltaMs, wallDelta > 0 ? threadCpuDeltaMs * 100.0 / wallDelta : 0,
                            Sys.rssKb()));

                    Measure.event("KWS_STATS", "\"mode\":\"" + (Cfg.micOnly
                            ? "MIC_ONLY_MODEL_LOADED" : (Cfg.evalMode ? "KWS_EVAL" : "KWS_FULL"))
                            + "\",\"framesDelta\":" + (frames - statFrames)
                            + ",\"decodes\":" + decodes
                            + ",\"acceptCalls\":" + KwsEngine.acceptCalls.get()
                            + ",\"readyTrueCount\":" + KwsEngine.readyTrueCount.get()
                            + ",\"decodeCalls\":" + KwsEngine.decodeCalls.get()
                            + String.format(",\"decodeAvgMs\":%.2f,\"decodeP50Ms\":%.2f,"
                                    + "\"decodeP95Ms\":%.2f,\"decodeP99Ms\":%.2f,\"maxDecodeMs\":%.2f",
                                    decodes > 0 ? decodeSum / decodes : 0, p50, p95, p99, decodeMax)
                            + ",\"droppedFrames\":" + droppedFrames
                            + String.format(",\"rms\":%.1f", rms)
                            + ",\"src\":\"" + activeSourceName() + "\""
                            + ",\"silenced\":" + SILENCED.get()
                            + ",\"wallDeltaMs\":" + wallDelta
                            + ",\"cpuDeltaMs\":" + cpuDelta
                            + ",\"captureThreadCpuDeltaMs\":" + threadCpuDeltaMs);

                    statWindow = now;
                    statCpuMs = cpuNow;
                    statThreadCpuNs = threadCpuNow;
                    statFrames = frames;
                    decodeSum = 0;
                    decodes = 0;
                    decodeMax = 0;
                    decodeIdx = 0;
                    Arrays.fill(decodeSamples, 0);
                }
                lastResult = "RECORDING rms=" + String.format("%.1f", rms)
                        + " src=" + activeSourceName()
                        + (SILENCED.get() ? " SILENCED" : "");
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
            audioSessionId = 0;
            SILENCED.set(false);
            RUNNING.set(false);
            L.i("AUDIO_LOOP_EXIT who=" + who);
        }
    }

    /**
     * Tries sources until one initialises, records, and is not silenced by concurrent-capture
     * policy. If every source is silenced we still keep the last one so the UI can report it.
     */
    private static AudioRecord openBestRecord(Context ctx, String who, int min) {
        AudioRecord silencedFallback = null;
        int silencedSource = -1;
        for (int src : SOURCE_CANDIDATES) {
            AudioRecord ar = openRecord(ctx, src, min);
            if (ar == null) continue;
            try {
                ar.startRecording();
            } catch (Throwable t) {
                L.e("AUDIO_STARTRECORDING_FAIL src=" + AudioStateMonitor.sourceName(src), t);
                releaseQuietly(ar);
                continue;
            }
            int rs = ar.getRecordingState();
            L.i("AUDIO_STARTRECORDING who=" + who
                    + " src=" + AudioStateMonitor.sourceName(src)
                    + " recordingState="
                    + (rs == AudioRecord.RECORDSTATE_RECORDING ? "RECORDSTATE_RECORDING" : rs)
                    + " sess=" + ar.getAudioSessionId());
            if (rs != AudioRecord.RECORDSTATE_RECORDING) {
                releaseQuietly(ar);
                continue;
            }
            preferBuiltinMic(ctx, ar);
            boolean sil = probeSilenced(ar);
            if (sil) {
                L.i("AUDIO_SOURCE_SILENCED src=" + AudioStateMonitor.sourceName(src)
                        + " sess=" + ar.getAudioSessionId() + " — trying next");
                if (silencedFallback != null) releaseQuietly(silencedFallback);
                silencedFallback = ar;
                silencedSource = src;
                continue;
            }
            if (silencedFallback != null) releaseQuietly(silencedFallback);
            commitSource(src, ar.getAudioSessionId(), false);
            return ar;
        }
        if (silencedFallback != null) {
            commitSource(silencedSource, silencedFallback.getAudioSessionId(), true);
            L.i("AUDIO_ALL_SOURCES_SILENCED keeping src="
                    + AudioStateMonitor.sourceName(silencedSource));
            return silencedFallback;
        }
        return null;
    }

    private static AudioRecord openRecord(Context ctx, int src, int min) {
        try {
            AudioRecord ar = new AudioRecord.Builder()
                    .setAudioSource(src)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build())
                    .setBufferSizeInBytes(Math.max(min * 4, FRAME_SAMPLES * 2 * 8))
                    .setContext(ctx)
                    .setPrivacySensitive(false)
                    .build();
            L.i("AUDIO_BUILT src=" + AudioStateMonitor.sourceName(src)
                    + " state=" + ar.getState()
                    + " privacySensitive=" + ar.isPrivacySensitive());
            if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
                L.i("AUDIO_INIT_FAIL src=" + AudioStateMonitor.sourceName(src)
                        + " state=" + ar.getState());
                releaseQuietly(ar);
                return null;
            }
            return ar;
        } catch (Throwable t) {
            L.i("AUDIO_SOURCE_UNSUPPORTED src=" + AudioStateMonitor.sourceName(src)
                    + " err=" + t);
            return null;
        }
    }

    private static void commitSource(int src, int sess, boolean silenced) {
        activeSource = src;
        audioSessionId = sess;
        lastSessionId = sess;
        SILENCED.set(silenced);
    }

    /**
     * One blocking read so the HAL publishes a recording configuration, then ask whether
     * concurrent-capture policy is feeding us zeros.
     */
    private static boolean probeSilenced(AudioRecord ar) {
        short[] buf = new short[FRAME_SAMPLES];
        try {
            ar.read(buf, 0, buf.length);
        } catch (Throwable ignored) {
        }
        return isSessionSilenced(ar.getAudioSessionId());
    }

    static boolean isSessionSilenced(int sess) {
        if (sess <= 0) return false;
        List<AudioRecordingConfiguration> cfgs = AudioStateMonitor.configs();
        if (cfgs == null) return false;
        for (AudioRecordingConfiguration c : cfgs) {
            if (c.getClientAudioSessionId() == sess) return c.isClientSilenced();
        }
        return false;
    }

    /**
     * Bluetooth headsets on Pixel often steal the VOICE_RECOGNITION / MIC route. Pin to the
     * built-in mic so the phone itself hears the wake word.
     */
    private static void preferBuiltinMic(Context ctx, AudioRecord ar) {
        try {
            AudioManager am = ctx.getSystemService(AudioManager.class);
            if (am == null) return;
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                    boolean ok = ar.setPreferredDevice(d);
                    L.i("AUDIO_PREFERRED_DEV builtinMic=" + ok + " id=" + d.getId());
                    return;
                }
            }
        } catch (Throwable t) {
            L.e("AUDIO_PREFERRED_DEV_FAIL", t);
        }
    }

    private static void releaseQuietly(AudioRecord ar) {
        if (ar == null) return;
        try {
            if (ar.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) ar.stop();
        } catch (Throwable ignored) {
        }
        try {
            ar.release();
        } catch (Throwable ignored) {
        }
    }
}

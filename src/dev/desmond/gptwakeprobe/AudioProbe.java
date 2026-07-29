package dev.desmond.gptwakeprobe;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import java.util.concurrent.atomic.AtomicBoolean;

/** Continuous 16 kHz mono capture that logs RMS, standing in for the KWS front-end. */
public final class AudioProbe {

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static volatile Thread worker;
    private static volatile String lastResult = "none";

    public static boolean isRunning() {
        return RUNNING.get();
    }

    public static String lastResult() {
        return lastResult;
    }

    public static synchronized void start(String who) {
        if (!RUNNING.compareAndSet(false, true)) {
            L.i("AUDIO_ALREADY_RUNNING who=" + who);
            return;
        }
        worker = new Thread(() -> loop(who), "audio-probe");
        worker.start();
    }

    /** Full stop → join → release, as required before handing the mic to ChatGPT. */
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
        try {
            int min = AudioRecord.getMinBufferSize(
                    16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) {
                lastResult = "MIN_BUFFER_BAD";
                L.i("AUDIO_MIN_BUFFER_BAD=" + min + " who=" + who);
                RUNNING.set(false);
                return;
            }

            ar = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(16000)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build())
                    .setBufferSizeInBytes(min * 4)
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

            L.i("DIRECT_AUDIORECORD_OK who=" + who
                    + " ownCapture=" + AudioStateMonitor.ownCaptureState());
            AudioStateMonitor.dumpConfigs("afterStart:" + who);

            short[] buf = new short[1600];
            long last = 0;
            int silentFrames = 0, totalFrames = 0;
            double maxRms = 0;

            while (RUNNING.get()) {
                int n = ar.read(buf, 0, buf.length);
                if (n < 0) {
                    lastResult = "READ_ERR" + n;
                    L.i("AUDIO_READ_ERR n=" + n + " who=" + who);
                    break;
                }
                if (n == 0) continue;

                double acc = 0;
                for (int i = 0; i < n; i++) acc += (double) buf[i] * buf[i];
                double rms = Math.sqrt(acc / n);
                if (rms > maxRms) maxRms = rms;

                totalFrames++;
                if (rms == 0.0) silentFrames++;

                long now = System.currentTimeMillis();
                if (now - last > 3000) {
                    last = now;
                    lastResult = "RECORDING rms=" + String.format("%.1f", rms);
                    L.i(String.format(
                            "AUDIO_RECORDING rms=%.1f maxRms=%.1f frames=%d allZeroFrames=%d %s who=%s",
                            rms, maxRms, totalFrames, silentFrames,
                            AudioStateMonitor.ownCaptureState(), who));
                }
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
                    L.i("AUDIO_RELEASED who=" + who);
                }
            } catch (Throwable ignored) {
            }
            RUNNING.set(false);
            L.i("AUDIO_LOOP_EXIT who=" + who);
        }
    }
}

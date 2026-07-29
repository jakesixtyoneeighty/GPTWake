package com.desmond.gptwake;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.ResultReceiver;
import java.util.concurrent.atomic.AtomicBoolean;

public class WakeService extends Service {

    private static final String CH = "listening";
    private static final int ID = 7311;

    public static final String EXTRA_ACK = "ack";
    public static final String EXTRA_CYCLE = "cycle";
    public static final String EXTRA_STOP_AFTER = "stop_after";
    public static final String EXTRA_RESUME_AFTER = "resume_after";

    private static final AtomicBoolean FOREGROUND = new AtomicBoolean(false);
    private HandlerThread ht;
    private Handler bg;
    private static volatile WakeController controller;

    public static WakeController controller() {
        return controller;
    }

    public static boolean isForegroundNow() {
        return FOREGROUND.get();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ht = new HandlerThread("mic-probe-sched");
        ht.start();
        bg = new Handler(ht.getLooper());
        AudioStateMonitor.install(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ResultReceiver ack = intent == null ? null
                : intent.getParcelableExtra(EXTRA_ACK, ResultReceiver.class);

        if (!FOREGROUND.get()) {
            try {
                NotificationManager nm = getSystemService(NotificationManager.class);
                NotificationChannel ch = new NotificationChannel(
                        CH, getString(R.string.channel_listening), NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
                Notification n = new Notification.Builder(this, CH)
                        .setContentTitle(getString(R.string.notification_listening))
                        .setContentText(WakeWordStore.phrase(this))
                        .setSmallIcon(R.drawable.ic_mic)
                        .setContentIntent(android.app.PendingIntent.getActivity(this, 0,
                                new Intent(this, MainActivity.class)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                android.app.PendingIntent.FLAG_IMMUTABLE))
                        .setOngoing(true)
                        .build();
                startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
                FOREGROUND.set(true);
                L.i("MIC_FGS_START_OK");
            } catch (Throwable t) {
                L.e("MIC_FGS_START_FAIL", t);
                if (ack != null) ack.send(1, null);
                stopSelf();
                return START_NOT_STICKY;
            }
        } else {
            L.i("MIC_FGS_ALREADY_FOREGROUND");
        }

        if (intent != null && intent.getBooleanExtra(EXTRA_CYCLE, false)) {
            scheduleCycle(intent.getIntExtra(EXTRA_STOP_AFTER, 45),
                    intent.getIntExtra(EXTRA_RESUME_AFTER, 75));
        } else if (controller == null) {
            controller = new WakeController(this);
            controller.start();
        } else {
            L.i("WAKE_CONTROLLER_ALREADY state=" + controller.state());
        }

        if (ack != null) {
            // ACK only once the capture loop is actually producing audio.
            bg.postDelayed(() -> {
                boolean ok = AudioProbe.isRunning();
                L.i("FGS_ACK running=" + ok + " last=" + AudioProbe.lastResult());
                ack.send(ok ? 0 : 2, null);
            }, 1200);
        }
        return START_STICKY;
    }

    /**
     * Test one: keep this FGS alive, release the mic mid-flight, then re-acquire it later
     * with no ShimActivity in between. All timing is internal so no adb interaction can
     * grant the process a temporary exemption.
     */
    private void scheduleCycle(int stopAfterSec, int resumeAfterSec) {
        L.i("CYCLE_SCHEDULED stopAfter=" + stopAfterSec + "s resumeAfter=" + resumeAfterSec + "s");
        AudioProbe.start("CYCLE_PHASE1");

        bg.postDelayed(() -> {
            L.i("CYCLE_STOP_BEGIN fgs=" + FOREGROUND.get());
            AudioProbe.stop();
            AudioStateMonitor.dumpConfigs("afterCycleStop");
            L.i("CYCLE_STOP_DONE micReleased fgsStillForeground=" + FOREGROUND.get()
                    + " mode=" + AudioStateMonitor.modeName(AudioStateMonitor.mode()));
        }, stopAfterSec * 1000L);

        bg.postDelayed(() -> {
            L.i("REACQUIRE_BEGIN noShim fgsStillForeground=" + FOREGROUND.get());
            AudioStateMonitor.dumpConfigs("beforeReacquire");
            AudioProbe.start("REACQUIRE");
            bg.postDelayed(() -> {
                L.i("REACQUIRE_RESULT running=" + AudioProbe.isRunning()
                        + " last=" + AudioProbe.lastResult()
                        + " own=" + AudioStateMonitor.ownCaptureState());
                L.i(AudioProbe.isRunning() ? "REACQUIRE_OK" : "REACQUIRE_FAIL");
            }, 10_000L);
        }, resumeAfterSec * 1000L);
    }

    @Override
    public void onDestroy() {
        L.i("MIC_FGS_DESTROY");
        FOREGROUND.set(false);
        WakeController c = controller;
        controller = null;
        if (c != null) c.stop();
        AudioProbe.stop();
        if (ht != null) ht.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

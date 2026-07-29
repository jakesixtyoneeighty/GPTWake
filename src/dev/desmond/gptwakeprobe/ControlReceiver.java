package dev.desmond.gptwakeprobe;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

/** adb-drivable control surface so the probe can be scripted without UI taps. */
public class ControlReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String cmd = intent.getStringExtra("cmd");
        L.i("CTRL cmd=" + cmd);
        if (cmd == null) return;
        Context app = context.getApplicationContext();
        AudioStateMonitor.install(app);

        switch (cmd) {
            case "arm": {
                int delay = intent.getIntExtra("delay", 30);
                L.i("ARMED delay=" + delay + "s");
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    L.i("LAUNCH_ATTEMPT");
                    boolean ok = GptLauncher.launch(app);
                    L.i("LAUNCH_RESULT=" + ok);
                }, delay * 1000L);
                break;
            }
            case "launch":
                L.i("LAUNCH_RESULT=" + GptLauncher.launch(app));
                break;
            case "mode":
                Prefs.setMode(app, intent.getStringExtra("value"));
                L.i("MODE_SET=" + Prefs.mode(app));
                break;
            case "micfgs_start":
                try {
                    app.startForegroundService(new Intent(app, MicProbeService.class));
                    L.i("CTRL_MIC_FGS_REQUEST_OK");
                } catch (Throwable t) {
                    L.e("CTRL_MIC_FGS_REQUEST_FAIL", t);
                }
                break;
            case "micfgs_stop":
                app.stopService(new Intent(app, MicProbeService.class));
                L.i("CTRL_MIC_FGS_STOP");
                break;
            case "rec_start":
                AudioProbe.start("CTRL");
                break;
            case "rec_stop":
                AudioProbe.stop();
                break;
            case "hangup": {
                PendingIntent pi = VoiceNotificationListener.hangUpIntent();
                if (pi == null) {
                    L.i("HANGUP_NO_INTENT");
                    break;
                }
                try {
                    pi.send();
                    L.i("HANGUP_SENT");
                } catch (Throwable t) {
                    L.e("HANGUP_FAIL", t);
                }
                break;
            }
            case "shim_launch":
            case "shim_fgs": {
                Intent i = new Intent(app, ShimActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(ShimActivity.EXTRA_ACTION, "shim_fgs".equals(cmd) ? "fgs" : "launch");
                try {
                    app.startActivity(i);
                    L.i("SHIM_START_OK cmd=" + cmd);
                } catch (Throwable t) {
                    L.e("SHIM_START_FAIL", t);
                }
                break;
            }
            case "cycle_no_shim": {
                int stopAfter = intent.getIntExtra("stop_after", 45);
                int resumeAfter = intent.getIntExtra("resume_after", 75);
                try {
                    app.startForegroundService(new Intent(app, MicProbeService.class)
                            .putExtra(MicProbeService.EXTRA_CYCLE, true)
                            .putExtra(MicProbeService.EXTRA_STOP_AFTER, stopAfter)
                            .putExtra(MicProbeService.EXTRA_RESUME_AFTER, resumeAfter));
                    L.i("CYCLE_REQUEST_OK stop=" + stopAfter + " resume=" + resumeAfter);
                } catch (Throwable t) {
                    L.e("CYCLE_REQUEST_FAIL", t);
                }
                break;
            }
            case "dump_configs":
                AudioStateMonitor.dumpConfigs("manual");
                L.i("VOICE_CONFIRMED=" + AudioStateMonitor.isVoiceConfirmed()
                        + " commMode=" + AudioStateMonitor.isCommunicationMode()
                        + " realCapture=" + AudioStateMonitor.hasRealCommunicationCapture());
                break;
            case "state": {
                L.i("STATE mode=" + Prefs.mode(app)
                        + " recRunning=" + AudioProbe.isRunning()
                        + " fgs=" + MicProbeService.isForegroundNow()
                        + " audioMode=" + AudioStateMonitor.modeName(AudioStateMonitor.mode())
                        + " voiceConfirmed=" + AudioStateMonitor.isVoiceConfirmed()
                        + " own=" + AudioStateMonitor.ownCaptureState()
                        + " hangUp=" + (VoiceNotificationListener.hangUpIntent() != null));
                break;
            }
            default:
                L.i("CTRL_UNKNOWN=" + cmd);
        }
    }
}

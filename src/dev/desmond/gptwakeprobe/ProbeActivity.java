package dev.desmond.gptwakeprobe;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class ProbeActivity extends Activity {

    private TextView status;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            AudioManager am = getSystemService(AudioManager.class);
            status.setText(
                    "mode=" + Prefs.mode(ProbeActivity.this)
                            + "  audioRecordRunning=" + AudioProbe.isRunning()
                            + "\naudioMode=" + AudioStateMonitor.modeName(am.getMode())
                            + "  hangUpIntent=" + (VoiceNotificationListener.hangUpIntent() != null)
                            + "\n\n" + L.dump());
            ui.postDelayed(this, 1000);
        }
    };

    private Button b(LinearLayout parent, String label, View.OnClickListener l) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setAllCaps(false);
        btn.setOnClickListener(l);
        parent.addView(btn);
        return btn;
    }

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (12 * getResources().getDisplayMetrics().density);
        root.setPadding(p, p, p, p);

        b(root, "ARM 30-SECOND LAUNCH", v -> {
            Toast.makeText(this, "Press Home / turn screen off now", Toast.LENGTH_LONG).show();
            L.i("ARMED_30S");
            ui.postDelayed(() -> {
                L.i("LAUNCH_ATTEMPT");
                boolean ok = GptLauncher.launch(getApplicationContext());
                L.i("LAUNCH_RESULT=" + ok);
            }, 30_000L);
        });

        b(root, "LAUNCH CHATGPT VOICE NOW", v -> {
            boolean ok = GptLauncher.launch(getApplicationContext());
            L.i("FOREGROUND_LAUNCH_RESULT=" + ok);
        });

        b(root, "MODE = DIRECT_VIS_RECORD", v -> {
            Prefs.setMode(this, Prefs.MODE_DIRECT_VIS_RECORD);
            L.i("MODE_SET=DIRECT_VIS_RECORD (re-select the assistant to apply)");
        });

        b(root, "MODE = MIC_FGS_FROM_VIS", v -> {
            Prefs.setMode(this, Prefs.MODE_MIC_FGS_FROM_VIS);
            L.i("MODE_SET=MIC_FGS_FROM_VIS (re-select the assistant to apply)");
        });

        b(root, "MODE = OFF", v -> {
            Prefs.setMode(this, Prefs.MODE_OFF);
            L.i("MODE_SET=OFF");
        });

        b(root, "START MIC FGS FROM FOREGROUND", v -> {
            try {
                startForegroundService(new Intent(this, MicProbeService.class));
                L.i("FG_MIC_FGS_REQUEST_OK");
            } catch (Throwable t) {
                L.e("FG_MIC_FGS_REQUEST_FAIL", t);
            }
        });

        b(root, "STOP MIC FGS", v -> {
            stopService(new Intent(this, MicProbeService.class));
            L.i("FG_MIC_FGS_STOP");
        });

        b(root, "START AUDIORECORD IN-PROCESS", v -> AudioProbe.start("ACTIVITY"));
        b(root, "STOP AUDIORECORD", v -> AudioProbe.stop());

        b(root, "HANG UP CHATGPT VOICE (via NLS)", v -> {
            PendingIntent pi = VoiceNotificationListener.hangUpIntent();
            if (pi == null) {
                L.i("HANGUP_NO_INTENT");
                return;
            }
            try {
                pi.send();
                L.i("HANGUP_SENT");
            } catch (Throwable t) {
                L.e("HANGUP_FAIL", t);
            }
        });

        b(root, "OPEN NOTIFICATION LISTENER SETTINGS", v ->
                startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")));

        b(root, "OPEN ASSISTANT SETTINGS", v ->
                startActivity(new Intent("android.settings.VOICE_INPUT_SETTINGS")));

        status = new TextView(this);
        status.setTypeface(Typeface.MONOSPACE);
        status.setTextSize(10f);
        status.setTextColor(Color.parseColor("#DDDDDD"));
        status.setBackgroundColor(Color.parseColor("#101010"));

        ScrollView sv = new ScrollView(this);
        sv.addView(status);
        root.addView(sv);

        setContentView(root);

        AudioStateMonitor.install(this);
        requestPermissions(new String[]{
                android.Manifest.permission.RECORD_AUDIO,
                android.Manifest.permission.POST_NOTIFICATIONS}, 1);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(refresh);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(refresh);
        super.onPause();
    }
}

package dev.desmond.gptwakeprobe;

import android.content.Intent;
import android.service.voice.VoiceInteractionService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

public class RouterVoiceInteractionService extends VoiceInteractionService {

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Override
    public void onReady() {
        super.onReady();
        String mode = Prefs.mode(this);
        L.i("VIS_ON_READY mode=" + mode + " pid=" + android.os.Process.myPid());
        AudioStateMonitor.install(this);

        if (Prefs.MODE_DIRECT_VIS_RECORD.equals(mode)) {
            worker.execute(() -> AudioProbe.start("VIS"));
        } else if (Prefs.MODE_MIC_FGS_FROM_VIS.equals(mode)) {
            try {
                startForegroundService(new Intent(this, MicProbeService.class));
                L.i("MIC_FGS_START_REQUEST_OK");
            } catch (Throwable t) {
                L.e("MIC_FGS_START_REQUEST_FAIL", t);
            }
        } else {
            L.i("VIS_MODE_OFF");
        }
    }

    @Override
    public void onShutdown() {
        L.i("VIS_ON_SHUTDOWN");
        AudioProbe.stop();
        try {
            stopService(new Intent(this, MicProbeService.class));
        } catch (Throwable ignored) {
        }
        super.onShutdown();
    }

    @Override
    public void onLaunchVoiceAssistFromKeyguard() {
        L.i("VIS_LAUNCH_FROM_KEYGUARD");
        AudioProbe.stop();
        GptLauncher.launch(this);
    }
}

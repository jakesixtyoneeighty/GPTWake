package dev.desmond.gptwakeprobe;

import android.content.Intent;
import android.speech.RecognitionService;

public class ProbeRecognitionService extends RecognitionService {
    @Override
    protected void onStartListening(Intent recognizerIntent, Callback listener) {
        L.i("RECOGNITION_START");
    }

    @Override
    protected void onCancel(Callback listener) {
        L.i("RECOGNITION_CANCEL");
    }

    @Override
    protected void onStopListening(Callback listener) {
        L.i("RECOGNITION_STOP");
    }
}

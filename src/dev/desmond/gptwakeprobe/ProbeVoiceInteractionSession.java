package dev.desmond.gptwakeprobe;

import android.content.Context;
import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;

public class ProbeVoiceInteractionSession extends VoiceInteractionSession {

    public ProbeVoiceInteractionSession(Context context) {
        super(context);
    }

    @Override
    public void onShow(Bundle args, int showFlags) {
        super.onShow(args, showFlags);
        L.i("SESSION_ON_SHOW flags=" + showFlags);
        AudioProbe.stop();
        boolean ok = GptLauncher.launch(getContext());
        L.i("SESSION_ROUTE_RESULT=" + ok);
        hide();
    }
}

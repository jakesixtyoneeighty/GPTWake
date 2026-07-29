package dev.desmond.gptwakeprobe;

import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.service.voice.VoiceInteractionSessionService;

public class ProbeVoiceInteractionSessionService extends VoiceInteractionSessionService {
    @Override
    public VoiceInteractionSession onNewSession(Bundle args) {
        L.i("VIS_NEW_SESSION");
        return new ProbeVoiceInteractionSession(this);
    }
}

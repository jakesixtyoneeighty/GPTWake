package com.desmond.gptwake;

/** Runtime switches for the measurement campaign. All default to the production behaviour. */
public final class Cfg {

    /** KWS hits are logged but never hand off to ChatGPT. For recall / false-activation runs. */
    public static volatile boolean evalMode = false;

    /**
     * Power baseline A: capture, PCM conversion, RMS and statistics run exactly as in the full
     * pipeline and the model stays loaded, but acceptWaveform/decode are never called.
     */
    public static volatile boolean micOnly = false;

    /** Duplicate-hit suppression window, also the eval-mode refractory period. */
    public static volatile long refractoryMs = 2000;

    /** Periodic power / memory sampling. */
    public static volatile boolean powerLog = false;
    public static volatile long powerLogIntervalMs = 10_000;

    private Cfg() {
    }
}

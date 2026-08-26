package com.desmond.gptwake;

import android.content.Context;
import android.content.SharedPreferences;

/** Persisted wake phrase, kept in device-protected storage so it survives Direct Boot. */
public final class WakeWordStore {

    public static final String DEFAULT_PHRASE = "open sesame";
    /** Matches assets/kws/keywords.txt, used when nothing custom is stored. */
    public static final String DEFAULT_LINE = "OW1 P AH0 N S EH1 S AH0 M IY0 @open_sesame";

    private static final String LEGACY_DEFAULT_PHRASE = "芝麻开门";

    private static SharedPreferences sp(Context c) {
        return c.createDeviceProtectedStorageContext()
                .getSharedPreferences("wakeword", Context.MODE_PRIVATE);
    }

    public static String phrase(Context c) {
        migrateLegacyDefault(c);
        return sp(c).getString("phrase", DEFAULT_PHRASE);
    }

    public static String keywordLine(Context c) {
        migrateLegacyDefault(c);
        return sp(c).getString("line", DEFAULT_LINE);
    }

    public static void save(Context c, String phrase, String line) {
        sp(c).edit().putString("phrase", phrase).putString("line", line).commit();
        L.i("WAKEWORD_SAVED phrase=" + phrase + " line=" + line);
    }

    public static void reset(Context c) {
        sp(c).edit().remove("phrase").remove("line").commit();
        L.i("WAKEWORD_RESET");
    }

    /**
     * Existing installs shipped with the Chinese default. Leave any phrase the user actually
     * chose alone; only rewrite the old stock default so English speech can match.
     */
    private static void migrateLegacyDefault(Context c) {
        SharedPreferences p = sp(c);
        if (!LEGACY_DEFAULT_PHRASE.equals(p.getString("phrase", null))) return;
        p.edit().putString("phrase", DEFAULT_PHRASE).putString("line", DEFAULT_LINE).commit();
        L.i("WAKEWORD_MIGRATED_LEGACY_DEFAULT to=" + DEFAULT_PHRASE);
    }

    private WakeWordStore() {
    }
}

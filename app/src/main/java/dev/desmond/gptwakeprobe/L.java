package dev.desmond.gptwakeprobe;

import android.util.Log;
import java.util.ArrayDeque;

public final class L {
    public static final String TAG = "GPTWAKE";
    private static final ArrayDeque<String> RING = new ArrayDeque<>();

    public static synchronized void i(String m) {
        Log.i(TAG, m);
        push("I " + m);
    }

    public static synchronized void e(String m, Throwable t) {
        Log.e(TAG, m, t);
        push("E " + m + " :: " + (t == null ? "null" : t.toString()));
    }

    private static void push(String s) {
        RING.addLast(s);
        while (RING.size() > 200) RING.removeFirst();
    }

    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (String s : RING) sb.append(s).append('\n');
        return sb.toString();
    }
}

package dev.desmond.gptwakeprobe;

import android.os.Debug;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-thread CPU accounting. Debug.threadCpuTimeNanos() only reports the calling thread, so each
 * controlled thread publishes its own cumulative value and the sampler differences those.
 * Threads we do not own (ONNX Runtime workers, ART) are covered by the /proc/self/task sweep.
 */
public final class ThreadCpu {

    private static final ConcurrentHashMap<String, Long> CUM_NS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> PREV_NS = new ConcurrentHashMap<>();

    /** Must be called from inside the thread being measured. */
    public static void publish(String name) {
        CUM_NS.put(name, Debug.threadCpuTimeNanos());
    }

    /** Window deltas in ms since the previous call, keyed by thread name. */
    public static Map<String, Long> deltasMs() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : CUM_NS.entrySet()) {
            long now = e.getValue();
            Long prev = PREV_NS.put(e.getKey(), now);
            out.put(e.getKey(), prev == null ? -1 : (now - prev) / 1_000_000L);
        }
        return out;
    }

    /** Whole-process breakdown, including threads we cannot instrument. utime+stime in ms. */
    public static Map<String, Long> procTaskCpuMs() {
        Map<String, Long> out = new LinkedHashMap<>();
        File tasks = new File("/proc/self/task");
        File[] tids = tasks.listFiles();
        if (tids == null) return out;
        for (File t : tids) {
            String comm = readLine(new File(t, "comm"));
            String stat = readLine(new File(t, "stat"));
            if (comm == null || stat == null) continue;
            try {
                int close = stat.lastIndexOf(')');
                String[] f = stat.substring(close + 2).split("\\s+");
                long ticks = Long.parseLong(f[11]) + Long.parseLong(f[12]);   // utime, stime
                out.put(comm.trim() + ":" + t.getName(), ticks * 10);          // USER_HZ = 100
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static String readLine(File f) {
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            return r.readLine();
        } catch (Throwable t) {
            return null;
        }
    }

    private ThreadCpu() {
    }
}

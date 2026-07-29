package dev.desmond.gptwakeprobe;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Debug;
import android.os.Process;
import android.os.SystemClock;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Battery and CPU every 10 s; PSS-class memory every 60 s (it is far more expensive to collect).
 * Full meminfo is captured outside the app, before and after each run.
 */
public final class PowerLogger {

    private static final ScheduledExecutorService EXEC =
            Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "power-logger"));
    private static ScheduledFuture<?> task;
    private static long lastWallMs;
    private static long lastCpuMs;
    private static long lastPssMs;
    private static long seq;

    private static String num(BatteryManager bm, int id, String name) {
        try {
            long v = bm.getLongProperty(id);
            if (v == Long.MIN_VALUE) return "\"" + name + "\":\"UNSUPPORTED\"";
            return "\"" + name + "\":" + v;
        } catch (Throwable t) {
            return "\"" + name + "\":\"UNSUPPORTED\"";
        }
    }

    public static synchronized void start(Context c, String tag) {
        stop();
        Context app = c.getApplicationContext();
        lastWallMs = SystemClock.elapsedRealtime();
        lastCpuMs = Process.getElapsedCpuTime();
        lastPssMs = 0;
        seq = 0;
        L.i("POWERLOG_START tag=" + tag + " intervalMs=" + Cfg.powerLogIntervalMs);
        task = EXEC.scheduleAtFixedRate(() -> sample(app, tag),
                Cfg.powerLogIntervalMs, Cfg.powerLogIntervalMs, TimeUnit.MILLISECONDS);
    }

    public static synchronized void stop() {
        if (task != null) {
            task.cancel(false);
            task = null;
            L.i("POWERLOG_STOP");
        }
    }

    private static void sample(Context app, String tag) {
        try {
            ThreadCpu.publish("power-logger");

            long nowWall = SystemClock.elapsedRealtime();
            long nowCpu = Process.getElapsedCpuTime();
            long wallDelta = nowWall - lastWallMs;
            long cpuDelta = nowCpu - lastCpuMs;
            lastWallMs = nowWall;
            lastCpuMs = nowCpu;

            BatteryManager bm = app.getSystemService(BatteryManager.class);
            Intent bi = app.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int volt = bi == null ? -1 : bi.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
            int temp = bi == null ? -1 : bi.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
            int level = bi == null ? -1 : bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int plugged = bi == null ? -1 : bi.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);

            StringBuilder threads = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Long> e : ThreadCpu.deltasMs().entrySet()) {
                if (!first) threads.append(',');
                first = false;
                threads.append('"').append(e.getKey()).append("\":").append(e.getValue());
            }
            threads.append('}');

            StringBuilder body = new StringBuilder();
            body.append("\"tag\":\"").append(tag).append('"')
                .append(",\"wallDeltaMs\":").append(wallDelta)
                .append(",\"cpuDeltaMs\":").append(cpuDelta)
                .append(String.format(",\"cpuOneCorePct\":%.2f",
                        wallDelta > 0 ? cpuDelta * 100.0 / wallDelta : 0))
                .append(",\"threadCpuDeltaMs\":").append(threads)
                .append(',').append(num(bm, BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER, "chargeUAh"))
                .append(',').append(num(bm, BatteryManager.BATTERY_PROPERTY_CURRENT_NOW, "currentNowUA"))
                .append(',').append(num(bm, BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE, "currentAvgUA"))
                .append(',').append(num(bm, BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER, "energyNWh"))
                .append(",\"voltageMv\":").append(volt)
                .append(",\"tempDeciC\":").append(temp)
                .append(",\"levelPct\":").append(level)
                .append(",\"plugged\":").append(plugged)
                .append(",\"rssKb\":").append(Sys.rssKb());

            // PSS is comparatively expensive: sample it once a minute, not every 10 s.
            if (nowWall - lastPssMs >= 60_000) {
                lastPssMs = nowWall;
                Debug.MemoryInfo mi = new Debug.MemoryInfo();
                Debug.getMemoryInfo(mi);
                body.append(",\"totalPssKb\":").append(mi.getTotalPss())
                    .append(",\"privateDirtyKb\":").append(mi.getTotalPrivateDirty())
                    .append(",\"privateCleanKb\":").append(mi.getTotalPrivateClean())
                    .append(",\"swapPssKb\":").append(mi.getTotalSwappablePss())
                    .append(",\"nativeHeapAllocKb\":").append(Debug.getNativeHeapAllocatedSize() / 1024);

                StringBuilder tasks = new StringBuilder("{");
                boolean f2 = true;
                for (Map.Entry<String, Long> e : ThreadCpu.procTaskCpuMs().entrySet()) {
                    if (!f2) tasks.append(',');
                    f2 = false;
                    tasks.append('"').append(e.getKey().replace('"', '_')).append("\":").append(e.getValue());
                }
                tasks.append('}');
                body.append(",\"procTaskCpuMsCumulative\":").append(tasks);
            }

            Measure.event("SAMPLE", body.toString());

            if (++seq % 6 == 0) {   // one logcat line per minute, the file stays authoritative
                L.i("POWER tag=" + tag + " seq=" + seq
                        + String.format(" cpuOneCorePct=%.2f", wallDelta > 0 ? cpuDelta * 100.0 / wallDelta : 0)
                        + " levelPct=" + level + " tempDeciC=" + temp + " rssKb=" + Sys.rssKb());
            }
        } catch (Throwable t) {
            L.e("POWER_SAMPLE_FAIL", t);
        }
    }

    private PowerLogger() {
    }
}

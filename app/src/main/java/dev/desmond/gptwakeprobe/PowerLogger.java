package dev.desmond.gptwakeprobe;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Debug;
import android.os.Process;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Samples battery counters, process CPU deltas and PSS-based memory on a fixed cadence.
 * RSS alone is not a usable memory figure for a process that maps a large ONNX runtime.
 */
public final class PowerLogger {

    private static final ScheduledExecutorService EXEC =
            Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "power-log"));
    private static ScheduledFuture<?> task;
    private static long lastWallMs;
    private static long lastCpuMs;

    private static String prop(BatteryManager bm, int id, String name) {
        try {
            long v = bm.getLongProperty(id);
            if (v == Long.MIN_VALUE) return name + "=UNSUPPORTED";
            return name + "=" + v;
        } catch (Throwable t) {
            return name + "=UNSUPPORTED";
        }
    }

    public static synchronized void start(Context c, String tag) {
        stop();
        Context app = c.getApplicationContext();
        lastWallMs = System.currentTimeMillis();
        lastCpuMs = Process.getElapsedCpuTime();
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
            long nowWall = System.currentTimeMillis();
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

            Debug.MemoryInfo mi = new Debug.MemoryInfo();
            Debug.getMemoryInfo(mi);

            L.i("POWER tag=" + tag
                    + " wallDeltaMs=" + wallDelta
                    + " cpuDeltaMs=" + cpuDelta
                    + String.format(" cpuOneCorePct=%.2f", wallDelta > 0 ? cpuDelta * 100.0 / wallDelta : 0)
                    + " captureThreadCpuMs=" + AudioProbe.captureThreadCpuMs()
                    + " | " + prop(bm, BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER, "chargeUAh")
                    + " " + prop(bm, BatteryManager.BATTERY_PROPERTY_CURRENT_NOW, "currentNowUA")
                    + " " + prop(bm, BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE, "currentAvgUA")
                    + " " + prop(bm, BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER, "energyNWh")
                    + " voltageMv=" + volt + " tempDeciC=" + temp
                    + " levelPct=" + level + " plugged=" + plugged
                    + " | totalPssKb=" + mi.getTotalPss()
                    + " privateDirtyKb=" + mi.getTotalPrivateDirty()
                    + " privateCleanKb=" + mi.getTotalPrivateClean()
                    + " swapPssKb=" + mi.getTotalSwappablePss()
                    + " nativeHeapAllocKb=" + (Debug.getNativeHeapAllocatedSize() / 1024)
                    + " rssKb=" + Sys.rssKb());
        } catch (Throwable t) {
            L.e("POWER_SAMPLE_FAIL", t);
        }
    }

    private PowerLogger() {
    }
}

package dev.desmond.gptwakeprobe;

import android.os.Process;
import java.io.BufferedReader;
import java.io.FileReader;

public final class Sys {

    public static long rssKb() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/status"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("VmRSS:")) {
                    return Long.parseLong(line.replaceAll("[^0-9]", ""));
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    public static long cpuMs() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/stat"))) {
            String[] f = r.readLine().split("\\s+");
            long ticks = Long.parseLong(f[13]) + Long.parseLong(f[14]);
            return ticks * 1000 / 100;   // USER_HZ is 100 on Android
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private Sys() {
    }
}

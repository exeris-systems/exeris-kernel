/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.locks.LockSupport;

/**
 * Measures CPU time consumed by scheduler carrier threads in three phases:
 * idle, timed-sleep load (rate x sleepMs, like the pilot's /delayed), and idle again.
 * Run once with the custom scheduler and once without it (stock ForkJoinPool).
 */
public class CarrierIdleProbe {
    public static void main(String[] args) throws Exception {
        int rate = Integer.parseInt(args.length > 0 ? args[0] : "3000");
        long sleepMs = Long.parseLong(args.length > 1 ? args[1] : "20");
        long phaseMs = Long.parseLong(args.length > 2 ? args[2] : "3000");

        // start the scheduler (and its carriers) and warm up
        Thread.ofVirtual().start(() -> { }).join();
        load(rate, sleepMs, 2000);
        Thread.sleep(500);

        report("idle-1", measure(() -> sleepQuietly(phaseMs)), phaseMs);
        report("load  ", measure(() -> load(rate, sleepMs, phaseMs)), phaseMs);
        Thread.sleep(200);
        report("idle-2", measure(() -> sleepQuietly(phaseMs)), phaseMs);
    }

    static void load(int rate, long sleepMs, long durationMs) {
        long intervalNs = 1_000_000_000L / rate;
        long start = System.nanoTime();
        long next = start;
        long end = start + durationMs * 1_000_000L;
        while (next < end) {
            Thread.ofVirtual().start(() -> {
                byte[] state = new byte[8192];
                for (int i = 0; i < state.length; i += 64) {
                    state[i] = (byte) i;
                }
                sleepQuietly(sleepMs);
            });
            next += intervalNs;
            long wait = next - System.nanoTime();
            if (wait > 0) {
                LockSupport.parkNanos(wait);
            }
        }
    }

    static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static Map<String, Long> measure(Runnable phase) {
        Map<String, Long> before = carrierCpu();
        phase.run();
        Map<String, Long> after = carrierCpu();
        Map<String, Long> delta = new TreeMap<>();
        after.forEach((name, ns) -> delta.put(name, ns - before.getOrDefault(name, 0L)));
        return delta;
    }

    static Map<String, Long> carrierCpu() {
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        Map<String, Long> out = new TreeMap<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            String n = t.getName();
            if (n.startsWith("exeris-carrier-") || n.startsWith("ForkJoinPool-")) {
                out.put(n, mx.getThreadCpuTime(t.threadId()));
            }
        }
        return out;
    }

    static void report(String phase, Map<String, Long> delta, long phaseMs) {
        StringBuilder sb = new StringBuilder(phase).append(":");
        double total = 0;
        for (var e : delta.entrySet()) {
            double pct = 100.0 * e.getValue() / (phaseMs * 1_000_000.0);
            total += pct;
            sb.append(String.format(" %s=%.1f%%", e.getKey(), pct));
        }
        sb.append(String.format("  total=%.1f%%", total));
        System.out.println(sb);
    }
}

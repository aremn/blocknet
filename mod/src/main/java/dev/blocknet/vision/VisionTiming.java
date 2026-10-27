// SPDX-License-Identifier: MIT
package dev.blocknet.vision;

import java.util.Arrays;
import java.util.Locale;

/** Bounded rolling samples, including cold-start and GC stalls (no outlier filtering). */
public final class VisionTiming {
    private final double[] ticks = new double[4096], frames = new double[512], detailFrames = new double[512];
    private int tickCount, frameCount, detailCount;
    private double maximum;
    public void tick(long nanos) {
        double ms = nanos / 1_000_000.0;
        ticks[tickCount++ % ticks.length] = ms;
        maximum = Math.max(maximum, ms);
    }
    public void frame(long nanos, boolean detail) {
        if (detail) detailFrames[detailCount++ % detailFrames.length] = nanos / 1_000_000.0;
        else frames[frameCount++ % frames.length] = nanos / 1_000_000.0;
    }
    public int frameCount() { return frameCount + detailCount; }
    private static double percentile(double[] samples, int count, double p) {
        if (count == 0) return 0;
        double[] values = Arrays.copyOf(samples, Math.min(count, samples.length));
        Arrays.sort(values);
        return values[Math.min(values.length - 1, (int) Math.ceil(p * values.length) - 1)];
    }
    public double tickP95() { return percentile(ticks, tickCount, .95); }
    public double maxTick() { return maximum; }
    public String report() {
        return String.format(Locale.ROOT, "vision ms: tick p50=%.3f p95=%.3f max=%.3f; frame 64x36 p50=%.3f p95=%.3f (n=%d); frame 128x72 p50=%.3f p95=%.3f (n=%d); budget=%.3f",
            percentile(ticks, tickCount, .5), tickP95(), maximum, percentile(frames, frameCount, .5),
            percentile(frames, frameCount, .95), frameCount, percentile(detailFrames, detailCount, .5),
            percentile(detailFrames, detailCount, .95), detailCount, VisionSensor.BUDGET_NANOS / 1_000_000.0);
    }
}

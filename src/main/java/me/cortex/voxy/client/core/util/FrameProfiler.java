package me.cortex.voxy.client.core.util;

import me.cortex.voxy.client.core.gl.BufferTextureMirror;
import me.cortex.voxy.common.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL11C.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11C.glFinish;
import static org.lwjgl.opengl.GL11C.glGetError;

/**
 * Coarse per stage frame profiler for drivers without usable timer queries (e.g. GLES backed ones).
 * <p>
 * Enabled with -Dvoxy.profileFrames=true. Every {@link #mark} waits for the gpu to finish (glFinish) and attributes the
 * time since the previous mark to the given label, so the numbers are CPU+GPU wall time per stage and the frame rate
 * will be lower while profiling. A summary is logged every few seconds.
 */
public final class FrameProfiler {
    private FrameProfiler() {}

    public static final boolean ENABLED = Boolean.getBoolean("voxy.profileFrames");
    private static final long LOG_INTERVAL_NS = 3_000_000_000L;

    private static final Map<String, long[]> TOTALS = new LinkedHashMap<>();
    private static long last;
    private static long windowStart = System.nanoTime();
    private static int frames;
    private static int errorLogsLeft = 20;

    /** Marks the start of a frame (does not wait for the gpu) */
    public static void start() {
        if (!ENABLED) return;
        last = System.nanoTime();
    }

    /** Waits for the gpu and attributes the time since the previous mark to this label */
    public static void mark(String label) {
        if (!ENABLED) return;
        glFinish();
        long now = System.nanoTime();
        TOTALS.computeIfAbsent(label, k -> new long[1])[0] += now - last;
        last = now;
    }

    public static void frameEnd() {
        if (!ENABLED) return;
        mark("finish");
        frames++;
        //Report gl errors raised during the frame, they point at invalid mirror copies/bindings
        int error;
        while ((error = glGetError()) != GL_NO_ERROR && errorLogsLeft > 0) {
            errorLogsLeft--;
            Logger.error("GL error during voxy frame: 0x" + Integer.toHexString(error));
        }
        long now = System.nanoTime();
        if (now - windowStart >= LOG_INTERVAL_NS && frames > 0) {
            StringBuilder sb = new StringBuilder();
            double total = 0;
            for (var entry : TOTALS.entrySet()) {
                double ms = entry.getValue()[0] / 1_000_000.0 / frames;
                total += ms;
                sb.append(String.format(" %s=%.1fms", entry.getKey(), ms));
            }
            Logger.info(String.format("Voxy profile: %d frames, %.1fms/frame (voxy only):%s | mirror copies/frame=%.1f, mirror MB/frame=%.2f",
                    frames, total, sb, BufferTextureMirror.takeCopyCount() / (double) frames, BufferTextureMirror.takeCopyBytes() / (double) frames / (1024.0 * 1024.0)));
            TOTALS.clear();
            frames = 0;
            windowStart = now;
        }
    }
}

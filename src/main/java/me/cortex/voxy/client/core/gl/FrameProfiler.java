package me.cortex.voxy.client.core.gl;

import me.cortex.voxy.common.Logger;
import org.lwjgl.opengl.GL11C;

import java.util.LinkedHashMap;
import java.util.Map;

//Cheap per stage cpu+gpu timing (glFinish per stage), only active on the vertex data texture path or with -Dvoxy.profileFrames=true
public class FrameProfiler {
    public static final boolean ENABLED = Boolean.getBoolean("voxy.profileFrames") || Capabilities.INSTANCE.vertexDataTextures;
    private static final Map<String, long[]> STAGES = new LinkedHashMap<>();
    private static long last = System.nanoTime();
    private static long stageStart = last;
    private static int frames = 0;

    public static void begin() {
        if (!ENABLED) return;
        GL11C.glFinish();
        stageStart = System.nanoTime();
    }

    public static void stage(String name) {
        if (!ENABLED) return;
        GL11C.glFinish();
        long now = System.nanoTime();
        long[] s = STAGES.computeIfAbsent(name, k -> new long[2]);
        s[0] += now - stageStart;
        s[1] = Math.max(s[1], now - stageStart);
        int err = GL11C.glGetError();
        if (err != 0) {
            Logger.error("GL error 0x" + Integer.toHexString(err) + " after stage " + name);
        }
        stageStart = System.nanoTime();
    }

    public static void endFrame() {
        if (!ENABLED) return;
        frames++;
        long now = System.nanoTime();
        if (now - last < 3_000_000_000L) return;
        StringBuilder sb = new StringBuilder("Profile over " + frames + " frames (avg/max ms):");
        for (var e : STAGES.entrySet()) {
            sb.append(' ').append(e.getKey()).append('=')
              .append(String.format("%.2f/%.2f", e.getValue()[0] / 1e6 / frames, e.getValue()[1] / 1e6));
        }
        Logger.info(sb.toString());
        STAGES.clear();
        frames = 0;
        last = now;
    }
}

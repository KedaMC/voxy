package me.cortex.voxy.common.util;

import org.joml.*;
import org.lwjgl.system.MemoryUtil;

//JOML's getToAddress/setFromAddress go through its Unsafe based MemUtil, which fails to initialise on some JVMs
// (e.g. the Android launcher JVM, "Unexpected Matrix4f element offset"), so write via LWJGL's MemoryUtil instead
public final class JomlMem {
    private JomlMem() {}

    public static void put(Matrix4f m, long ptr) {
        MemoryUtil.memPutFloat(ptr, m.m00()); MemoryUtil.memPutFloat(ptr + 4, m.m01()); MemoryUtil.memPutFloat(ptr + 8, m.m02()); MemoryUtil.memPutFloat(ptr + 12, m.m03());
        MemoryUtil.memPutFloat(ptr + 16, m.m10()); MemoryUtil.memPutFloat(ptr + 20, m.m11()); MemoryUtil.memPutFloat(ptr + 24, m.m12()); MemoryUtil.memPutFloat(ptr + 28, m.m13());
        MemoryUtil.memPutFloat(ptr + 32, m.m20()); MemoryUtil.memPutFloat(ptr + 36, m.m21()); MemoryUtil.memPutFloat(ptr + 40, m.m22()); MemoryUtil.memPutFloat(ptr + 44, m.m23());
        MemoryUtil.memPutFloat(ptr + 48, m.m30()); MemoryUtil.memPutFloat(ptr + 52, m.m31()); MemoryUtil.memPutFloat(ptr + 56, m.m32()); MemoryUtil.memPutFloat(ptr + 60, m.m33());
    }

    public static void put(Vector2f v, long ptr) {
        MemoryUtil.memPutFloat(ptr, v.x); MemoryUtil.memPutFloat(ptr + 4, v.y);
    }

    public static void put(Vector3f v, long ptr) {
        MemoryUtil.memPutFloat(ptr, v.x); MemoryUtil.memPutFloat(ptr + 4, v.y); MemoryUtil.memPutFloat(ptr + 8, v.z);
    }

    public static void put(Vector4f v, long ptr) {
        MemoryUtil.memPutFloat(ptr, v.x); MemoryUtil.memPutFloat(ptr + 4, v.y); MemoryUtil.memPutFloat(ptr + 8, v.z); MemoryUtil.memPutFloat(ptr + 12, v.w);
    }

    public static void put(Vector2i v, long ptr) {
        MemoryUtil.memPutInt(ptr, v.x); MemoryUtil.memPutInt(ptr + 4, v.y);
    }

    public static void put(Vector3i v, long ptr) {
        MemoryUtil.memPutInt(ptr, v.x); MemoryUtil.memPutInt(ptr + 4, v.y); MemoryUtil.memPutInt(ptr + 8, v.z);
    }

    public static void set(Vector3f v, long ptr) {
        v.x = MemoryUtil.memGetFloat(ptr); v.y = MemoryUtil.memGetFloat(ptr + 4); v.z = MemoryUtil.memGetFloat(ptr + 8);
    }

    public static void set(Vector4f v, long ptr) {
        v.x = MemoryUtil.memGetFloat(ptr); v.y = MemoryUtil.memGetFloat(ptr + 4); v.z = MemoryUtil.memGetFloat(ptr + 8); v.w = MemoryUtil.memGetFloat(ptr + 12);
    }
}

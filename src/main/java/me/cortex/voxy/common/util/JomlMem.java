package me.cortex.voxy.common.util;

import org.joml.*;
import org.lwjgl.system.MemoryUtil;

/**
 * Writes/reads JOML types to raw memory addresses.
 * <p>
 * Replaces JOML's own {@code getToAddress}/{@code setFromAddress}, which go through JOML's Unsafe based accessor.
 * That accessor verifies the field layout of the JOML classes when it is first loaded and refuses to initialise when the
 * JVM lays the fields out differently than expected (seen on the JVM used by Android launchers:
 * "Unexpected Matrix4f element offset"), after which every call throws NoClassDefFoundError.
 * LWJGL's MemoryUtil does not depend on object layouts, so it is safe everywhere.
 * <p>
 * Layout matches JOML: matrices are column major (m00, m01, m02, m03, m10, ...), vectors are x, y, z, w.
 */
public final class JomlMem {
    private JomlMem() {}

    public static void put(Matrix4fc m, long ptr) {
        MemoryUtil.memPutFloat(ptr, m.m00());
        MemoryUtil.memPutFloat(ptr + 4, m.m01());
        MemoryUtil.memPutFloat(ptr + 8, m.m02());
        MemoryUtil.memPutFloat(ptr + 12, m.m03());
        MemoryUtil.memPutFloat(ptr + 16, m.m10());
        MemoryUtil.memPutFloat(ptr + 20, m.m11());
        MemoryUtil.memPutFloat(ptr + 24, m.m12());
        MemoryUtil.memPutFloat(ptr + 28, m.m13());
        MemoryUtil.memPutFloat(ptr + 32, m.m20());
        MemoryUtil.memPutFloat(ptr + 36, m.m21());
        MemoryUtil.memPutFloat(ptr + 40, m.m22());
        MemoryUtil.memPutFloat(ptr + 44, m.m23());
        MemoryUtil.memPutFloat(ptr + 48, m.m30());
        MemoryUtil.memPutFloat(ptr + 52, m.m31());
        MemoryUtil.memPutFloat(ptr + 56, m.m32());
        MemoryUtil.memPutFloat(ptr + 60, m.m33());
    }

    public static void put(Vector2fc v, long ptr) {
        MemoryUtil.memPutFloat(ptr, v.x());
        MemoryUtil.memPutFloat(ptr + 4, v.y());
    }

    public static void put(Vector3fc v, long ptr) {
        MemoryUtil.memPutFloat(ptr, v.x());
        MemoryUtil.memPutFloat(ptr + 4, v.y());
        MemoryUtil.memPutFloat(ptr + 8, v.z());
    }

    public static void put(Vector4fc v, long ptr) {
        MemoryUtil.memPutFloat(ptr, v.x());
        MemoryUtil.memPutFloat(ptr + 4, v.y());
        MemoryUtil.memPutFloat(ptr + 8, v.z());
        MemoryUtil.memPutFloat(ptr + 12, v.w());
    }

    public static void put(Vector2ic v, long ptr) {
        MemoryUtil.memPutInt(ptr, v.x());
        MemoryUtil.memPutInt(ptr + 4, v.y());
    }

    public static void put(Vector3ic v, long ptr) {
        MemoryUtil.memPutInt(ptr, v.x());
        MemoryUtil.memPutInt(ptr + 4, v.y());
        MemoryUtil.memPutInt(ptr + 8, v.z());
    }

    public static void get(Vector3f dest, long ptr) {
        dest.x = MemoryUtil.memGetFloat(ptr);
        dest.y = MemoryUtil.memGetFloat(ptr + 4);
        dest.z = MemoryUtil.memGetFloat(ptr + 8);
    }

    public static void get(Vector4f dest, long ptr) {
        dest.x = MemoryUtil.memGetFloat(ptr);
        dest.y = MemoryUtil.memGetFloat(ptr + 4);
        dest.z = MemoryUtil.memGetFloat(ptr + 8);
        dest.w = MemoryUtil.memGetFloat(ptr + 12);
    }
}

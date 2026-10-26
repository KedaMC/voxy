package me.cortex.voxy.client.core.rendering.util;

import me.cortex.voxy.client.core.gl.BufferTextureMirror;
import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.gl.shader.Shader;

import static org.lwjgl.opengl.GL33C.glBindSampler;
import static org.lwjgl.opengl.GL45C.glBindTextureUnit;

/**
 * Constants and helpers for the texture mirrors of the buffers the vertex shaders read.
 * Only used when {@link Capabilities#vertexDataTextures} is set (vertex stage has no shader storage buffer support).
 * The shader side addressing lives in lod/gl46/bindings.glsl and must match these values (they are passed in as defines).
 */
public final class VertexDataTextures {
    private VertexDataTextures() {}

    public static boolean enabled() {
        return Capabilities.INSTANCE.vertexDataTextures;
    }

    //Texture units, picked high to not collide with the units used by the pipelines (0-2 and the lightmap)
    public static final int QUAD_UNIT = 12;
    public static final int MODEL_UNIT = 13;
    public static final int COLOUR_UNIT = 14;
    public static final int POSITION_UNIT = 15;

    //The culling vertex shader uses its own program, so it reuses the same units
    public static final int SECTION_UNIT = 12;
    public static final int VISIBILITY_UNIT = 13;
    public static final int LOOKUP_UNIT = 14;

    //Section metadata is 32 bytes = 2 RGBA32UI texels, visibility and the indirect lookup are single R32UI texels
    public static final int SECTION_LOG2_WIDTH = 12;
    public static final int VISIBILITY_LOG2_WIDTH = 12;
    public static final int LOOKUP_LOG2_WIDTH = 12;

    //Quads are 8 bytes (RG32UI), 8192 texels wide = 64KB rows
    public static final int QUAD_LOG2_WIDTH = 13;
    //Models are 64 bytes = 4 RGBA32UI texels
    public static final int MODEL_LOG2_WIDTH = 12;
    public static final int COLOUR_LOG2_WIDTH = 12;
    //Section positions are 8 bytes (RG32UI)
    public static final int POSITION_LOG2_WIDTH = 12;

    public static final int QUAD_ROW_BYTES = 8 << QUAD_LOG2_WIDTH;
    public static final int POSITION_ROW_BYTES = 8 << POSITION_LOG2_WIDTH;

    public static final int POSITION_COUNT = 400_000;
    //The position buffer must be a whole number of rows so the entire texture can be copied from it
    public static final long POSITION_BUFFER_SIZE = alignUp(8L * POSITION_COUNT, POSITION_ROW_BYTES);

    public static void applyDefines(Shader.Builder<?> builder) {
        builder.define("VERTEX_DATA_TEXTURES")
                .define("QUAD_TEXTURE_BINDING", QUAD_UNIT)
                .define("MODEL_TEXTURE_BINDING", MODEL_UNIT)
                .define("COLOUR_TEXTURE_BINDING", COLOUR_UNIT)
                .define("POSITION_TEXTURE_BINDING", POSITION_UNIT)
                .define("QUAD_TEXTURE_LOG2_WIDTH", QUAD_LOG2_WIDTH)
                .define("MODEL_TEXTURE_LOG2_WIDTH", MODEL_LOG2_WIDTH)
                .define("COLOUR_TEXTURE_LOG2_WIDTH", COLOUR_LOG2_WIDTH)
                .define("POSITION_TEXTURE_LOG2_WIDTH", POSITION_LOG2_WIDTH)
                .define("SECTION_TEXTURE_BINDING", SECTION_UNIT)
                .define("VISIBILITY_TEXTURE_BINDING", VISIBILITY_UNIT)
                .define("LOOKUP_TEXTURE_BINDING", LOOKUP_UNIT)
                .define("SECTION_TEXTURE_LOG2_WIDTH", SECTION_LOG2_WIDTH)
                .define("VISIBILITY_TEXTURE_LOG2_WIDTH", VISIBILITY_LOG2_WIDTH)
                .define("LOOKUP_TEXTURE_LOG2_WIDTH", LOOKUP_LOG2_WIDTH);
    }

    public static void unbind() {
        for (int unit = QUAD_UNIT; unit <= POSITION_UNIT; unit++) {
            glBindSampler(unit, 0);
            glBindTextureUnit(unit, 0);
        }
    }

    public static BufferTextureMirror createPositionMirror() {
        return new BufferTextureMirror(BufferTextureMirror.Format.RG32UI, POSITION_LOG2_WIDTH, POSITION_BUFFER_SIZE / 8);
    }

    private static long alignUp(long value, long alignment) {
        return ((value + alignment - 1) / alignment) * alignment;
    }
}

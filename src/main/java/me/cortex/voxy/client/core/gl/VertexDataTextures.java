package me.cortex.voxy.client.core.gl;

import me.cortex.voxy.client.core.gl.shader.Shader;

//Shared constants for reading buffer data in vertex shaders through mirror textures.
// Needed on drivers where the vertex stage supports no (or too few) shader storage blocks, e.g. Mali on Android
public final class VertexDataTextures {
    private VertexDataTextures() {}

    public static boolean enabled() {
        return Capabilities.INSTANCE.vertexDataTextures;
    }

    //Terrain vertex shader (quads3.vert)
    public static final int UNIT_QUAD = 12;
    public static final int UNIT_POSITION = 13;
    public static final int UNIT_MODEL = 14;
    public static final int UNIT_COLOUR = 15;
    //Occlusion cull raster vertex shader (cull/raster.vert)
    public static final int UNIT_SECTION = 12;
    public static final int UNIT_LOOKUP = 13;
    public static final int UNIT_VISIBILITY = 14;
    //Chunk outline vertex shader
    public static final int UNIT_CHUNK_POS = 12;

    //Width of each mirror texture, as log2 of texels
    public static final int SHIFT_QUAD = 13;//8 byte texels
    public static final int SHIFT_POSITION = 11;//8 byte texels
    public static final int SHIFT_MODEL = 9;//16 byte texels, 4 per model
    public static final int SHIFT_COLOUR = 9;//4 byte texels
    public static final int SHIFT_SECTION = 10;//16 byte texels, 2 per section
    public static final int SHIFT_LOOKUP = 10;//4 byte texels
    public static final int SHIFT_VISIBILITY = 11;//4 byte texels
    public static final int SHIFT_CHUNK_POS = 11;//8 byte texels

    public static <T extends Shader> Shader.Builder<T> applyTerrain(Shader.Builder<T> builder) {
        return builder.defineIf("VERTEX_DATA_TEXTURES", enabled())
                .defineIf("QUAD_TEXTURE_BINDING", enabled(), UNIT_QUAD)
                .defineIf("QUAD_TEXTURE_SHIFT", enabled(), SHIFT_QUAD)
                .defineIf("POSITION_TEXTURE_BINDING", enabled(), UNIT_POSITION)
                .defineIf("POSITION_TEXTURE_SHIFT", enabled(), SHIFT_POSITION)
                .defineIf("MODEL_TEXTURE_BINDING", enabled(), UNIT_MODEL)
                .defineIf("MODEL_TEXTURE_SHIFT", enabled(), SHIFT_MODEL)
                .defineIf("MODEL_COLOUR_TEXTURE_BINDING", enabled(), UNIT_COLOUR)
                .defineIf("MODEL_COLOUR_TEXTURE_SHIFT", enabled(), SHIFT_COLOUR);
    }

    public static <T extends Shader> Shader.Builder<T> applyCull(Shader.Builder<T> builder) {
        return builder.defineIf("VERTEX_DATA_TEXTURES", enabled())
                .defineIf("SECTION_TEXTURE_BINDING", enabled(), UNIT_SECTION)
                .defineIf("SECTION_TEXTURE_SHIFT", enabled(), SHIFT_SECTION)
                .defineIf("INDIRECT_LOOKUP_TEXTURE_BINDING", enabled(), UNIT_LOOKUP)
                .defineIf("INDIRECT_LOOKUP_TEXTURE_SHIFT", enabled(), SHIFT_LOOKUP)
                .defineIf("VISIBILITY_TEXTURE_BINDING", enabled(), UNIT_VISIBILITY)
                .defineIf("VISIBILITY_TEXTURE_SHIFT", enabled(), SHIFT_VISIBILITY);
    }

    public static <T extends Shader> Shader.Builder<T> applyChunkOutline(Shader.Builder<T> builder) {
        return builder.defineIf("VERTEX_DATA_TEXTURES", enabled())
                .defineIf("CHUNK_POS_TEXTURE_BINDING", enabled(), UNIT_CHUNK_POS)
                .defineIf("CHUNK_POS_TEXTURE_SHIFT", enabled(), SHIFT_CHUNK_POS);
    }
}

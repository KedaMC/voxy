layout(binding = 0, std140) uniform SceneUniform {
    mat4 MVP;
    ivec3 baseSectionPos;
    uint frameId;
    vec3 cameraSubPos;
};

//TODO: see if making the stride 2*4*4 bytes or something cause you get that 16 byte write
struct DrawCommand {
    uint  count;
    uint  instanceCount;
    uint  firstIndex;
    int  baseVertex;
    uint  baseInstance;
};


#ifdef BLOCK_MODEL_TEXTURE_BINDING
layout(binding = BLOCK_MODEL_TEXTURE_BINDING) uniform sampler2D blockModelAtlas;
#endif


#ifndef Quad
#define Quad ivec2
#endif
#ifdef QUAD_BUFFER_BINDING
layout(binding = QUAD_BUFFER_BINDING, std430) readonly restrict buffer QuadBuffer {
    Quad quadData[];
};
#endif

#ifdef DRAW_BUFFER_BINDING
layout(binding = DRAW_BUFFER_BINDING, std430) writeonly restrict buffer DrawBuffer {
    DrawCommand cmdBuffer[];
};
#endif

#ifdef DRAW_COUNT_BUFFER_BINDING
layout(binding = DRAW_COUNT_BUFFER_BINDING, std430) restrict buffer DrawCommandCountBuffer {
    uint cmdGenDispatchX;
    uint cmdGenDispatchY;
    uint cmdGenDispatchZ;

    uint opaqueDrawCount;
    uint translucentDrawCount;
    uint temporalOpaqueDrawCount;

    DrawCommand cullDrawIndirectCommand;
};
#endif

#ifdef SECTION_METADATA_BUFFER_BINDING
layout(binding = SECTION_METADATA_BUFFER_BINDING, std430) readonly restrict buffer SectionBuffer {
    SectionMeta sectionData[];
};
#endif

#ifdef INDIRECT_SECTION_LOOKUP_BINDING
layout(binding = INDIRECT_SECTION_LOOKUP_BINDING, std430) readonly restrict buffer IndirectSectionLookupBuffer {
    uint sectionCount;
    uint indirectLookup[];
};
#endif

#ifndef VISIBILITY_ACCESS
#define VISIBILITY_ACCESS readonly
#endif
#ifdef VISIBILITY_BUFFER_BINDING
layout(binding = VISIBILITY_BUFFER_BINDING, std430) VISIBILITY_ACCESS restrict buffer VisibilityBuffer {
    uint visibilityData[];
};
#endif

#ifdef MODEL_BUFFER_BINDING
layout(binding = MODEL_BUFFER_BINDING, std430) readonly restrict buffer ModelBuffer {
    BlockModel modelData[];
};
#endif

#ifdef MODEL_COLOUR_BUFFER_BINDING
layout(binding = MODEL_COLOUR_BUFFER_BINDING, std430) readonly restrict buffer ModelColourBuffer {
    uint colourData[];
};
#endif

#ifdef POSITION_SCRATCH_BINDING
#ifndef POSITION_SCRATCH_ACCESS
#define POSITION_SCRATCH_ACCESS readonly
#endif
layout(binding = POSITION_SCRATCH_BINDING, std430) POSITION_SCRATCH_ACCESS restrict buffer PositionScratchBuffer {
    uvec2 positionBuffer[];
};
#endif



#ifdef VERTEX_STAGE_DATA//Defined by the vertex shaders that use the accessors below
//Vertex stage access to buffer data through mirror textures, for drivers without vertex stage shader storage blocks
// the *_TEXTURE_BINDING/*_TEXTURE_SHIFT defines come from the application (shift is log2 of the texture width in texels)
#define TEXEL_FETCH_LINEAR(tex, shift, idx) texelFetch(tex, ivec2(int((idx)&((1u<<(shift))-1u)), int((idx)>>(shift))), 0)

#ifdef QUAD_TEXTURE_BINDING
layout(binding = QUAD_TEXTURE_BINDING) uniform highp usampler2D quadTexture;
Quad loadQuad(uint idx) {
    uvec2 d = TEXEL_FETCH_LINEAR(quadTexture, QUAD_TEXTURE_SHIFT, idx).xy;
    #ifdef QUAD_DATA_USE_64_BIT
    return packUint2x32(d);
    #else
    return ivec2(d);
    #endif
}
#define LOAD_QUAD(i) loadQuad(i)
#else
#define LOAD_QUAD(i) quadData[i]
#endif

#ifdef POSITION_TEXTURE_BINDING
layout(binding = POSITION_TEXTURE_BINDING) uniform highp usampler2D positionTexture;
uvec2 loadPosition(uint idx) {
    return TEXEL_FETCH_LINEAR(positionTexture, POSITION_TEXTURE_SHIFT, idx).xy;
}
#define LOAD_POSITION(i) loadPosition(i)
#else
#define LOAD_POSITION(i) positionBuffer[i]
#endif

#ifdef MODEL_TEXTURE_BINDING
layout(binding = MODEL_TEXTURE_BINDING) uniform highp usampler2D modelTexture;
//A model is 4 texels (64 bytes), only the used fields are loaded
BlockModel loadModel(uint id) {
    uint base = id<<2;
    uvec4 a = TEXEL_FETCH_LINEAR(modelTexture, MODEL_TEXTURE_SHIFT, base);
    uvec4 b = TEXEL_FETCH_LINEAR(modelTexture, MODEL_TEXTURE_SHIFT, base+1u);
    uvec4 c = TEXEL_FETCH_LINEAR(modelTexture, MODEL_TEXTURE_SHIFT, base+2u);
    BlockModel m;
    m.faceData[0] = a.x; m.faceData[1] = a.y; m.faceData[2] = a.z; m.faceData[3] = a.w;
    m.faceData[4] = b.x; m.faceData[5] = b.y;
    m.flagsA = b.z;
    m.colourTint = b.w;
    m.customId = c.x;
    return m;
}
#define LOAD_MODEL(i) loadModel(i)
#else
#define LOAD_MODEL(i) modelData[i]
#endif

#ifdef MODEL_COLOUR_TEXTURE_BINDING
layout(binding = MODEL_COLOUR_TEXTURE_BINDING) uniform highp usampler2D colourTexture;
uint loadColour(uint idx) {
    return TEXEL_FETCH_LINEAR(colourTexture, MODEL_COLOUR_TEXTURE_SHIFT, idx).x;
}
#define LOAD_COLOUR(i) loadColour(i)
#else
#define LOAD_COLOUR(i) colourData[i]
#endif

#ifdef SECTION_TEXTURE_BINDING
layout(binding = SECTION_TEXTURE_BINDING) uniform highp usampler2D sectionTexture;
//A section is 2 texels (32 bytes)
SectionMeta loadSection(uint id) {
    uint base = id<<1;
    SectionMeta m;
    m.a = TEXEL_FETCH_LINEAR(sectionTexture, SECTION_TEXTURE_SHIFT, base);
    m.b = TEXEL_FETCH_LINEAR(sectionTexture, SECTION_TEXTURE_SHIFT, base+1u);
    return m;
}
#define LOAD_SECTION(i) loadSection(i)
#else
#define LOAD_SECTION(i) sectionData[i]
#endif

#ifdef INDIRECT_LOOKUP_TEXTURE_BINDING
layout(binding = INDIRECT_LOOKUP_TEXTURE_BINDING) uniform highp usampler2D indirectLookupTexture;
uint loadIndirectLookup(uint idx) {
    return TEXEL_FETCH_LINEAR(indirectLookupTexture, INDIRECT_LOOKUP_TEXTURE_SHIFT, idx+1u).x;//The first element of the buffer is the section count
}
#define LOAD_INDIRECT_LOOKUP(i) loadIndirectLookup(i)
#else
#define LOAD_INDIRECT_LOOKUP(i) indirectLookup[i]
#endif

#ifdef VISIBILITY_TEXTURE_BINDING
layout(binding = VISIBILITY_TEXTURE_BINDING) uniform highp usampler2D visibilityTexture;
uint loadVisibility(uint idx) {
    return TEXEL_FETCH_LINEAR(visibilityTexture, VISIBILITY_TEXTURE_SHIFT, idx).x;
}
#define LOAD_VISIBILITY(i) loadVisibility(i)
#else
#define LOAD_VISIBILITY(i) visibilityData[i]
#endif

#endif

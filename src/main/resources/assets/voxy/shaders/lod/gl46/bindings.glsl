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
#ifdef USE_DATA_TEXTURES
//The vertex stage cannot read storage buffers on some drivers, the data is mirrored into a texture instead
// texel i is at (i & (width-1), i >> log2Width), see VertexDataTextures.java
layout(binding = QUAD_TEXTURE_BINDING) uniform usampler2D quadTexture;
Quad loadQuad(uint index) {
    uvec2 data = texelFetch(quadTexture, ivec2(int(index & ((1u<<QUAD_TEXTURE_LOG2_WIDTH)-1u)), int(index>>QUAD_TEXTURE_LOG2_WIDTH)), 0).rg;
    #ifdef QUAD_DATA_USE_64_BIT
    return uint64_t(data.x)|(uint64_t(data.y)<<32);
    #else
    return ivec2(data);
    #endif
}
#else
layout(binding = QUAD_BUFFER_BINDING, std430) readonly restrict buffer QuadBuffer {
    Quad quadData[];
};
Quad loadQuad(uint index) {
    return quadData[index];
}
#endif
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
#ifdef USE_DATA_TEXTURES
//A section is 32 bytes, 2 RGBA32UI texels
layout(binding = SECTION_TEXTURE_BINDING) uniform usampler2D sectionTexture;
SectionMeta loadSection(uint sectionId) {
    uint texel = sectionId*2u;
    ivec2 base = ivec2(int(texel & ((1u<<SECTION_TEXTURE_LOG2_WIDTH)-1u)), int(texel>>SECTION_TEXTURE_LOG2_WIDTH));
    SectionMeta meta;
    meta.a = texelFetch(sectionTexture, base, 0);
    meta.b = texelFetch(sectionTexture, base+ivec2(1,0), 0);
    return meta;
}
#else
layout(binding = SECTION_METADATA_BUFFER_BINDING, std430) readonly restrict buffer SectionBuffer {
    SectionMeta sectionData[];
};
SectionMeta loadSection(uint sectionId) {
    return sectionData[sectionId];
}
#endif
#endif

#ifdef INDIRECT_SECTION_LOOKUP_BINDING
#ifdef USE_DATA_TEXTURES
//Texel 0 is the section count, the lookup entries start at texel 1 (matches the layout of the storage buffer)
layout(binding = LOOKUP_TEXTURE_BINDING) uniform usampler2D lookupTexture;
uint loadIndirectLookup(uint index) {
    uint texel = index+1u;
    return texelFetch(lookupTexture, ivec2(int(texel & ((1u<<LOOKUP_TEXTURE_LOG2_WIDTH)-1u)), int(texel>>LOOKUP_TEXTURE_LOG2_WIDTH)), 0).r;
}
#else
layout(binding = INDIRECT_SECTION_LOOKUP_BINDING, std430) readonly restrict buffer IndirectSectionLookupBuffer {
    uint sectionCount;
    uint indirectLookup[];
};
uint loadIndirectLookup(uint index) {
    return indirectLookup[index];
}
#endif
#endif

#ifndef VISIBILITY_ACCESS
#define VISIBILITY_ACCESS readonly
#endif
#ifdef VISIBILITY_BUFFER_BINDING
#ifdef USE_DATA_TEXTURES
layout(binding = VISIBILITY_TEXTURE_BINDING) uniform usampler2D visibilityTexture;
uint loadVisibility(uint sectionId) {
    return texelFetch(visibilityTexture, ivec2(int(sectionId & ((1u<<VISIBILITY_TEXTURE_LOG2_WIDTH)-1u)), int(sectionId>>VISIBILITY_TEXTURE_LOG2_WIDTH)), 0).r;
}
#else
layout(binding = VISIBILITY_BUFFER_BINDING, std430) VISIBILITY_ACCESS restrict buffer VisibilityBuffer {
    uint visibilityData[];
};
#ifdef VISIBILITY_LOADER
//Only for shaders that read the buffer (others may declare it writeonly, where a read would not compile)
uint loadVisibility(uint sectionId) {
    return visibilityData[sectionId];
}
#endif
#endif
#endif

#ifdef MODEL_BUFFER_BINDING
#ifdef USE_DATA_TEXTURES
//A model is 64 bytes, 4 RGBA32UI texels (the 4th is padding), the width is a multiple of 4 so a model never spans rows
layout(binding = MODEL_TEXTURE_BINDING) uniform usampler2D modelTexture;
BlockModel loadModel(uint modelId) {
    uint texel = modelId*4u;
    ivec2 base = ivec2(int(texel & ((1u<<MODEL_TEXTURE_LOG2_WIDTH)-1u)), int(texel>>MODEL_TEXTURE_LOG2_WIDTH));
    uvec4 a = texelFetch(modelTexture, base, 0);
    uvec4 b = texelFetch(modelTexture, base+ivec2(1,0), 0);
    uvec4 c = texelFetch(modelTexture, base+ivec2(2,0), 0);
    BlockModel model;
    model.faceData[0] = a.x; model.faceData[1] = a.y; model.faceData[2] = a.z; model.faceData[3] = a.w;
    model.faceData[4] = b.x; model.faceData[5] = b.y;
    model.flagsA = b.z;
    model.colourTint = b.w;
    model.customId = c.x;
    return model;
}
#else
layout(binding = MODEL_BUFFER_BINDING, std430) readonly restrict buffer ModelBuffer {
    BlockModel modelData[];
};
BlockModel loadModel(uint modelId) {
    return modelData[modelId];
}
#endif
#endif

#ifdef MODEL_COLOUR_BUFFER_BINDING
#ifdef USE_DATA_TEXTURES
layout(binding = COLOUR_TEXTURE_BINDING) uniform usampler2D colourTexture;
uint loadColour(uint index) {
    return texelFetch(colourTexture, ivec2(int(index & ((1u<<COLOUR_TEXTURE_LOG2_WIDTH)-1u)), int(index>>COLOUR_TEXTURE_LOG2_WIDTH)), 0).r;
}
#else
layout(binding = MODEL_COLOUR_BUFFER_BINDING, std430) readonly restrict buffer ModelColourBuffer {
    uint colourData[];
};
uint loadColour(uint index) {
    return colourData[index];
}
#endif
#endif

#ifdef POSITION_SCRATCH_BINDING
#ifndef POSITION_SCRATCH_ACCESS
#define POSITION_SCRATCH_ACCESS readonly
#endif
#ifdef USE_DATA_TEXTURES
layout(binding = POSITION_TEXTURE_BINDING) uniform usampler2D positionTexture;
uvec2 loadPosition(uint index) {
    return texelFetch(positionTexture, ivec2(int(index & ((1u<<POSITION_TEXTURE_LOG2_WIDTH)-1u)), int(index>>POSITION_TEXTURE_LOG2_WIDTH)), 0).rg;
}
#else
layout(binding = POSITION_SCRATCH_BINDING, std430) POSITION_SCRATCH_ACCESS restrict buffer PositionScratchBuffer {
    uvec2 positionBuffer[];
};
#ifdef POSITION_SCRATCH_LOADER
//Only for shaders that read the buffer (others declare it writeonly, where a read would not compile)
uvec2 loadPosition(uint index) {
    return positionBuffer[index];
}
#endif
#endif
#endif


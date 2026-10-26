package me.cortex.voxy.client.core.rendering.section.backend.mdic;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.gl.BufferTextureMirror;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.rendering.util.VertexDataTextures;
import org.jetbrains.annotations.Nullable;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;

public class MDICViewport extends Viewport<MDICViewport> {
    public final GlBuffer drawCountCallBuffer = new GlBuffer(1024).zero();
    public final GlBuffer drawCallBuffer = new GlBuffer(5*4*(MDICSectionRenderer.OPAQUE_DRAW_COUNT+MDICSectionRenderer.TRANSLUCENT_DRAW_COUNT+MDICSectionRenderer.TEMPORAL_DRAW_COUNT)).zero();//400k draw calls
    //400k positions, when mirrored into a texture the size is a whole number of texture rows
    public final GlBuffer positionScratchBuffer  = new GlBuffer(VertexDataTextures.enabled()?VertexDataTextures.POSITION_BUFFER_SIZE:8*400000).zero();
    //Texture copy of the position buffer, only present when the vertex stage cannot read storage buffers
    public final @Nullable BufferTextureMirror positionMirror = VertexDataTextures.enabled()?VertexDataTextures.createPositionMirror():null;
    public final GlBuffer indirectLookupBuffer = new GlBuffer(HierarchicalOcclusionTraverser.MAX_QUEUE_SIZE*4+4);//In theory, this could be global/not unique to the viewport
    public final GlBuffer visibilityBuffer;

    //Texture copies of the visibility and render list buffers, copied every frame before the culling pass, only present
    // when the vertex stage cannot read storage buffers
    public final @Nullable BufferTextureMirror visibilityMirror;
    public final @Nullable BufferTextureMirror lookupMirror;

    public MDICViewport(RenderProperties properties, int maxSectionCount) {
        super(properties);
        this.visibilityBuffer = new GlBuffer(maxSectionCount*4L);
        if (VertexDataTextures.enabled()) {
            this.visibilityMirror = new BufferTextureMirror(BufferTextureMirror.Format.R32UI, VertexDataTextures.VISIBILITY_LOG2_WIDTH, this.visibilityBuffer.size()/4);
            this.lookupMirror = new BufferTextureMirror(BufferTextureMirror.Format.R32UI, VertexDataTextures.LOOKUP_LOG2_WIDTH, this.indirectLookupBuffer.size()/4);
        } else {
            this.visibilityMirror = null;
            this.lookupMirror = null;
        }
    }

    @Override
    protected void delete0() {
        super.delete0();
        this.visibilityBuffer.free();
        this.indirectLookupBuffer.free();
        this.drawCountCallBuffer.free();
        this.drawCallBuffer.free();
        this.positionScratchBuffer.free();
        if (this.positionMirror != null) {
            this.positionMirror.free();
        }
        if (this.visibilityMirror != null) {
            this.visibilityMirror.free();
        }
        if (this.lookupMirror != null) {
            this.lookupMirror.free();
        }
    }

    @Override
    public GlBuffer getRenderList() {
        return this.indirectLookupBuffer;
    }
}

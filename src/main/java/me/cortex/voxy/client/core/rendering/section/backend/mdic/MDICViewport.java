package me.cortex.voxy.client.core.rendering.section.backend.mdic;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.MirrorTexture;
import me.cortex.voxy.client.core.gl.VertexDataTextures;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;

public class MDICViewport extends Viewport<MDICViewport> {
    public final GlBuffer drawCountCallBuffer = new GlBuffer(1024).zero();
    public final GlBuffer drawCallBuffer = new GlBuffer(5*4*(MDICSectionRenderer.OPAQUE_DRAW_COUNT+MDICSectionRenderer.TRANSLUCENT_DRAW_COUNT+MDICSectionRenderer.TEMPORAL_DRAW_COUNT)).zero();//400k draw calls
    public final GlBuffer positionScratchBuffer  = new GlBuffer(8*400000).zero();//400k positions
    public final GlBuffer indirectLookupBuffer = new GlBuffer(HierarchicalOcclusionTraverser.MAX_QUEUE_SIZE*4+4);//In theory, this could be global/not unique to the viewport
    public final GlBuffer visibilityBuffer;

    //Copies of the buffers read by the vertex stage, only present when the vertex stage cannot read shader storage blocks
    public final MirrorTexture positionMirror;
    public final MirrorTexture lookupMirror;
    public final MirrorTexture visibilityMirror;

    public MDICViewport(RenderProperties properties, int maxSectionCount) {
        super(properties);
        this.visibilityBuffer = new GlBuffer(maxSectionCount*4L);
        if (VertexDataTextures.enabled()) {
            this.positionMirror = new MirrorTexture("PositionMirror", this.positionScratchBuffer.size(), 2, VertexDataTextures.SHIFT_POSITION);
            this.lookupMirror = new MirrorTexture("IndirectLookupMirror", this.indirectLookupBuffer.size(), 1, VertexDataTextures.SHIFT_LOOKUP);
            this.visibilityMirror = new MirrorTexture("VisibilityMirror", this.visibilityBuffer.size(), 1, VertexDataTextures.SHIFT_VISIBILITY);
        } else {
            this.positionMirror = null;
            this.lookupMirror = null;
            this.visibilityMirror = null;
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
            this.lookupMirror.free();
            this.visibilityMirror.free();
        }
    }

    @Override
    public GlBuffer getRenderList() {
        return this.indirectLookupBuffer;
    }
}

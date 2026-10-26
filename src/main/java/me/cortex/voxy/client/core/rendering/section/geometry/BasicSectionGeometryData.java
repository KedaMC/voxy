package me.cortex.voxy.client.core.rendering.section.geometry;

import me.cortex.voxy.client.core.gl.BufferTextureMirror;
import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.rendering.util.VertexDataTextures;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.ThreadUtils;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

import static org.lwjgl.opengl.ARBSparseBuffer.*;
import static org.lwjgl.opengl.GL42C.GL_BUFFER_UPDATE_BARRIER_BIT;
import static org.lwjgl.opengl.GL42C.GL_PIXEL_BUFFER_BARRIER_BIT;
import static org.lwjgl.opengl.GL42C.glMemoryBarrier;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL15C.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15C.glBindBuffer;

public class BasicSectionGeometryData implements IGeometryData {
    public static final int SECTION_METADATA_SIZE = 32;
    private final GlBuffer sectionMetadataBuffer;
    private final GlBuffer geometryBuffer;
    public final boolean isExternalGeometryBuffer;

    private final int maxSectionCount;
    private int currentSectionCount;

    public BasicSectionGeometryData(int maxSectionCount, GlBuffer geometryBuffer) {
        this.maxSectionCount = maxSectionCount;
        this.sectionMetadataBuffer = new GlBuffer((long) maxSectionCount * SECTION_METADATA_SIZE);
        //8 Cause a quad is 8 bytes
        if ((geometryBuffer.size()%8)!=0) {
            throw new IllegalStateException();
        }
        this.geometryBuffer = geometryBuffer;
        this.isExternalGeometryBuffer = true;
        this.quadMirror = createQuadMirror(geometryBuffer);
    }

    //Texture copy of the geometry buffer, only present when the vertex stage cannot read storage buffers
    private final @Nullable BufferTextureMirror quadMirror;

    private static @Nullable BufferTextureMirror createQuadMirror(GlBuffer buffer) {
        if (!VertexDataTextures.enabled()) {
            return null;
        }
        if (buffer.isSparse()) {
            throw new IllegalStateException("Sparse geometry buffers cannot be mirrored into a texture");
        }
        if ((buffer.size() % VertexDataTextures.QUAD_ROW_BYTES) != 0) {
            throw new IllegalStateException("Geometry buffer size must be a multiple of " + VertexDataTextures.QUAD_ROW_BYTES + " to be mirrored");
        }
        var mirror = new BufferTextureMirror(BufferTextureMirror.Format.RG32UI, VertexDataTextures.QUAD_LOG2_WIDTH, buffer.size() / 8);
        Logger.info("Created geometry texture mirror " + mirror.width + "x" + mirror.height);
        return mirror;
    }

    public @Nullable BufferTextureMirror getQuadMirror() {
        return this.quadMirror;
    }

    //Texture copy of the section metadata (2 RGBA32UI texels per section), used by the culling vertex shader
    private @Nullable BufferTextureMirror sectionMirror;
    private boolean sectionMirrorDirty = true;
    private long sectionMirrorVersion = -1;

    /** To be called when the section metadata buffer has been written to by anything other than the upload stream */
    public void markSectionsChanged() {
        this.sectionMirrorDirty = true;
    }

    /** Brings the section metadata texture up to date and binds it, only when the vertex stage cannot read storage buffers */
    public void bindSectionMirror(int unit) {
        if (!VertexDataTextures.enabled()) {
            return;
        }
        if (this.sectionMirror == null) {
            this.sectionMirror = new BufferTextureMirror(BufferTextureMirror.Format.RGBA32UI, VertexDataTextures.SECTION_LOG2_WIDTH, this.sectionMetadataBuffer.size() / 16);
        }
        if (this.sectionMirrorDirty || this.sectionMetadataBuffer.uploadVersion != this.sectionMirrorVersion) {
            glMemoryBarrier(GL_PIXEL_BUFFER_BARRIER_BIT | GL_BUFFER_UPDATE_BARRIER_BIT);
            this.sectionMirror.copyAll(this.sectionMetadataBuffer);
            this.sectionMirrorDirty = false;
            this.sectionMirrorVersion = this.sectionMetadataBuffer.uploadVersion;
        }
        this.sectionMirror.bind(unit);
    }

    /**
     * Copies the rows of the geometry buffer that contain the given element ranges into the texture mirror.
     * Must be called after the writes to the geometry buffer have finished on the gpu (with a pixel buffer barrier).
     * @param ranges pairs of (firstElement, elementCount), elements being 8 byte quads
     */
    public void mirrorGeometryRanges(long[] ranges, int rangeCount) {
        if (this.quadMirror == null || rangeCount == 0) {
            return;
        }
        //Convert to row ranges (start row << 32 | end row), sort and merge so touching/overlapping ranges become one copy
        long[] rows = new long[rangeCount];
        int count = 0;
        for (int i = 0; i < rangeCount; i++) {
            long first = ranges[i * 2];
            long size = ranges[i * 2 + 1];
            if (size <= 0) continue;
            long startRow = this.quadMirror.rowOf(first);
            long endRow = this.quadMirror.rowOf(first + size - 1);
            rows[count++] = (startRow << 32) | endRow;
        }
        if (count == 0) return;
        Arrays.sort(rows, 0, count);
        long curStart = rows[0] >>> 32;
        long curEnd = rows[0] & 0xFFFFFFFFL;
        for (int i = 1; i <= count; i++) {
            if (i < count) {
                long s = rows[i] >>> 32;
                long e = rows[i] & 0xFFFFFFFFL;
                if (s <= curEnd + 1) {
                    curEnd = Math.max(curEnd, e);
                    continue;
                }
                this.quadMirror.copyRows(this.geometryBuffer, (int) curStart, (int) (curEnd - curStart + 1));
                curStart = s;
                curEnd = e;
            } else {
                this.quadMirror.copyRows(this.geometryBuffer, (int) curStart, (int) (curEnd - curStart + 1));
            }
        }
    }

    public BasicSectionGeometryData(int maxSectionCount, long geometryCapacity) {
        this.isExternalGeometryBuffer = false;
        this.maxSectionCount = maxSectionCount;
        this.sectionMetadataBuffer = new GlBuffer((long) maxSectionCount * SECTION_METADATA_SIZE);
        //8 Cause a quad is 8 bytes
        if ((geometryCapacity%8)!=0) {
            throw new IllegalStateException();
        }
        long start = System.currentTimeMillis();
        String msg = "Creating and zeroing " + (geometryCapacity/(1024*1024)) + "MB geometry buffer";
        if (Capabilities.INSTANCE.canQueryGpuMemory) {
            msg += " driver states " + (Capabilities.INSTANCE.getFreeDedicatedGpuMemory()/(1024*1024)) + "MB of free memory";
        }
        Logger.info(msg);
        Logger.info("if your game crashes/exits here without any other log message, try manually decreasing the geometry capacity");
        glGetError();//Clear any errors
        GlBuffer buffer = null;
        if (!(Capabilities.INSTANCE.isNvidia&&ThreadUtils.isWindows&&Capabilities.INSTANCE.sparseBuffer)) {//This hack makes it so it doesnt crash on renderdoc
            buffer = new GlBuffer(geometryCapacity, false);//Only do this if we are not on nvidia
            //TODO: FIXME: TEST, see if the issue is that we are trying to zero the entire buffer, try only zeroing increments
            // or dont zero it at all
        } else {
            Logger.info("Running on nvidia, using workaround sparse buffer allocation");
        }
        int error = glGetError();
        if (error != GL_NO_ERROR || buffer == null) {
            if ((buffer == null || error == GL_OUT_OF_MEMORY) && Capabilities.INSTANCE.sparseBuffer) {
                if (buffer != null) {
                    Logger.error("Failed to allocate geometry buffer, attempting workaround with sparse buffers");
                    buffer.free();
                }
                buffer = new GlBuffer(geometryCapacity, GL_SPARSE_STORAGE_BIT_ARB);
                //buffer.zero();
                error = glGetError();
                if (error != GL_NO_ERROR) {
                    buffer.free();
                    throw new IllegalStateException("Unable to allocate geometry buffer using workaround, got gl error " + error);
                }
            } else {
                throw new IllegalStateException("Unable to allocate geometry buffer, got gl error " + error);
            }
        }
        this.geometryBuffer = buffer;
        this.quadMirror = createQuadMirror(buffer);
        long delta = System.currentTimeMillis() - start;
        Logger.info("Successfully allocated the geometry buffer in " + delta + "ms");
    }

    private long sparseCommitment = 0;//Tracks the current range of the allocated sparse buffer
    public void ensureAccessable(int maxElementAccess) {
        long size = (Integer.toUnsignedLong(maxElementAccess)*8L+65535L)&~65535L;
        //If we are a sparse buffer, ensure the memory upto the requested size is allocated
        if (this.geometryBuffer.isSparse()) {
            if (this.sparseCommitment < size) {//if we try to access memory outside the allocation range, allocate it
                glBindBuffer(GL_ARRAY_BUFFER, this.geometryBuffer.id);
                size += 65536L*1024;//increase size by 64mb to prevent driver allocation thrashing
                glBufferPageCommitmentARB(GL_ARRAY_BUFFER, this.sparseCommitment, size-this.sparseCommitment, true);
                glBindBuffer(GL_ARRAY_BUFFER, 0);
                //Logger.info("Resizing sparse: " + this.sparseCommitment + ", " + (size-this.sparseCommitment));
                this.sparseCommitment = size;
            }
        }
    }

    public GlBuffer getGeometryBuffer() {
        return this.geometryBuffer;
    }

    public GlBuffer getMetadataBuffer() {
        return this.sectionMetadataBuffer;
    }

    @Override
    public int getSectionCount() {
        return this.currentSectionCount;
    }

    public void setSectionCount(int count) {
        this.currentSectionCount = count;
    }

    public int getMaxSectionCount() {
        return this.maxSectionCount;
    }

    public long getGeometryCapacityBytes() {//In bytes
        return this.geometryBuffer.size();
    }

    @Override
    public void free() {
        this.sectionMetadataBuffer.free();
        if (this.quadMirror != null) {
            this.quadMirror.free();
        }
        if (this.sectionMirror != null) {
            this.sectionMirror.free();
        }

        long gpuMemory = 0;
        if (Capabilities.INSTANCE.canQueryGpuMemory) {
            glFinish();
            gpuMemory = Capabilities.INSTANCE.getFreeDedicatedGpuMemory();
        }
        if (this.geometryBuffer.isSparse()) {
            glBindBuffer(GL_ARRAY_BUFFER, this.geometryBuffer.id);
            glBufferPageCommitmentARB(GL_ARRAY_BUFFER, 0, this.sparseCommitment, false);
            glBindBuffer(GL_ARRAY_BUFFER, 0);
        }

        glFinish();

        if (!this.isExternalGeometryBuffer) {
            this.geometryBuffer.free();
            glFinish();
            if (Capabilities.INSTANCE.canQueryGpuMemory) {
                long releaseSize = (long) (this.geometryBuffer.size() * 0.75);//if gpu memory usage drops by 75% of the expected value assume we freed it
                if (this.geometryBuffer.isSparse()) {//If we are using sparse buffers, use the commited size instead
                    releaseSize = (long) (this.sparseCommitment * 0.75);
                }
                if (Capabilities.INSTANCE.getFreeDedicatedGpuMemory() - gpuMemory <= releaseSize) {
                    Logger.info("Attempting to wait for gpu memory to release");
                    long start = System.currentTimeMillis();

                    long TIMEOUT = 400;

                    while (System.currentTimeMillis() - start < TIMEOUT) {//Wait up to 2.5 seconds for memory to release
                        glFinish();
                        if (Capabilities.INSTANCE.getFreeDedicatedGpuMemory() - gpuMemory > releaseSize) break;
                    }
                    if (Capabilities.INSTANCE.getFreeDedicatedGpuMemory() - gpuMemory <= releaseSize) {
                        Logger.warn("Failed to wait for gpu memory to be freed, this could indicate an issue with the driver");
                    }
                }
            }
        }
    }

    @Override
    public long getMaxCapacity() {
        return this.geometryBuffer.size();
    }
}

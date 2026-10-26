package me.cortex.voxy.client.core.gl;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.TrackedObject;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL21C.GL_PIXEL_UNPACK_BUFFER;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL33C.glBindSampler;
import static org.lwjgl.opengl.GL45C.*;

/**
 * A 2D integer texture that mirrors the contents of a buffer.
 * <p>
 * Used on drivers where the vertex stage cannot read shader storage buffers (GL_MAX_VERTEX_SHADER_STORAGE_BLOCKS == 0,
 * i.e. Mali GPUs behind GLES). The buffer stays the source of truth (compute shaders and the upload stream keep writing to it)
 * and rows of it are copied into the texture through a pixel unpack buffer, which is a pure gpu side copy.
 * <p>
 * Texel {@code i} of the buffer lives at {@code (i & (width-1), i >> log2Width)}, the shader side does the same addressing.
 */
public class BufferTextureMirror extends TrackedObject {
    public final int id;
    public final int log2Width;
    public final int width;
    public final int height;
    private final int bytesPerTexel;
    private final int transferFormat;

    //Statistics for the frame profiler
    private static long copyCount;
    private static long copyBytes;
    private static int errorChecksLeft = 512;

    public static long takeCopyCount() {
        long v = copyCount; copyCount = 0; return v;
    }

    public static long takeCopyBytes() {
        long v = copyBytes; copyBytes = 0; return v;
    }

    //Checks for (and logs) a gl error for the first copies, an invalid copy would otherwise silently leave stale data
    private void checkError(String what, long bytes) {
        copyCount++;
        copyBytes += bytes;
        if (errorChecksLeft > 0) {
            errorChecksLeft--;
            int error = glGetError();
            if (error != GL_NO_ERROR) {
                Logger.error("GL error 0x" + Integer.toHexString(error) + " during mirror copy (" + what + ") of texture " + this.id + " " + this.width + "x" + this.height);
            }
        }
    }

    public enum Format {
        R32UI(GL_R32UI, GL_RED_INTEGER, 4),
        RG32UI(GL_RG32UI, GL_RG_INTEGER, 8),
        RGBA32UI(GL_RGBA32UI, GL_RGBA_INTEGER, 16);

        final int internal;
        final int transfer;
        final int bytes;

        Format(int internal, int transfer, int bytes) {
            this.internal = internal;
            this.transfer = transfer;
            this.bytes = bytes;
        }
    }

    /**
     * @param texelCount number of texels the mirrored buffer holds, rounded up to a whole number of rows
     */
    public BufferTextureMirror(Format format, int log2Width, long texelCount) {
        this.log2Width = log2Width;
        this.width = 1 << log2Width;
        long rows = (texelCount + this.width - 1) >> log2Width;
        if (rows <= 0 || rows > 16383) {
            throw new IllegalArgumentException("Cannot mirror " + texelCount + " texels with a texture width of " + this.width);
        }
        this.height = (int) rows;
        this.bytesPerTexel = format.bytes;
        this.transferFormat = format.transfer;

        this.id = glCreateTextures(GL_TEXTURE_2D);
        glTextureStorage2D(this.id, 1, format.internal, this.width, this.height);
        //Integer textures can only be point sampled, and with the default min filter (mipmapped) the texture would be incomplete
        glTextureParameteri(this.id, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTextureParameteri(this.id, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTextureParameteri(this.id, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTextureParameteri(this.id, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    }

    /** The size in bytes the source buffer must be at least, for any row to be copied from it */
    public long requiredBufferSize() {
        return (long) this.width * this.height * this.bytesPerTexel;
    }

    public int rowOf(long texelIndex) {
        return (int) (texelIndex >> this.log2Width);
    }

    /** Copies the whole buffer, the buffer may end part way through the last row of the texture */
    public void copyAll(GlBuffer source) {
        long texels = Math.min(source.size() / this.bytesPerTexel, (long) this.width * this.height);
        int fullRows = (int) (texels >> this.log2Width);
        int remainder = (int) (texels & (this.width - 1));
        if (fullRows > 0) {
            this.copyRows(source, 0, fullRows);
        }
        if (remainder > 0) {
            glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
            glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
            glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, source.id);
            glTextureSubImage2D(this.id, 0, 0, fullRows, remainder, 1, this.transferFormat, GL_UNSIGNED_INT, (long) fullRows * this.width * this.bytesPerTexel);
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
            this.checkError("tail row " + fullRows, (long) remainder * this.bytesPerTexel);
        }
    }

    public void copyRows(GlBuffer source, int firstRow, int rowCount) {
        if (firstRow < 0 || rowCount <= 0 || firstRow + rowCount > this.height) {
            throw new IllegalArgumentException("Row range [" + firstRow + ", " + (firstRow + rowCount) + ") outside of " + this.height + " rows");
        }
        long offset = (long) firstRow * this.width * this.bytesPerTexel;
        if (offset + (long) rowCount * this.width * this.bytesPerTexel > source.size()) {
            throw new IllegalStateException("Source buffer too small for mirror copy");
        }
        glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
        glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
        glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, source.id);
        glTextureSubImage2D(this.id, 0, 0, firstRow, this.width, rowCount, this.transferFormat, GL_UNSIGNED_INT, offset);
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
        this.checkError("rows " + firstRow + "+" + rowCount, (long) rowCount * this.width * this.bytesPerTexel);
    }

    public void bind(int unit) {
        glBindTextureUnit(unit, this.id);
        glBindSampler(unit, 0);
    }

    @Override
    public void free() {
        super.free0();
        glDeleteTextures(this.id);
    }
}

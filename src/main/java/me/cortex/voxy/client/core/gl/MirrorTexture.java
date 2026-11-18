package me.cortex.voxy.client.core.gl;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL12C.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL21C.GL_PIXEL_UNPACK_BUFFER;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL33C.glBindSampler;
import static org.lwjgl.opengl.GL42C.*;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BARRIER_BIT;
import static org.lwjgl.opengl.GL45C.*;

//A 2D integer texture holding a copy of (part of) a buffer, texel i is bytes [i*texelBytes, (i+1)*texelBytes) of the buffer
// used to give vertex shaders access to buffer data on drivers that expose no vertex stage shader storage blocks
public class MirrorTexture {
    public final GlTexture texture;
    public final int widthShift;
    private final int width;
    private final int rows;
    private final int texelBytes;
    private final int pixelFormat;

    //components: 1 (R32UI), 2 (RG32UI) or 4 (RGBA32UI)
    public MirrorTexture(String name, long capacityBytes, int components, int widthShift) {
        this.widthShift = widthShift;
        this.width = 1 << widthShift;
        this.texelBytes = components * 4;
        int internalFormat;
        switch (components) {
            case 1 -> {internalFormat = GL_R32UI; this.pixelFormat = GL_RED_INTEGER;}
            case 2 -> {internalFormat = GL_RG32UI; this.pixelFormat = GL_RG_INTEGER;}
            case 4 -> {internalFormat = GL_RGBA32UI; this.pixelFormat = GL_RGBA_INTEGER;}
            default -> throw new IllegalArgumentException("components must be 1, 2 or 4");
        }
        long texels = (capacityBytes + this.texelBytes - 1) / this.texelBytes;
        long rows = Math.max(1, (texels + this.width - 1) / this.width);
        int maxSize = glGetInteger(GL_MAX_TEXTURE_SIZE);
        if (rows > maxSize || this.width > maxSize) {
            throw new IllegalStateException("Mirror texture " + name + " needs " + this.width + "x" + rows + " texels but the driver supports at most " + maxSize);
        }
        this.rows = (int) rows;
        this.texture = new GlTexture().store(internalFormat, 1, this.width, this.rows).name(name);
        //Integer textures must not be filtered
        glTextureParameteri(this.texture.id, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTextureParameteri(this.texture.id, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTextureParameteri(this.texture.id, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTextureParameteri(this.texture.id, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    }

    //Makes writes done by shaders to buffers visible to the buffer->texture copies
    public static void barrier() {
        glMemoryBarrier(GL_PIXEL_BUFFER_BARRIER_BIT | GL_BUFFER_UPDATE_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);
    }

    //Copies the byte range of the source buffer into the texture, the buffer must be laid out as the mirror expects (starting at byte 0)
    public void copyRange(GlBuffer src, long byteOffset, long byteLength) {
        long texelCount = (long) this.width * this.rows;
        long t0 = byteOffset / this.texelBytes;
        long t1 = Math.min((byteOffset + byteLength + this.texelBytes - 1) / this.texelBytes, Math.min(texelCount, src.size() / this.texelBytes));
        if (t1 <= t0) {
            return;
        }
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, src.id);
        long r0 = t0 / this.width;
        long r1 = (t1 - 1) / this.width;
        int x0 = (int) (t0 % this.width);
        if (r0 == r1) {
            this.upload(x0, r0, (int) (t1 - t0), 1, t0);
        } else {
            long startRow = r0;
            if (x0 != 0) {
                this.upload(x0, r0, this.width - x0, 1, t0);
                startRow = r0 + 1;
            }
            int tail = (int) ((t1 - 1) % this.width) + 1;
            boolean lastFull = tail == this.width;
            long endRow = lastFull ? r1 + 1 : r1;//Exclusive
            if (endRow > startRow) {
                this.upload(0, startRow, this.width, (int) (endRow - startRow), startRow * this.width);
            }
            if (!lastFull) {
                this.upload(0, r1, tail, 1, r1 * this.width);
            }
        }
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
    }

    public void copyAll(GlBuffer src) {
        this.copyRange(src, 0, src.size());
    }

    private void upload(int x, long y, int w, int h, long firstTexel) {
        glTextureSubImage2D(this.texture.id, 0, x, (int) y, w, h, this.pixelFormat, GL_UNSIGNED_INT, firstTexel * this.texelBytes);
    }

    public void bind(int unit) {
        glBindTextureUnit(unit, this.texture.id);
        glBindSampler(unit, 0);
    }

    public void free() {
        this.texture.free();
    }
}

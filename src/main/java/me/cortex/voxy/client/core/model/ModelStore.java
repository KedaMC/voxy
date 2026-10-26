package me.cortex.voxy.client.core.model;

import me.cortex.voxy.client.core.RenderResourceReuse;
import me.cortex.voxy.client.core.gl.BufferTextureMirror;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.client.core.rendering.util.VertexDataTextures;
import org.jetbrains.annotations.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL11C.GL_NEAREST;
import static org.lwjgl.opengl.GL11C.GL_NEAREST_MIPMAP_LINEAR;
import static org.lwjgl.opengl.GL12C.GL_TEXTURE_MAX_LOD;
import static org.lwjgl.opengl.GL12C.GL_TEXTURE_MIN_LOD;
import static org.lwjgl.opengl.GL30.glBindBufferBase;
import static org.lwjgl.opengl.GL33.*;
import static org.lwjgl.opengl.GL33C.glSamplerParameteri;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL42.GL_PIXEL_BUFFER_BARRIER_BIT;
import static org.lwjgl.opengl.GL42.glMemoryBarrier;
import static org.lwjgl.opengl.GL45.glBindTextureUnit;

public class ModelStore {
    public static final int MODEL_SIZE = 64;
    final GlBuffer modelBuffer;
    final GlBuffer modelColourBuffer;
    final GlTexture textures;
    public final int blockSampler = glGenSamplers();

    //Texture copies of the model and colour buffers, only present when the vertex stage cannot read storage buffers
    private final @Nullable BufferTextureMirror modelMirror;
    private final @Nullable BufferTextureMirror colourMirror;
    private long modelMirrorVersion = -1;
    private long colourMirrorVersion = -1;

    public ModelStore() {
        this.modelBuffer = new GlBuffer(MODEL_SIZE * (1<<16)).name("ModelData");
        this.modelColourBuffer = new GlBuffer(4 * (1<<16)).name("ModelColour");
        this.textures = RenderResourceReuse.getOrCreateModelStoreTextureAtlas();

        //Limit the mips of the texture to match that of the terrain atlas
        int mipLvl = ((TextureAtlas) Minecraft.getInstance().getTextureManager()
                .getTexture(Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/blocks.png")))
                .maxMipLevel;

        glSamplerParameteri(this.blockSampler, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_LINEAR);
        glSamplerParameteri(this.blockSampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glSamplerParameteri(this.blockSampler, GL_TEXTURE_MIN_LOD, 0);
        glSamplerParameteri(this.blockSampler, GL_TEXTURE_MAX_LOD, mipLvl);//Integer.numberOfTrailingZeros(ModelFactory.MODEL_TEXTURE_SIZE)

        if (VertexDataTextures.enabled()) {
            //A model is 64 bytes, i.e. 4 RGBA32UI texels, a colour is a single R32UI texel
            this.modelMirror = new BufferTextureMirror(BufferTextureMirror.Format.RGBA32UI, VertexDataTextures.MODEL_LOG2_WIDTH, this.modelBuffer.size() / 16);
            this.colourMirror = new BufferTextureMirror(BufferTextureMirror.Format.R32UI, VertexDataTextures.COLOUR_LOG2_WIDTH, this.modelColourBuffer.size() / 4);
        } else {
            this.modelMirror = null;
            this.colourMirror = null;
        }
    }


    public void free() {
        this.modelBuffer.free();
        this.modelColourBuffer.free();
        if (this.modelMirror != null) this.modelMirror.free();
        if (this.colourMirror != null) this.colourMirror.free();
        RenderResourceReuse.giveBackModelStoreTextureAtlas(this.textures);
        glDeleteSamplers(this.blockSampler);
    }


    public void bind(int modelBindingIndex, int colourBindingIndex, int textureBindingIndex) {
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, modelBindingIndex, this.modelBuffer.id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, colourBindingIndex, this.modelColourBuffer.id);
        glBindTextureUnit(textureBindingIndex, this.textures.id);
        glBindSampler(textureBindingIndex, this.blockSampler);
    }

    //Brings the texture copies of the model data up to date and binds them for the vertex shader.
    // Does nothing when the vertex stage can read the storage buffers directly
    public void bindVertexMirrors() {
        if (this.modelMirror == null) {
            return;
        }
        if (this.modelBuffer.uploadVersion != this.modelMirrorVersion || this.modelColourBuffer.uploadVersion != this.colourMirrorVersion) {
            //Make sure everything queued on the upload stream has actually been copied into the buffers
            UploadStream.INSTANCE.commit();
            glMemoryBarrier(GL_PIXEL_BUFFER_BARRIER_BIT);
            if (this.modelBuffer.uploadVersion != this.modelMirrorVersion) {
                this.modelMirror.copyAll(this.modelBuffer);
                this.modelMirrorVersion = this.modelBuffer.uploadVersion;
            }
            if (this.modelColourBuffer.uploadVersion != this.colourMirrorVersion) {
                this.colourMirror.copyAll(this.modelColourBuffer);
                this.colourMirrorVersion = this.modelColourBuffer.uploadVersion;
            }
        }
        this.modelMirror.bind(VertexDataTextures.MODEL_UNIT);
        this.colourMirror.bind(VertexDataTextures.COLOUR_UNIT);
    }
}

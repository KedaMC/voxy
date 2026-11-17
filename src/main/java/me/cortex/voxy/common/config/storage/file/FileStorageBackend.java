package me.cortex.voxy.common.config.storage.file;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.storage.StorageBackend;
import me.cortex.voxy.common.config.storage.StorageConfig;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.WorldEngine;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.function.LongConsumer;

//Pure java persistent storage, no native libraries required
//Sections are stored in an append only log with an in memory index that is rebuilt on open
//Log record: [long key][int length (-1 is a tombstone)][length bytes]
public class FileStorageBackend extends StorageBackend {
    private static final int HEADER_SIZE = 12;
    private static final int LEN_BITS = 24;//Biggest serialized section is well below 16MB
    private static final long LEN_MASK = (1L << LEN_BITS) - 1;
    private static final long COMPACT_MIN_GARBAGE = 64L * 1024 * 1024;

    private final Path sectionsPath;
    private final Object lock = new Object();
    private final FileChannel mappingsChannel;
    private final Int2ObjectOpenHashMap<byte[]> idMappings = new Int2ObjectOpenHashMap<>();
    private final Long2LongOpenHashMap index = new Long2LongOpenHashMap();//key -> (dataOffset << LEN_BITS | length)
    private FileChannel sectionsChannel;
    private long sectionsEnd;
    private long mappingsEnd;
    private long garbage;
    private boolean closed;

    public FileStorageBackend(Path directory) {
        this.index.defaultReturnValue(-1);
        try {
            Files.createDirectories(directory);
            this.sectionsPath = directory.resolve("sections.log");
            this.sectionsChannel = openChannel(this.sectionsPath);
            this.mappingsChannel = openChannel(directory.resolve("mappings.log"));
            this.loadMappings();
            this.loadSections();
            if (this.garbage > COMPACT_MIN_GARBAGE && this.garbage > this.sectionsEnd - this.garbage) {
                this.compact();
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to open file storage at " + directory, e);
        }
    }

    private static FileChannel openChannel(Path path) throws IOException {
        return FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
    }

    private static void readFully(FileChannel channel, ByteBuffer dst, long pos) throws IOException {
        while (dst.hasRemaining()) {
            int r = channel.read(dst, pos);
            if (r < 0) {
                throw new IOException("Unexpected end of file");
            }
            pos += r;
        }
    }

    private static void writeFully(FileChannel channel, ByteBuffer src, long pos) throws IOException {
        while (src.hasRemaining()) {
            pos += channel.write(src, pos);
        }
    }

    private void loadSections() throws IOException {
        long size = this.sectionsChannel.size();
        long pos = 0;
        var header = ByteBuffer.allocate(HEADER_SIZE);
        while (pos < size) {
            if (pos + HEADER_SIZE > size) {
                break;
            }
            header.clear();
            readFully(this.sectionsChannel, header, pos);
            long key = header.getLong(0);
            int len = header.getInt(8);
            if (len < -1 || (len > 0 && pos + HEADER_SIZE + len > size)) {
                break;
            }
            long old = this.index.get(key);
            if (old != -1) {
                this.garbage += HEADER_SIZE + (old & LEN_MASK);
            }
            if (len == -1) {
                this.index.remove(key);
                this.garbage += HEADER_SIZE;
                pos += HEADER_SIZE;
            } else {
                this.index.put(key, ((pos + HEADER_SIZE) << LEN_BITS) | len);
                pos += HEADER_SIZE + len;
            }
        }
        if (pos != size) {
            Logger.warn("Truncating corrupted tail of " + this.sectionsPath + " from " + size + " to " + pos);
            this.sectionsChannel.truncate(pos);
        }
        this.sectionsEnd = pos;
    }

    private void loadMappings() throws IOException {
        long size = this.mappingsChannel.size();
        long pos = 0;
        var header = ByteBuffer.allocate(8);
        while (pos + 8 <= size) {
            header.clear();
            readFully(this.mappingsChannel, header, pos);
            int id = header.getInt(0);
            int len = header.getInt(4);
            if (len < 0 || pos + 8 + len > size) {
                break;
            }
            var data = new byte[len];
            readFully(this.mappingsChannel, ByteBuffer.wrap(data), pos + 8);
            this.idMappings.put(id, data);
            pos += 8 + len;
        }
        if (pos != size) {
            this.mappingsChannel.truncate(pos);
        }
        this.mappingsEnd = pos;
    }

    //Rewrites the log with only live records
    private void compact() throws IOException {
        Logger.info("Compacting " + this.sectionsPath + ", reclaiming " + (this.garbage >> 20) + "MB");
        var tmp = this.sectionsPath.resolveSibling("sections.log.tmp");
        var newIndex = new Long2LongOpenHashMap();
        newIndex.defaultReturnValue(-1);
        long pos = 0;
        try (var out = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            var header = ByteBuffer.allocate(HEADER_SIZE);
            var iter = this.index.long2LongEntrySet().fastIterator();
            while (iter.hasNext()) {
                var entry = iter.next();
                long packed = entry.getLongValue();
                int len = (int) (packed & LEN_MASK);
                var data = ByteBuffer.allocate(len);
                readFully(this.sectionsChannel, data, packed >>> LEN_BITS);
                data.flip();
                header.clear();
                header.putLong(entry.getLongKey()).putInt(len).flip();
                writeFully(out, header, pos);
                writeFully(out, data, pos + HEADER_SIZE);
                newIndex.put(entry.getLongKey(), ((pos + HEADER_SIZE) << LEN_BITS) | len);
                pos += HEADER_SIZE + len;
            }
            out.force(true);
        }
        this.sectionsChannel.close();
        Files.move(tmp, this.sectionsPath, StandardCopyOption.REPLACE_EXISTING);
        this.sectionsChannel = openChannel(this.sectionsPath);
        this.index.clear();
        this.index.putAll(newIndex);
        this.sectionsEnd = pos;
        this.garbage = 0;
    }

    @Override
    public MemoryBuffer getSectionData(long key, MemoryBuffer scratch) {
        long packed;
        FileChannel channel;
        synchronized (this.lock) {
            packed = this.index.get(key);
            channel = this.sectionsChannel;
        }
        if (packed == -1) {
            return null;
        }
        int len = (int) (packed & LEN_MASK);
        if (len > scratch.size) {
            throw new IllegalStateException("Scratch buffer too small for section, " + len + " > " + scratch.size);
        }
        try {
            readFully(channel, MemoryUtil.memByteBuffer(scratch.address, len), packed >>> LEN_BITS);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return scratch.subSize(len);
    }

    @Override
    public void setSectionData(long key, MemoryBuffer data) {
        int len = (int) data.size;
        if (len > LEN_MASK) {
            throw new IllegalArgumentException("Section too large");
        }
        var header = ByteBuffer.allocate(HEADER_SIZE);
        header.putLong(key).putInt(len).flip();
        var body = data.asByteBuffer().duplicate();
        body.position(0).limit(len);
        synchronized (this.lock) {
            try {
                long pos = this.sectionsEnd;
                writeFully(this.sectionsChannel, header, pos);
                writeFully(this.sectionsChannel, body, pos + HEADER_SIZE);
                this.sectionsEnd = pos + HEADER_SIZE + len;
                long old = this.index.put(key, ((pos + HEADER_SIZE) << LEN_BITS) | len);
                if (old != -1) {
                    this.garbage += HEADER_SIZE + (old & LEN_MASK);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public void deleteSectionData(long key) {
        synchronized (this.lock) {
            long old = this.index.remove(key);
            if (old == -1) {
                return;
            }
            var header = ByteBuffer.allocate(HEADER_SIZE);
            header.putLong(key).putInt(-1).flip();
            try {
                writeFully(this.sectionsChannel, header, this.sectionsEnd);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            this.sectionsEnd += HEADER_SIZE;
            this.garbage += HEADER_SIZE * 2L + (old & LEN_MASK);
        }
    }

    @Override
    public void iteratePositions(int level, LongConsumer consumer) {
        long[] keys;
        synchronized (this.lock) {
            keys = this.index.keySet().toLongArray();
        }
        for (long key : keys) {
            if (level == -1 || WorldEngine.getLevel(key) == level) {
                consumer.accept(key);
            }
        }
    }

    @Override
    public void putIdMapping(int id, ByteBuffer data) {
        var bytes = new byte[data.remaining()];
        data.duplicate().get(bytes);
        var record = ByteBuffer.allocate(8 + bytes.length);
        record.putInt(id).putInt(bytes.length).put(bytes).flip();
        synchronized (this.idMappings) {
            try {
                writeFully(this.mappingsChannel, record, this.mappingsEnd);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            this.mappingsEnd += 8 + bytes.length;
            this.idMappings.put(id, bytes);
        }
    }

    @Override
    public Int2ObjectOpenHashMap<byte[]> getIdMappingsData() {
        synchronized (this.idMappings) {
            return new Int2ObjectOpenHashMap<>(this.idMappings);
        }
    }

    @Override
    public void flush() {
        try {
            synchronized (this.lock) {
                if (!this.closed) {
                    this.sectionsChannel.force(false);
                }
            }
            synchronized (this.idMappings) {
                if (!this.closed) {
                    this.mappingsChannel.force(false);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() {
        this.flush();
        synchronized (this.lock) {
            synchronized (this.idMappings) {
                this.closed = true;
                try {
                    this.sectionsChannel.close();
                    this.mappingsChannel.close();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    public static class Config extends StorageConfig {
        @Override
        public StorageBackend build(ConfigBuildCtx ctx) {
            return new FileStorageBackend(Path.of(ctx.ensurePathExists(ctx.substituteString(ctx.resolvePath()))));
        }

        public static String getConfigTypeName() {
            return "File";
        }
    }
}

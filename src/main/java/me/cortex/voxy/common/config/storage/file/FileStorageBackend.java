package me.cortex.voxy.common.config.storage.file;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.storage.StorageBackend;
import me.cortex.voxy.common.config.storage.StorageConfig;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.WorldEngine;
import org.lwjgl.system.MemoryUtil;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.LongConsumer;

//Pure java persistent storage, no native libraries required
//
//Sections live in append only segment files (seg-XXXX.log, max 256MB each), an in memory index (rebuilt on open) maps
//a section key to its newest record. A background thread compacts segments that contain a lot of dead records and
//evicts the oldest segments when the storage grows past the size limit (-Dvoxy.maxStorageMB, default 8192, <=0 disables)
//or when the device is nearly out of disk space.
//
//Segment header: [int magic][int version][long sequence]
//Record:         [long key][int length (-1 is a tombstone)][length bytes]
//Segments are replayed in (sequence, id) order on open, compaction output inherits the sequence of its source
public class FileStorageBackend extends StorageBackend {
    private static final int HEADER_SIZE = 12;
    private static final int SEG_HEADER_SIZE = 16;
    private static final int SEG_MAGIC = 0x56584653;
    private static final int OFFSET_BITS = 28;
    private static final int LEN_BITS = 24;
    private static final int ID_BITS = 12;
    private static final long SEG_MAX = 1L << OFFSET_BITS;
    private static final int MAX_SEGMENTS = 1 << ID_BITS;
    private static final long LEN_MASK = (1L << LEN_BITS) - 1;
    private static final long OFFSET_MASK = (1L << OFFSET_BITS) - 1;

    private static final long MAX_BYTES = Math.max(0L, Long.getLong("voxy.maxStorageMB", 8192L)) * 1024L * 1024L;
    private static final long MIN_FREE_BYTES = 128L * 1024 * 1024;
    private static final long MAINTENANCE_INTERVAL_MS = 15_000;
    private static final long COMPACT_MIN_GARBAGE = 4L * 1024 * 1024;

    private static long pack(int id, long dataOffset, int len) {
        return ((long) id << (OFFSET_BITS + LEN_BITS)) | (dataOffset << LEN_BITS) | len;
    }
    private static int unpackId(long p) {
        return (int) (p >>> (OFFSET_BITS + LEN_BITS));
    }
    private static long unpackOffset(long p) {
        return (p >>> LEN_BITS) & OFFSET_MASK;
    }
    private static int unpackLen(long p) {
        return (int) (p & LEN_MASK);
    }

    private static final class Segment {
        final int id;
        final long sequence;
        final Path path;
        volatile FileChannel channel;
        volatile boolean removed;
        long size;//Guarded by the backend lock
        long live;//Bytes of records that are still referenced by the index/tombstones, guarded by the backend lock

        Segment(int id, long sequence, Path path, FileChannel channel) {
            this.id = id;
            this.sequence = sequence;
            this.path = path;
            this.channel = channel;
        }

        //Interrupting a thread that is inside a FileChannel operation closes the channel for everyone, so reopen it
        synchronized void reopen() throws IOException {
            if (!this.channel.isOpen()) {
                if (this.removed) {
                    throw new ClosedChannelException();
                }
                this.channel = FileChannel.open(this.path, StandardOpenOption.READ, StandardOpenOption.WRITE);
            }
        }
    }

    private final Path directory;
    private final Object lock = new Object();
    private final Segment[] segments = new Segment[MAX_SEGMENTS];
    private final Long2LongOpenHashMap index = new Long2LongOpenHashMap();//key -> packed location of the newest record
    private final Long2IntOpenHashMap tombstones = new Long2IntOpenHashMap();//key -> segment holding the newest record, when that record is a tombstone
    private final Segment mappings;
    private final Int2ObjectOpenHashMap<byte[]> idMappings = new Int2ObjectOpenHashMap<>();
    private final Thread maintenance;
    private Segment active;
    private long nextSequence = 1;
    private long totalSize;
    private int segmentCount;
    private long mappingsEnd;
    private boolean forceEvict;
    private long lastWriteWarn;
    private volatile boolean closed;

    public FileStorageBackend(Path directory) {
        this.directory = directory;
        this.index.defaultReturnValue(-1);
        this.tombstones.defaultReturnValue(-1);
        try {
            Files.createDirectories(directory);
            var mappingsPath = directory.resolve("mappings.log");
            this.mappings = new Segment(-1, 0, mappingsPath, openChannel(mappingsPath));
            this.loadMappings();
            this.loadSegments();
        } catch (IOException e) {
            throw new RuntimeException("Failed to open file storage at " + directory, e);
        }
        this.maintenance = new Thread(this::maintenanceLoop, "Voxy file storage maintenance");
        this.maintenance.setDaemon(true);
        this.maintenance.start();
    }

    private static FileChannel openChannel(Path path) throws IOException {
        return FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
    }

    private static void readFully(Segment seg, ByteBuffer dst, long pos) throws IOException {
        boolean interrupted = false;
        try {
            int reopens = 0;
            while (dst.hasRemaining()) {
                try {
                    int r = seg.channel.read(dst, pos);
                    if (r < 0) {
                        throw new EOFException("Unexpected end of file");
                    }
                    pos += r;
                } catch (ClosedChannelException e) {
                    if (seg.removed || ++reopens > 16) {
                        throw e;
                    }
                    interrupted |= Thread.interrupted();
                    seg.reopen();
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void writeFully(Segment seg, ByteBuffer src, long pos) throws IOException {
        boolean interrupted = false;
        try {
            int reopens = 0;
            while (src.hasRemaining()) {
                try {
                    pos += seg.channel.write(src, pos);
                } catch (ClosedChannelException e) {
                    if (seg.removed || ++reopens > 16) {
                        throw e;
                    }
                    interrupted |= Thread.interrupted();
                    seg.reopen();
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void force(Segment seg) throws IOException {
        boolean interrupted = false;
        try {
            for (int i = 0; ; i++) {
                try {
                    seg.channel.force(false);
                    return;
                } catch (ClosedChannelException e) {
                    if (seg.removed || i > 16) {
                        throw e;
                    }
                    interrupted |= Thread.interrupted();
                    seg.reopen();
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void closeAndDelete(Segment seg) {
        seg.removed = true;
        try {
            seg.channel.close();
        } catch (IOException ignored) {}
        try {
            Files.deleteIfExists(seg.path);
        } catch (IOException e) {
            Logger.warn("Failed to delete " + seg.path + ": " + e);
        }
    }

    //==== Opening ====

    private void loadSegments() throws IOException {
        List<Segment> found = new ArrayList<>();
        try (var stream = Files.list(this.directory)) {
            for (var path : (Iterable<Path>) stream::iterator) {
                String name = path.getFileName().toString();
                if (!name.startsWith("seg-") || !name.endsWith(".log")) {
                    continue;
                }
                int id;
                try {
                    id = Integer.parseInt(name.substring(4, name.length() - 4));
                } catch (NumberFormatException e) {
                    continue;
                }
                if (id < 0 || id >= MAX_SEGMENTS) {
                    continue;
                }
                var channel = openChannel(path);
                if (channel.size() < SEG_HEADER_SIZE) {
                    //Crashed while creating the segment
                    channel.close();
                    Files.deleteIfExists(path);
                    continue;
                }
                var header = ByteBuffer.allocate(SEG_HEADER_SIZE);
                var seg = new Segment(id, 0, path, channel);
                readFully(seg, header, 0);
                if (header.getInt(0) != SEG_MAGIC) {
                    Logger.warn("Ignoring unknown file " + path);
                    channel.close();
                    continue;
                }
                found.add(new Segment(id, header.getLong(8), path, channel));
            }
        }
        found.sort(Comparator.<Segment>comparingLong(s -> s.sequence).thenComparingInt(s -> s.id));
        for (var seg : found) {
            this.segments[seg.id] = seg;
            this.segmentCount++;
            this.scan(seg);
            this.nextSequence = Math.max(this.nextSequence, seg.sequence + 1);
            this.totalSize += seg.size;
        }

        var indexIter = this.index.values().iterator();
        while (indexIter.hasNext()) {
            long loc = indexIter.nextLong();
            this.segments[unpackId(loc)].live += HEADER_SIZE + unpackLen(loc);
        }
        var tombIter = this.tombstones.values().iterator();
        while (tombIter.hasNext()) {
            this.segments[tombIter.nextInt()].live += HEADER_SIZE;
        }

        Segment newest = found.isEmpty() ? null : found.get(found.size() - 1);
        if (newest != null && newest.size < SEG_MAX - (4L << 20)) {
            this.active = newest;
        } else {
            this.active = this.newSegment(this.nextSequence++);
        }
    }

    private void scan(Segment seg) throws IOException {
        long size = seg.channel.size();
        long pos = SEG_HEADER_SIZE;
        var header = ByteBuffer.allocate(HEADER_SIZE);
        while (pos + HEADER_SIZE <= size) {
            header.clear();
            readFully(seg, header, pos);
            long key = header.getLong(0);
            int len = header.getInt(8);
            if (len < -1 || pos + HEADER_SIZE + Math.max(len, 0) > Math.min(size, SEG_MAX)) {
                break;
            }
            if (len == -1) {
                this.index.remove(key);
                this.tombstones.put(key, seg.id);
                pos += HEADER_SIZE;
            } else {
                this.index.put(key, pack(seg.id, pos + HEADER_SIZE, len));
                this.tombstones.remove(key);
                pos += HEADER_SIZE + len;
            }
        }
        if (pos != size) {
            Logger.warn("Truncating corrupted tail of " + seg.path + " from " + size + " to " + pos);
            seg.channel.truncate(pos);
        }
        seg.size = pos;
    }

    private void loadMappings() throws IOException {
        long size = this.mappings.channel.size();
        long pos = 0;
        var header = ByteBuffer.allocate(8);
        while (pos + 8 <= size) {
            header.clear();
            readFully(this.mappings, header, pos);
            int id = header.getInt(0);
            int len = header.getInt(4);
            if (len < 0 || pos + 8 + len > size) {
                break;
            }
            var data = new byte[len];
            readFully(this.mappings, ByteBuffer.wrap(data), pos + 8);
            this.idMappings.put(id, data);
            pos += 8 + len;
        }
        if (pos != size) {
            this.mappings.channel.truncate(pos);
        }
        this.mappingsEnd = pos;
    }

    //Must hold the lock (or be in the constructor)
    private Segment newSegment(long sequence) throws IOException {
        int id = -1;
        for (int i = 0; i < MAX_SEGMENTS; i++) {
            if (this.segments[i] == null) {
                id = i;
                break;
            }
        }
        if (id == -1) {
            throw new IllegalStateException("Too many storage segments");
        }
        var path = this.directory.resolve(String.format("seg-%04d.log", id));
        var channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        var seg = new Segment(id, sequence, path, channel);
        var header = ByteBuffer.allocate(SEG_HEADER_SIZE);
        header.putInt(SEG_MAGIC).putInt(1).putLong(sequence).flip();
        writeFully(seg, header, 0);
        seg.size = SEG_HEADER_SIZE;
        this.segments[id] = seg;
        this.segmentCount++;
        this.totalSize += SEG_HEADER_SIZE;
        return seg;
    }

    //==== Reads/writes ====

    //Must hold the lock, returns the packed location of the written record
    private long append(long key, int len, ByteBuffer body) throws IOException {
        long rec = HEADER_SIZE + (long) Math.max(len, 0);
        if (this.active.size + rec > SEG_MAX) {
            try {
                force(this.active);
            } catch (IOException e) {
                Logger.warn("Failed to flush segment " + this.active.path + ": " + e);
            }
            this.active = this.newSegment(this.nextSequence++);
            this.lock.notifyAll();//Wake the maintenance thread, there is a new full segment to look at
        }
        var seg = this.active;
        var header = ByteBuffer.allocate(HEADER_SIZE);
        header.putLong(key).putInt(len).flip();
        long pos = seg.size;
        writeFully(seg, header, pos);
        if (len > 0) {
            writeFully(seg, body, pos + HEADER_SIZE);
        }
        seg.size = pos + rec;
        seg.live += rec;
        this.totalSize += rec;
        return pack(seg.id, pos + HEADER_SIZE, Math.max(len, 0));
    }

    //Must hold the lock
    private void onWriteFailure(IOException e) {
        long now = System.currentTimeMillis();
        if (now - this.lastWriteWarn > 10_000) {
            this.lastWriteWarn = now;
            Logger.warn("Failed to write to voxy storage, the section is not persisted: " + e);
        }
        var msg = e.getMessage();
        if (msg != null && msg.toLowerCase().contains("space")) {
            this.forceEvict = true;
            this.lock.notifyAll();
        }
    }

    @Override
    public MemoryBuffer getSectionData(long key, MemoryBuffer scratch) {
        for (int attempt = 0; ; attempt++) {
            long packed;
            Segment seg;
            synchronized (this.lock) {
                packed = this.index.get(key);
                seg = packed == -1 ? null : this.segments[unpackId(packed)];
            }
            if (packed == -1) {
                return null;
            }
            if (seg != null) {
                int len = unpackLen(packed);
                if (len > scratch.size) {
                    throw new IllegalStateException("Scratch buffer too small for section, " + len + " > " + scratch.size);
                }
                try {
                    readFully(seg, MemoryUtil.memByteBuffer(scratch.address, len), unpackOffset(packed));
                    return scratch.subSize(len);
                } catch (ClosedChannelException e) {
                    //The segment was compacted or evicted while reading, look the section up again
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            if (attempt >= 8) {
                throw new IllegalStateException("Section location kept changing while reading");
            }
        }
    }

    @Override
    public void setSectionData(long key, MemoryBuffer data) {
        int len = (int) data.size;
        if (len > LEN_MASK) {
            throw new IllegalArgumentException("Section too large");
        }
        var body = data.asByteBuffer().duplicate();
        body.position(0).limit(len);
        synchronized (this.lock) {
            if (this.closed) {
                return;
            }
            try {
                long loc = this.append(key, len, body);
                long old = this.index.put(key, loc);
                if (old != -1) {
                    this.segments[unpackId(old)].live -= HEADER_SIZE + unpackLen(old);
                }
                int tomb = this.tombstones.remove(key);
                if (tomb != -1) {
                    this.segments[tomb].live -= HEADER_SIZE;
                }
            } catch (IOException e) {
                this.onWriteFailure(e);
            }
        }
    }

    @Override
    public void deleteSectionData(long key) {
        synchronized (this.lock) {
            if (this.closed) {
                return;
            }
            long old = this.index.remove(key);
            if (old == -1) {
                return;
            }
            this.segments[unpackId(old)].live -= HEADER_SIZE + unpackLen(old);
            try {
                this.append(key, -1, null);
                this.tombstones.put(key, this.active.id);
            } catch (IOException e) {
                this.onWriteFailure(e);
            }
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
                writeFully(this.mappings, record, this.mappingsEnd);
                this.mappingsEnd += 8 + bytes.length;
            } catch (IOException e) {
                synchronized (this.lock) {
                    this.onWriteFailure(e);
                }
            }
            this.idMappings.put(id, bytes);
        }
    }

    @Override
    public Int2ObjectOpenHashMap<byte[]> getIdMappingsData() {
        synchronized (this.idMappings) {
            return new Int2ObjectOpenHashMap<>(this.idMappings);
        }
    }

    //==== Maintenance (compaction + size limit) ====

    private void maintenanceLoop() {
        while (true) {
            synchronized (this.lock) {
                if (this.closed) {
                    return;
                }
                try {
                    this.lock.wait(MAINTENANCE_INTERVAL_MS);
                } catch (InterruptedException e) {
                    return;
                }
                if (this.closed) {
                    return;
                }
            }
            try {
                this.maintain();
            } catch (Throwable t) {
                Logger.error("Voxy storage maintenance failed", t);
            }
        }
    }

    private void maintain() {
        List<Segment> candidates = new ArrayList<>();
        synchronized (this.lock) {
            for (var seg : this.segments) {
                if (seg == null || seg == this.active) {
                    continue;
                }
                long garbage = seg.size - SEG_HEADER_SIZE - seg.live;
                if ((garbage > COMPACT_MIN_GARBAGE && garbage * 5 > seg.size * 2) || (seg.live == 0 && seg.size > SEG_HEADER_SIZE)) {
                    candidates.add(seg);
                }
            }
        }
        //Most garbage first, a few per cycle to keep the io light
        candidates.sort(Comparator.comparingLong((Segment s) -> s.size - s.live).reversed());
        for (int i = 0; i < Math.min(4, candidates.size()); i++) {
            if (this.closed) {
                return;
            }
            this.compact(candidates.get(i));
        }
        this.enforceLimits();
    }

    //Copies the live records of a (non active) segment into a new segment with the same sequence and then drops the old one
    private void compact(Segment src) {
        Segment out = null;
        var keys = new LongArrayList();
        var olds = new LongArrayList();//-1 for tombstones
        var news = new LongArrayList();
        long outPos = SEG_HEADER_SIZE;
        try {
            long end;
            synchronized (this.lock) {
                end = src.size;//Non active segments never grow
            }
            var header = ByteBuffer.allocate(HEADER_SIZE);
            long pos = SEG_HEADER_SIZE;
            while (pos < end) {
                if (this.closed) {
                    this.discard(out);
                    return;
                }
                header.clear();
                readFully(src, header, pos);
                long key = header.getLong(0);
                int len = header.getInt(8);
                if (len < -1) {
                    throw new IOException("Corrupted record in " + src.path);
                }
                long recSize = HEADER_SIZE + (long) Math.max(len, 0);
                long oldLoc = -1;
                boolean keep;
                synchronized (this.lock) {
                    if (len == -1) {
                        keep = this.tombstones.get(key) == src.id;
                    } else {
                        oldLoc = pack(src.id, pos + HEADER_SIZE, len);
                        keep = this.index.get(key) == oldLoc;
                    }
                }
                if (keep) {
                    var rec = ByteBuffer.allocate((int) recSize);
                    readFully(src, rec, pos);
                    rec.flip();
                    if (out == null) {
                        synchronized (this.lock) {
                            out = this.newSegment(src.sequence);
                        }
                    }
                    writeFully(out, rec, outPos);
                    keys.add(key);
                    olds.add(oldLoc);
                    news.add(pack(out.id, outPos + HEADER_SIZE, Math.max(len, 0)));
                    outPos += recSize;
                }
                pos += recSize;
            }
            if (out != null) {
                force(out);
            }
            synchronized (this.lock) {
                if (this.closed) {
                    this.discardLocked(out);
                    return;
                }
                if (out != null) {
                    for (int i = 0; i < keys.size(); i++) {
                        long key = keys.getLong(i);
                        long old = olds.getLong(i);
                        long nw = news.getLong(i);
                        if (old == -1) {
                            if (this.tombstones.get(key) == src.id) {
                                this.tombstones.put(key, out.id);
                                out.live += HEADER_SIZE;
                            }
                        } else if (this.index.get(key) == old) {
                            //Otherwise it was overwritten/deleted while copying and the copy is dead
                            this.index.put(key, nw);
                            out.live += HEADER_SIZE + unpackLen(nw);
                        }
                    }
                    this.totalSize += outPos - out.size;
                    out.size = outPos;
                }
                this.segments[src.id] = null;
                this.segmentCount--;
                this.totalSize -= src.size;
            }
            closeAndDelete(src);
        } catch (IOException | RuntimeException e) {
            Logger.warn("Failed to compact " + src.path + ": " + e);
            this.discard(out);
        }
    }

    private void discard(Segment out) {
        synchronized (this.lock) {
            this.discardLocked(out);
        }
    }

    private void discardLocked(Segment out) {
        if (out == null) {
            return;
        }
        if (this.segments[out.id] == out) {
            this.segments[out.id] = null;
            this.segmentCount--;
            this.totalSize -= out.size;
        }
        closeAndDelete(out);
    }

    private long usableSpace() {
        try {
            return Files.getFileStore(this.directory).getUsableSpace();
        } catch (Throwable t) {
            return Long.MAX_VALUE;
        }
    }

    //Drops the oldest segments (and the sections in them) while over the size limit or low on disk space
    private void enforceLimits() {
        boolean evicting = false;
        for (int i = 0; i < MAX_SEGMENTS; i++) {
            long usable = this.usableSpace();
            Segment victim = null;
            synchronized (this.lock) {
                if (this.closed) {
                    return;
                }
                boolean low = this.forceEvict || usable < MIN_FREE_BYTES;
                boolean over = MAX_BYTES > 0 && (evicting ? this.totalSize > MAX_BYTES * 0.9 : this.totalSize > MAX_BYTES);
                boolean tooMany = this.segmentCount > MAX_SEGMENTS - 16;
                if (!(low || over || tooMany)) {
                    return;
                }
                evicting |= over;
                this.forceEvict = false;
                for (var seg : this.segments) {
                    if (seg == null || seg == this.active) {
                        continue;
                    }
                    if (victim == null || seg.sequence < victim.sequence || (seg.sequence == victim.sequence && seg.id < victim.id)) {
                        victim = seg;
                    }
                }
                if (victim == null) {
                    return;
                }
                int vid = victim.id;
                var it = this.index.long2LongEntrySet().fastIterator();
                while (it.hasNext()) {
                    if (unpackId(it.next().getLongValue()) == vid) {
                        it.remove();
                    }
                }
                var tombIt = this.tombstones.values().iterator();
                while (tombIt.hasNext()) {
                    if (tombIt.nextInt() == vid) {
                        tombIt.remove();
                    }
                }
                this.segments[vid] = null;
                this.segmentCount--;
                this.totalSize -= victim.size;
            }
            Logger.info("Voxy storage limit reached, evicting oldest segment " + victim.path.getFileName() + " (" + (victim.size >> 20) + "MB)");
            closeAndDelete(victim);
        }
    }

    //==== Lifecycle ====

    @Override
    public void flush() {
        try {
            synchronized (this.lock) {
                if (!this.closed && this.active != null) {
                    force(this.active);
                }
            }
            synchronized (this.idMappings) {
                if (!this.closed) {
                    force(this.mappings);
                }
            }
        } catch (IOException e) {
            Logger.warn("Failed to flush voxy storage: " + e);
        }
    }

    @Override
    public void close() {
        this.flush();
        synchronized (this.lock) {
            if (this.closed) {
                return;
            }
            this.closed = true;
            this.lock.notifyAll();
        }
        try {
            this.maintenance.join(15_000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        synchronized (this.lock) {
            for (var seg : this.segments) {
                if (seg != null) {
                    try {
                        seg.channel.close();
                    } catch (IOException ignored) {}
                }
            }
        }
        synchronized (this.idMappings) {
            try {
                this.mappings.channel.close();
            } catch (IOException ignored) {}
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

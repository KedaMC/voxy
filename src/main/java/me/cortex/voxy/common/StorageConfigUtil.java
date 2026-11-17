package me.cortex.voxy.common;

import me.cortex.voxy.common.config.Serialization;
import me.cortex.voxy.common.config.compressors.LZ4Compressor;
import me.cortex.voxy.common.config.compressors.ZSTDCompressor;
import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.config.section.SectionStorageConfig;
import me.cortex.voxy.common.config.storage.other.CompressionStorageAdaptor;
import me.cortex.voxy.common.config.storage.file.FileStorageBackend;
import me.cortex.voxy.common.config.storage.rocksdb.RocksDBStorageBackend;
import me.cortex.voxy.common.util.ThreadUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class StorageConfigUtil {

    public static <T> T getCreateStorageConfig(Class<T> clz, Predicate<T> verifier, Supplier<T> defaultConfig, Path path) {
        try {
            Files.createDirectories(path);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        var json = path.resolve("config.json");
        T config = null;
        if (Files.exists(json)) {
            try {
                var text = Files.readString(json);
                if (ThreadUtils.isAndroid && text.contains("\"ZSTD\"")) {
                    //The zstd compressor needs LWJGL natives that do not exist on Android, RocksDB compresses internally
                    Logger.warn("Storage config uses the ZSTD compressor which is unavailable on Android, resetting it to default");
                } else {
                    config = Serialization.GSON.fromJson(text, clz);
                }
                if (config == null) {
                    if (!(ThreadUtils.isAndroid && text.contains("\"ZSTD\""))) {
                        Logger.error("Config deserialization null, reverting to default");
                    }
                } else {
                    if (!verifier.test(config)) {
                        Logger.error("Config section storage null, reverting to default");
                        config = null;
                    }
                }
            } catch (Exception e) {
                Logger.error("Failed to load the storage configuration file, resetting it to default, this will probably break your save if you used a custom storage config", e);
            }
        }

        if (config == null) {
            config = defaultConfig.get();
        }
        try {
            Files.writeString(json, Serialization.GSON.toJson(config));
        } catch (Exception e) {
            throw new RuntimeException("Failed write the config, aborting!", e);
        }
        if (config == null) {
            throw new IllegalStateException("Config is still null\n");
        }
        return config;
    }

    public static SectionSerializationStorage.Config createDefaultSerializer() {
        //Create the default config
        var baseDB = new RocksDBStorageBackend.Config();

        if (ThreadUtils.isAndroid) {
            //No native libraries (rocksdb, lwjgl zstd/lmdb) are available on Android, use the pure java file backend with java lz4
            var compression = new CompressionStorageAdaptor.Config();
            compression.delegate = new FileStorageBackend.Config();
            compression.compressor = new LZ4Compressor.Config();

            var serializer = new SectionSerializationStorage.Config();
            serializer.storage = compression;
            return serializer;
        }

        var compressor = new ZSTDCompressor.Config();
        compressor.compressionLevel = 1;

        var compression = new CompressionStorageAdaptor.Config();
        compression.delegate = baseDB;
        compression.compressor = compressor;

        var serializer = new SectionSerializationStorage.Config();
        serializer.storage = compression;

        return serializer;
    }
}

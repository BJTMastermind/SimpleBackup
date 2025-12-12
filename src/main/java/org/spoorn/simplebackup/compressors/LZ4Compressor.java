package org.spoorn.simplebackup.compressors;

import java.util.concurrent.Executors;

import org.spoorn.simplebackup.SimpleBackup;
import org.spoorn.simplebackup.config.ModConfig;
import org.spoorn.simplebackup.util.SimpleBackupUtil;
import org.spoorn.tarlz4java.api.TarLz4Compressor;
import org.spoorn.tarlz4java.api.TarLz4CompressorBuilder;
import org.spoorn.tarlz4java.logging.Verbosity;
import org.spoorn.tarlz4java.util.concurrent.NamedThreadFactory;

public class LZ4Compressor {
    public static final String TAR_LZ4_EXTENSION = ".tar.lz4";
    private static boolean shouldLogBackupProgress = false;

    public static void init() {
        shouldLogBackupProgress = ModConfig.getInstance().intervalPercentageToLogBackupProgress > 0 && ModConfig.getInstance().intervalPercentageToLogBackupProgress <= 100;
    }

    // TODO: Add support for switching between fast vs high compressor
    public static boolean compress(String targetPath, String destinationPath, String outputFileBaseName) {
        try {
            int numThreads = ModConfig.getInstance().numThreads;
            TarLz4Compressor compressor = new TarLz4CompressorBuilder()
                    .numThreads(numThreads)
                    .bufferSize(ModConfig.getInstance().multiThreadBufferSize)
                    .logProgressPercentInterval(ModConfig.getInstance().intervalPercentageToLogBackupProgress)
                    .executorService(Executors.newFixedThreadPool(numThreads, new NamedThreadFactory("SimpleBackup")))
                    .shouldLogProgress(shouldLogBackupProgress)
                    .verbosity(Verbosity.DEBUG)
                    .excludeFiles(SimpleBackupUtil.FILES_TO_SKIP_COPY)
                    .build();
            return compressor.compress(targetPath, destinationPath, outputFileBaseName) != null;
        } catch (Exception e) {
            SimpleBackup.LOGGER.error("Could not lz4 compress target=[" + targetPath + "] to [" + destinationPath + "]", e);
            return false;
        }
    }
}

package org.spoorn.simplebackup.compressors;

import java.io.File;

import org.spoorn.simplebackup.SimpleBackup;
import org.spoorn.simplebackup.config.ModConfig;
import org.spoorn.simplebackup.util.SimpleBackupUtil;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.ExcludeFileFilter;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.progress.ProgressMonitor;

public class ZipCompressor {
    public static final String ZIP_EXTENSION = ".zip";

    private static boolean shouldLogBackupProgress = false;

    public static void init() {
        shouldLogBackupProgress = ModConfig.getInstance().intervalPercentageToLogBackupProgress > 0 && ModConfig.getInstance().intervalPercentageToLogBackupProgress <= 100;
    }

    public static boolean zip(String targetPath, String destinationPath) {
        try {
            ExcludeFileFilter excludeFileFilter = file -> SimpleBackupUtil.FILES_TO_SKIP_COPY.contains(file.getName());
            ZipParameters parameters = new ZipParameters();
            parameters.setExcludeFileFilter(excludeFileFilter);

            ZipFile zipFile = new ZipFile(destinationPath + ZIP_EXTENSION);

            if (shouldLogBackupProgress) {
                zipFile.setRunInThread(true);
            }

            ProgressMonitor progressMonitor = zipFile.getProgressMonitor();

            File targetFile = new File(targetPath);
            if (targetFile.isDirectory()) {
                zipFile.addFolder(targetFile, parameters);
            } else if (targetFile.isFile()) {
                zipFile.addFile(targetFile, parameters);
            } else {
                zipFile.close();
                throw new IllegalArgumentException("Target Path=" + targetPath + " is not a valid file or directory to backup");
            }
            zipFile.close();

            if (shouldLogBackupProgress) {
                int prevPercent = 0;
                int interval = ModConfig.getInstance().intervalPercentageToLogBackupProgress;
                while (progressMonitor.getState() != ProgressMonitor.State.READY) {
                    int currPercent = progressMonitor.getPercentDone();
                    if (prevPercent / interval < currPercent / interval) {
                        SimpleBackup.LOGGER.info("Backup progress: {}%", currPercent);
                    }
                    prevPercent = currPercent;
                    Thread.sleep(100);
                }
            }
            return true;
        } catch (Exception e) {
            SimpleBackup.LOGGER.error("Could not zip target=[" + targetPath + "] to [" + destinationPath + "]", e);
            return false;
        }
    }
}

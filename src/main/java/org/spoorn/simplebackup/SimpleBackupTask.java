package org.spoorn.simplebackup;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import org.spoorn.simplebackup.compressors.LZ4Compressor;
import org.spoorn.simplebackup.compressors.ZipCompressor;
import org.spoorn.simplebackup.config.ModConfig;
import org.spoorn.simplebackup.util.SimpleBackupUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;

public class SimpleBackupTask implements Runnable {
    private static final DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static Component BROADCAST1;
    private static Component SUCCESS_BROADCAST;
    private static Component FAILED_BROADCAST1;
    private static Component FAILED_BROADCAST2;

    public final Object lock = new Object();
    public boolean isProcessing = false;
    public Path lastBackupProcessed;
    private final String worldFolderName;
    private final Path worldSavePath;
    private final MinecraftServer server;
    private final long backupIntervalInMillis;
    private final String backupFormat;

    private boolean terminated = false;

    SimpleBackupTask(String worldFolderName, Path worldSavePath, MinecraftServer server, int backupIntervalInSeconds, String backupFormat) {
        this.worldFolderName = worldFolderName;
        this.worldSavePath = worldSavePath;
        this.server = server;
        this.backupIntervalInMillis = backupIntervalInSeconds * 1000L;
        this.backupFormat = backupFormat;
    }

    public static void init() {
        Map<String, String> broadcastMessages = ModConfig.getInstance().broadcastMessages;
        BROADCAST1 = Component.literal(broadcastMessages.getOrDefault("simplebackup.backup.broadcast", "Starting server backup...")).setStyle(Style.EMPTY.withColor(13543679));
        SUCCESS_BROADCAST = Component.literal(broadcastMessages.getOrDefault("simplebackup.backup.success.broadcast", "Server was successfully backed up to "));
        FAILED_BROADCAST1 = Component.literal(broadcastMessages.getOrDefault("simplebackup.backup.failed.broadcast1", "Server failed to backup to "));
        FAILED_BROADCAST2 = Component.literal(broadcastMessages.getOrDefault("simplebackup.backup.failed.broadcast2", ".  Please check the server logs for errors!"));
    }

    public static SimpleBackupTaskBuilder builder(final String worldFolderName, final Path worldSavePath,
                                                  final MinecraftServer server) {
        return new SimpleBackupTaskBuilder().worldFolderName(worldFolderName).worldSavePath(worldSavePath).server(server);
    }

    public static SimpleBackupTaskBuilder builder(final String worldFolderName, final Path worldSavePath,
                                                  final MinecraftServer server, String backupFormat) {
        return new SimpleBackupTaskBuilder().worldFolderName(worldFolderName).worldSavePath(worldSavePath).server(server).backupFormat(backupFormat);
    }

    public void terminate() {
        this.terminated = true;
        synchronized (this.lock) {
            this.lock.notify();
        }
    }

    @Override
    public void run() {
        PlayerList playerManager = this.server.getPlayerList();

        // wait at start
        if (!terminated && this.backupIntervalInMillis > 1000) {
            waitToContinue(playerManager);
        }

        // Automatic backup loops
        while (!terminated) {
            backup();
            if (this.backupIntervalInMillis > 1000) {
                waitToContinue(playerManager);
            } else {
                // Single run
                break;
            }
        }

        SimpleBackup.LOGGER.info("SimpleBackupTask Finished!");
    }

    public void backup() {
        this.isProcessing = true;
        PlayerList playerManager = this.server.getPlayerList();

        String timeStr = dtf.format(LocalDateTime.now());
        SimpleBackupUtil.broadcastMessage(BROADCAST1, playerManager);

        String broadcastBackupPath;
        if (SimpleBackupUtil.ZIP_FORMAT.equals(this.backupFormat)) {
            broadcastBackupPath = timeStr + ZipCompressor.ZIP_EXTENSION;
            this.lastBackupProcessed = SimpleBackupUtil.getBackupPath().resolve(broadcastBackupPath);
        } else if (SimpleBackupUtil.LZ4_FORMAT.equals(this.backupFormat)) {
            broadcastBackupPath = timeStr + LZ4Compressor.TAR_LZ4_EXTENSION;
            this.lastBackupProcessed = SimpleBackupUtil.getBackupPath().resolve(broadcastBackupPath);
        } else {
            broadcastBackupPath = timeStr + "/" + this.worldFolderName;
            this.lastBackupProcessed = SimpleBackupUtil.getBackupPath().resolve(timeStr);
        }
        boolean copied = SimpleBackupUtil.backup(this.worldSavePath, this.worldFolderName, timeStr, this.backupFormat)
                && SimpleBackupUtil.deleteStaleBackupFiles();
        Component relFolderPath = Component.literal(broadcastBackupPath);
        if (copied) {
            SimpleBackup.LOGGER.info("Successfully backed up world [{}] to [{}]", this.worldFolderName, broadcastBackupPath);
            SimpleBackupUtil.broadcastMessage(SUCCESS_BROADCAST.plainCopy().append(relFolderPath).setStyle(Style.EMPTY.withColor(8060843)), playerManager);
        } else {
            SimpleBackup.LOGGER.error("Server backup for world [{}] failed!  Check the logs for errors.", this.worldFolderName);
            SimpleBackupUtil.broadcastMessage(FAILED_BROADCAST1.plainCopy().append(relFolderPath).append(FAILED_BROADCAST2).setStyle(Style.EMPTY.withColor(16754871)), playerManager);
        }
        this.isProcessing = false;
    }

    // This doesn't account for spurious wakeups!
    private void waitToContinue(PlayerList playerManager) {
        // Automatic periodic backups
        try {
            // Technically there is an extremely small window where all server players can log out between the
            // backup and this check, so we'll never backup that window.  But it's small enough to not worry about practically
            // The below logic to wait on the lock will simply wait if we just backed up, but there are no players
            // online, or the single player game is paused.  This does mean the next backup's changed content
            // might span a duration less than the backup intervals, but this is intended as I think it's better
            // than trying to make sure each backup has an exact "online running" difference from the previous.
            if ((ModConfig.getInstance().onlyBackupIfPlayersOnline && playerManager.getPlayerCount() == 0)
                || (this.server.isSingleplayer())) {
                // Wait until a player logs on
                synchronized (this.lock) {
                    this.lock.wait();
                }
            }

            if (!this.terminated) {
                Thread.sleep(this.backupIntervalInMillis);
            }
        } catch (InterruptedException e) {
            if (this.terminated) {
                SimpleBackup.LOGGER.info("SimpleBackupTask interrupted by main thread");
            } else {
                SimpleBackup.LOGGER.error("SimpleBackupTask thread interrupted", e);
            }
        }
    }

    /**
     * Manual builder because lombok is stupid: https://github.com/projectlombok/lombok/issues/2307.
     */
    public static class SimpleBackupTaskBuilder {
        private String worldFolderName;
        private Path worldSavePath;
        private MinecraftServer server;
        private int backupIntervalInSeconds = -1;
        private String backupFormat = ModConfig.getInstance().backupFormat;

        SimpleBackupTaskBuilder() {
        }

        public SimpleBackupTaskBuilder worldFolderName(String worldFolderName) {
            this.worldFolderName = worldFolderName;
            return this;
        }

        public SimpleBackupTaskBuilder worldSavePath(Path worldSavePath) {
            this.worldSavePath = worldSavePath;
            return this;
        }

        public SimpleBackupTaskBuilder server(MinecraftServer server) {
            this.server = server;
            return this;
        }

        public SimpleBackupTaskBuilder backupIntervalInSeconds(int backupIntervalInSeconds) {
            this.backupIntervalInSeconds = Math.max(10, backupIntervalInSeconds);
            return this;
        }

        public SimpleBackupTaskBuilder backupFormat(String backupFormat) {
            this.backupFormat = backupFormat;
            return this;
        }

        public SimpleBackupTask build() {
            return new SimpleBackupTask(worldFolderName, worldSavePath, server, backupIntervalInSeconds, backupFormat);
        }

        public String toString() {
            return "SimpleBackupTask.SimpleBackupTaskBuilder(worldFolderName=" + this.worldFolderName + ", worldSavePath="
                    + this.worldSavePath + ", server=" + this.server + ", backupIntervalInSeconds=" + this.backupIntervalInSeconds
                    + ", backupFormat=" + this.backupFormat + ")";
        }
    }
}

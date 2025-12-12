package org.spoorn.simplebackup;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spoorn.simplebackup.compressors.LZ4Compressor;
import org.spoorn.simplebackup.compressors.ZipCompressor;
import org.spoorn.simplebackup.config.ModConfig;
import org.spoorn.simplebackup.mixin.MinecraftServerAccessor;
import org.spoorn.simplebackup.util.SimpleBackupUtil;

import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

public class SimpleBackup implements ModInitializer {
    public static final String MOD_ID = "simplebackup";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final AtomicReference<SimpleBackupTask> manualBackupTask = new AtomicReference<>();
    public static AtomicReference<SimpleBackupTask> simpleBackupTask = new AtomicReference<>();
    public static AtomicReference<SimpleBackupTask> serverEndBackupTask = new AtomicReference<>();
    //public static ExecutorService EXECUTOR_SERVICE;

    @Override
    public void onInitialize() {
        LOGGER.info("Hello from SimpleBackup!");

        // Config
        ModConfig.load();

        //EXECUTOR_SERVICE = Executors.newFixedThreadPool(ModConfigNew.getInstance().numThreads, new ThreadFactoryBuilder().setNameFormat("SimpleBackup-%d").build());

        // Lang for backup broadcast messages
        SimpleBackupTask.init();

        // Compressors init
        LZ4Compressor.init();
        ZipCompressor.init();

        // Create worlds backup folder
        Path backupsPath = SimpleBackupUtil.getBackupPath();
        SimpleBackupUtil.createDirectoryFailSafe(backupsPath);
        LOGGER.info("Worlds backup folder: {}", backupsPath);

        // Automatic backups
        final boolean enableAutomaticBackups = ModConfig.getInstance().enableAutomaticBackups;
        final AtomicReference<Thread> automaticBackupThread = new AtomicReference<>();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (enableAutomaticBackups) {
                LOGGER.info("Automatic backups are enabled");
                MinecraftServerAccessor accessor = (MinecraftServerAccessor) server;
                String worldFolderName = accessor.getSession().getLevelId();
                Path worldSavePath = accessor.getSession().getLevelPath(LevelResource.ROOT).getParent();

                int backupIntervals = ModConfig.getInstance().backupIntervalInSeconds;
                LOGGER.info("Scheduling a backup every {} seconds...", Math.max(10, backupIntervals));
                simpleBackupTask.set(SimpleBackupTask.builder(worldFolderName, worldSavePath, server)
                        .backupIntervalInSeconds(backupIntervals)
                        .build());
                Thread backupThread = new Thread(simpleBackupTask.get());
                backupThread.start();
                automaticBackupThread.set(backupThread);
            }
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            SimpleBackupTask autoBackup;
            if ((autoBackup = simpleBackupTask.get()) != null) {
                if (autoBackup.isProcessing && autoBackup.lastBackupProcessed != null) {
                    SimpleBackupUtil.cleanupFailedBackup(autoBackup.lastBackupProcessed);
                }
            }
        }));

        // Notify backup thread when player joins server
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            SimpleBackupTask autoBackup;
            if (enableAutomaticBackups && (autoBackup = simpleBackupTask.get()) != null) {
                synchronized (autoBackup.lock) {
                    autoBackup.lock.notify();
                }
            }
        });

        // Backup when server is stopped
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            SimpleBackupTask autoBackup;
            if (enableAutomaticBackups && (autoBackup = simpleBackupTask.get()) != null) {
                LOGGER.info("Terminating automatic backup thread");
                autoBackup.terminate();
                if (automaticBackupThread.get() != null) {
                    automaticBackupThread.get().interrupt();
                }
            }

            if (ModConfig.getInstance().enableServerStoppedBackup) {
                LOGGER.info("Server has stopped - creating a backup");
                MinecraftServerAccessor accessor = (MinecraftServerAccessor) server;
                String worldFolderName = accessor.getSession().getLevelId();
                Path worldSavePath = accessor.getSession().getLevelPath(LevelResource.ROOT).getParent();

                SimpleBackupTask serverStopBackup = SimpleBackupTask.builder(worldFolderName, worldSavePath, server)
                                .build();
                serverEndBackupTask.set(serverStopBackup);
                serverStopBackup.backup();

                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    if (serverStopBackup.isProcessing && serverStopBackup.lastBackupProcessed != null) {
                        SimpleBackupUtil.cleanupFailedBackup(serverStopBackup.lastBackupProcessed);
                    }
                }));
            }
        });

        // Commands
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("simplebackup")
                    .requires(ctx -> ctx.hasPermission(ModConfig.getInstance().permissionLevelForManualBackups))
                    .then(Commands.literal("start")
                        .executes(c -> this.triggerManualBackup(c, ModConfig.getInstance().backupFormat)))
                    .then(Commands.literal("zip")
                        .executes(c -> this.triggerManualBackup(c, SimpleBackupUtil.ZIP_FORMAT)))
                    .then(Commands.literal("directory")
                        .executes(c -> this.triggerManualBackup(c, SimpleBackupUtil.DIRECTORY_FORMAT)))
                    .then(Commands.literal("lz4")
                        .executes(c -> this.triggerManualBackup(c, SimpleBackupUtil.LZ4_FORMAT)))
                    );
        });
    }

    private int triggerManualBackup(CommandContext<CommandSourceStack> c, String backupFormat) {
        Map<String, String> broadcastMessages = ModConfig.getInstance().broadcastMessages;
        try {
            CommandSourceStack commandSource = c.getSource();
            // Check manual backups enabled
            if (!ModConfig.getInstance().enableManualBackups) {
                commandSource.sendSuccess(() -> Component.literal(broadcastMessages.getOrDefault("simplebackup.manualbackup.disabled",
                        "Manual backups are disabled by the server!"))
                        .setStyle(Style.EMPTY.withColor(16433282)), true);
                return 1;
            }

            boolean fromPlayer = commandSource.getPlayer() != null;

            // Try manual backup
            synchronized (manualBackupTask) {
                if (manualBackupTask.get() != null) {
                    commandSource.sendSuccess(() -> Component.literal(broadcastMessages.getOrDefault("simplebackup.manualbackup.alreadyexists",
                            "There is already an ongoing manual backup.  Please wait for it to finish before starting another!"))
                            .setStyle(Style.EMPTY.withColor(16433282)), true);
                } else {
                    if (fromPlayer) {
                        commandSource.getServer().getPlayerList().broadcastSystemMessage(
                                c.getSource().getPlayer().getDisplayName().copy().append(
                                        Component.literal(broadcastMessages.getOrDefault("simplebackup.manualbackup.started",
                                                " triggered a manual backup"))
                                                .setStyle(Style.EMPTY.withColor(16433282))), false);
                    } else {
                        // Could not find a player, so broadcasting as a general message
                        commandSource.getServer().getPlayerList().broadcastSystemMessage(Component.literal("Server" +
                                broadcastMessages.getOrDefault("simplebackup.manualbackup.started", " triggered a manual backup"))
                                .setStyle(Style.EMPTY.withColor(16433282)), false);
                    }

                    MinecraftServer server = commandSource.getServer();
                    MinecraftServerAccessor accessor = (MinecraftServerAccessor) server;
                    String worldFolderName = accessor.getSession().getLevelId();
                    Path worldSavePath = accessor.getSession().getLevelPath(LevelResource.ROOT).getParent();

                    SimpleBackupTask serverStopBackup = SimpleBackupTask.builder(worldFolderName, worldSavePath, server, backupFormat)
                            .build();
                    manualBackupTask.set(serverStopBackup);
                    new Thread(() -> {
                        serverStopBackup.run();
                        manualBackupTask.set(null);
                    }).start();
                }
            }
            return 1;
        } catch (Exception e) {
            LOGGER.error("Could not create manual backup!", e);
            return 0;
        }
    }
}

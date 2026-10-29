package dev.blocknet.bridge;

import dev.blocknet.Blocknet;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Starts one brain process per spawned bot when the bridge config names a brain directory, and
 * stops it with its whole process tree when the bot leaves (ADR-0003: the brain stays a separate
 * process, the body only launches it). Output goes to {@code logs/blocknet/brain-<bot>.log}.
 */
public final class BrainLauncher {
    private static final Map<String, Process> RUNNING = new ConcurrentHashMap<>();

    static {
        // Brains must not outlive a game that exits without stopping its server (a crash, a closed window).
        Runtime.getRuntime().addShutdownHook(new Thread(BrainLauncher::stopAll, "blocknet-brain-stop"));
    }

    private BrainLauncher() {}

    /** Starts the configured brain for a bot just spawned on the live server; returns a line for the player. */
    public static String start(String bot) {
        if (!BridgeServer.listening()) {
            return "brain not started: the bridge is not listening";
        }
        return start(bot, BridgeConfig.path(), FMLPaths.GAMEDIR.get().resolve("logs").resolve("blocknet"));
    }

    /** Starts {@code node <brain>/brain.js --bot <bot> --config <config>} unless one already runs for the bot. */
    public static String start(String bot, Path configPath, Path logDir) {
        BridgeConfig config;
        try {
            config = BridgeConfig.load(configPath);
        } catch (IOException | RuntimeException exception) {
            return "brain not started: " + exception.getMessage();
        }
        if (config.brain() == null || config.brain().isBlank()) {
            return "no brain configured (set \"brain\" in " + configPath.getFileName() + " to start one on spawn)";
        }
        Path brainDir = Path.of(config.brain()).toAbsolutePath().normalize();
        Path script = brainDir.resolve("brain.js");
        if (!Files.isRegularFile(script)) {
            return "brain not started: no brain.js in " + brainDir;
        }
        String key = key(bot);
        Process previous = RUNNING.get(key);
        if (previous != null && previous.isAlive()) {
            return "brain already running for " + bot;
        }
        String node = config.node() == null || config.node().isBlank() ? "node" : config.node();
        Path log = logDir.resolve("brain-" + bot + ".log");
        try {
            Files.createDirectories(logDir);
            Files.writeString(log, "--- " + LocalDateTime.now() + " starting brain for " + bot + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            Process process = new ProcessBuilder(List.of(node, script.toString(), "--bot", bot,
                    "--config", configPath.toAbsolutePath().toString()))
                .directory(brainDir.getParent().toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                .start();
            RUNNING.put(key, process);
            process.onExit().thenAccept(exited -> {
                // A brain stopped on purpose was removed first, so only an unexpected exit is reported.
                if (RUNNING.remove(key, exited)) {
                    unexpectedExit(bot, exited.exitValue(), log);
                }
            });
            Blocknet.LOGGER.info("Started brain for {} (pid {}), log {}", bot, process.pid(), log);
            return "brain started (log: " + log + ")";
        } catch (IOException exception) {
            return "brain failed to start: " + exception.getMessage();
        }
    }

    public static boolean running(String bot) {
        Process process = RUNNING.get(key(bot));
        return process != null && process.isAlive();
    }

    /** Stops the bot's brain and everything it spawned (model CLIs, the MCP wrapper). */
    public static void stop(String bot) {
        Process process = RUNNING.remove(key(bot));
        if (process == null) {
            return;
        }
        // Children first: once the parent is gone, Windows no longer links them to this tree.
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        Blocknet.LOGGER.info("Stopped brain for {}", bot);
    }

    public static void stopAll() {
        List.copyOf(RUNNING.keySet()).forEach(BrainLauncher::stop);
    }

    private static void unexpectedExit(String bot, int code, Path log) {
        Blocknet.LOGGER.warn("Brain for {} exited with code {}, see {}", bot, code, log);
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            server.execute(() -> server.getPlayerList().broadcastSystemMessage(
                Component.literal("brain for " + bot + " stopped (exit " + code + "), see " + log), false));
        }
    }

    private static String key(String bot) {
        return bot.toLowerCase(Locale.ROOT);
    }
}

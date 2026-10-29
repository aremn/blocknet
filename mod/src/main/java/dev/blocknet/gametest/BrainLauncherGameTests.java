package dev.blocknet.gametest;

import dev.blocknet.Blocknet;
import dev.blocknet.body.BotManager;
import dev.blocknet.bridge.BrainLauncher;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(Blocknet.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BrainLauncherGameTests {
    private BrainLauncherGameTests() {}

    /** A stand-in brain.js: records its arguments and its own child, then idles like the real brain. */
    private static final String FAKE_BRAIN = """
        const { spawn } = require('child_process');
        const fs = require('fs');
        const path = require('path');
        const child = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], { stdio: 'ignore' });
        fs.writeFileSync(path.join(__dirname, 'started.json'),
          JSON.stringify({ args: process.argv.slice(2), child: child.pid }));
        setInterval(() => {}, 1000);
        """;

    @GameTest(template = "arena", timeoutTicks = 600)
    public static void spawnStartsBrainAndDespawnStopsItsTree(GameTestHelper helper) throws Exception {
        TestArena.floor(helper);
        String name = "BnBrain";
        Path root = Files.createTempDirectory("blocknet-brain-test");
        Path brainDir = Files.createDirectories(root.resolve("brain"));
        Files.writeString(brainDir.resolve("brain.js"), FAKE_BRAIN, StandardCharsets.UTF_8);
        Path config = root.resolve("bridge.json");
        Files.writeString(config, "{\"port\":25599,\"token\":\"t\",\"brain\":\""
            + brainDir.toString().replace("\\", "\\\\") + "\"}");
        Path unconfigured = root.resolve("plain.json");
        Files.writeString(unconfigured, "{\"port\":25599,\"token\":\"t\"}");

        String none = BrainLauncher.start(name, unconfigured, root.resolve("logs"));
        helper.assertTrue(none.startsWith("no brain configured") && !BrainLauncher.running(name),
            "a config without brain starts nothing: " + none);

        TestArena.spawnBot(helper, name, new Vec3(1.5, 1, 1.5));
        String started = BrainLauncher.start(name, config, root.resolve("logs"));
        helper.assertTrue(started.startsWith("brain started"), "brain starts: " + started);
        String again = BrainLauncher.start(name, config, root.resolve("logs"));
        helper.assertTrue(again.startsWith("brain already running"), "one brain per bot: " + again);

        Path record = brainDir.resolve("started.json");
        var server = helper.getLevel().getServer();
        // Waiting on the process must never block the server thread that ticks the test.
        var result = CompletableFuture.runAsync(() -> {
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (!Files.exists(record) || Files.size(record) == 0) {
                    if (System.nanoTime() > deadline) throw new IllegalStateException("fake brain never wrote started.json");
                    Thread.sleep(50);
                }
                var seen = com.google.gson.JsonParser.parseString(Files.readString(record)).getAsJsonObject();
                String args = seen.getAsJsonArray("args").toString();
                check(args.contains("\"--bot\",\"" + name + "\""), "brain gets the bot name: " + args);
                check(args.contains("\"--config\"") && args.contains("bridge.json"), "brain gets the config path: " + args);
                var child = ProcessHandle.of(seen.get("child").getAsLong()).orElseThrow();
                check(BrainLauncher.running(name), "brain still running before despawn");

                server.submit(() -> BotManager.despawn(name)).get(10, TimeUnit.SECONDS);
                child.onExit().get(10, TimeUnit.SECONDS);
                check(!BrainLauncher.running(name), "despawn stops the brain");
                check(!child.isAlive(), "despawn stops what the brain spawned");
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(result.isDone(), "brain lifecycle pending");
            try {
                result.join();
            } finally {
                BrainLauncher.stop(name);
                BotManager.despawn(name);
            }
        });
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}

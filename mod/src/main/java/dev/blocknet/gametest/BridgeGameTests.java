package dev.blocknet.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.blocknet.Blocknet;
import dev.blocknet.body.BotManager;
import dev.blocknet.bridge.BridgeServer;
import dev.blocknet.bridge.BridgeTools;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(Blocknet.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BridgeGameTests {
    private BridgeGameTests() {}

    @GameTest(template = "arena", timeoutTicks = 400)
    public static void authenticatesListsCallsAndFinishesTask(GameTestHelper helper) throws Exception {
        TestArena.floor(helper);
        var bot = TestArena.spawnBot(helper, "BnBridge", new Vec3(1.5, 1, 4.5));
        BlockPos goal = helper.absolutePos(new BlockPos(5, 1, 4));
        var bridge = new BridgeServer(helper.getLevel().getServer(), 0, "test-secret");
        // Socket reads must never block the server thread that executes the requests.
        var result = CompletableFuture.runAsync(() -> {
            try (bridge; Socket socket = new Socket("127.0.0.1", bridge.port())) {
                socket.setSoTimeout(15000);
                var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                var output = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
                output.println("{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"tool.call\",\"params\":{}}");
                check(read(input).has("error"), "tool rejected before hello");
                output.println("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"hello\",\"params\":{\"protocol\":1,\"token\":\"wrong\"}}");
                check(read(input).has("error"), "wrong token rejected");
                output.println("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"hello\",\"params\":{\"protocol\":1,\"token\":\"test-secret\"}}");
                var hello = read(input).getAsJsonObject("result");
                check(hello.get("protocol").getAsInt() == 1 && hello.getAsJsonArray("tools").size() == BridgeTools.list().size(), "hello lists all tools");
                check(hello.getAsJsonArray("tools").asList().stream()
                    .anyMatch(tool -> tool.getAsJsonObject().get("name").getAsString().equals("gather")), "hello lists gather");
                check(hello.getAsJsonArray("bots").toString().contains("BnBridge"), "hello lists bot");
                output.println("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tool.call\",\"params\":{\"bot\":\"BnBridge\",\"tool\":\"status\"}}");
                check(read(input).getAsJsonObject("result").get("result").getAsString().contains("health"), "status reports body");
                output.println("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tool.call\",\"params\":{\"bot\":\"BnBridge\",\"tool\":\"goto\",\"args\":{\"x\":"
                    + goal.getX() + ",\"y\":" + goal.getY() + ",\"z\":" + goal.getZ() + "}}}");
                var accepted = read(input).getAsJsonObject("result");
                String id = accepted.get("task_id").getAsString();
                JsonObject message;
                JsonObject event;
                // Each bridge receives lifecycle events for every bot, including parallel fixtures.
                // Wait for this command's terminal event rather than assuming it is the next line.
                do {
                    message = read(input);
                    event = message.getAsJsonObject("params");
                } while (event == null || !event.has("task_id") || !event.get("task_id").getAsString().equals(id)
                    || !event.has("bot") || !event.get("bot").getAsString().equals("BnBridge")
                    || !event.has("type") || !event.get("type").getAsString().equals("task.finished"));
                check(message.get("method").getAsString().equals("event")
                    && event.get("type").getAsString().equals("task.finished")
                    && event.get("task_id").getAsString().equals(id)
                    && event.get("bot").getAsString().equals("BnBridge")
                    && event.get("result").getAsString().contains("arrived"), "matching arrived event: " + message);
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(result.isDone(), "socket round trip pending");
            try {
                result.join();
            } catch (RuntimeException exception) {
                BotManager.despawn("BnBridge");
                throw exception;
            }
            helper.assertTrue(bot.blockPosition().equals(goal), "bot physically arrived");
            BotManager.despawn("BnBridge");
        });
    }

    @GameTest(template = "arena", timeoutTicks = 250)
    public static void deathRespawnsAndBroadcastsLifecycle(GameTestHelper helper) throws Exception {
        TestArena.floor(helper);
        var bot = TestArena.spawnBot(helper, "BnReviveEvent", new Vec3(4.5, 1, 4.5));
        bot.setRespawnPosition(helper.getLevel().dimension(), bot.blockPosition(), 0, true, false);
        var bridge = new BridgeServer(helper.getLevel().getServer(), 0, "respawn-test");
        var result = CompletableFuture.runAsync(() -> {
            try (bridge; Socket socket = new Socket("127.0.0.1", bridge.port())) {
                socket.setSoTimeout(15000);
                var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                var output = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
                output.println("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"hello\",\"params\":{\"protocol\":1,\"token\":\"respawn-test\"}}");
                read(input);
                helper.getLevel().getServer().execute(() -> bot.hurt(bot.damageSources().genericKill(), Float.MAX_VALUE));
                boolean died = false;
                while (true) {
                    var message = read(input);
                    if (!message.has("params")) continue;
                    var event = message.getAsJsonObject("params");
                    if (!event.has("bot") || !event.get("bot").getAsString().equals("BnReviveEvent")) continue;
                    if (event.get("type").getAsString().equals("bot.died")) {
                        check(event.has("cause") && event.has("position"), "death carries cause and position"); died = true;
                    }
                    if (event.get("type").getAsString().equals("bot.respawned")) {
                        check(died, "death precedes respawn"); break;
                    }
                }
            } catch (Exception exception) { throw new RuntimeException(exception); }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(result.isDone(), "waiting for respawn event"); result.join();
            helper.assertTrue(BotManager.get("BnReviveEvent") != bot && BotManager.get("BnReviveEvent").isAlive(), "new body tracked");
            BotManager.despawn("BnReviveEvent");
        });
    }

    private static JsonObject read(BufferedReader reader) throws Exception {
        String line = reader.readLine();
        check(line != null, "socket closed unexpectedly");
        return JsonParser.parseString(line).getAsJsonObject();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}

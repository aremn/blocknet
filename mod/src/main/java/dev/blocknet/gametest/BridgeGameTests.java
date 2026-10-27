package dev.blocknet.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.blocknet.Blocknet;
import dev.blocknet.body.BotManager;
import dev.blocknet.bridge.BridgeServer;
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
                check(hello.get("protocol").getAsInt() == 1 && hello.getAsJsonArray("tools").size() == 19, "hello lists all tools");
                check(hello.getAsJsonArray("tools").asList().stream()
                    .anyMatch(tool -> tool.getAsJsonObject().get("name").getAsString().equals("gather")), "hello lists gather");
                check(hello.getAsJsonArray("bots").toString().contains("BnBridge"), "hello lists bot");
                output.println("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tool.call\",\"params\":{\"bot\":\"BnBridge\",\"tool\":\"status\"}}");
                check(read(input).getAsJsonObject("result").get("result").getAsString().contains("health"), "status reports body");
                output.println("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tool.call\",\"params\":{\"bot\":\"BnBridge\",\"tool\":\"goto\",\"args\":{\"x\":"
                    + goal.getX() + ",\"y\":" + goal.getY() + ",\"z\":" + goal.getZ() + "}}}");
                var accepted = read(input).getAsJsonObject("result");
                String id = accepted.get("task_id").getAsString();
                var message = read(input);
                var event = message.getAsJsonObject("params");
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

    private static JsonObject read(BufferedReader reader) throws Exception {
        String line = reader.readLine();
        check(line != null, "socket closed unexpectedly");
        return JsonParser.parseString(line).getAsJsonObject();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}

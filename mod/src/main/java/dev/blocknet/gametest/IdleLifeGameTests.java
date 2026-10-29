package dev.blocknet.gametest;

import dev.blocknet.Blocknet;
import dev.blocknet.body.BotManager;
import dev.blocknet.body.BotPlayer;
import dev.blocknet.control.IdleLife;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** A bot with no order is not a statue: it faces whoever talks, fidgets while talked to, keeps up. */
@GameTestHolder(Blocknet.MOD_ID)
@PrefixGameTestTemplate(false)
public final class IdleLifeGameTests {
    private static final Vec3 BOT = new Vec3(24.5, 1, 24.5);

    private IdleLifeGameTests() {}

    private static BotPlayer bot(GameTestHelper h, String name) {
        for (int x = 0; x < 48; x++) for (int z = 0; z < 48; z++) {
            h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            for (int y = 1; y <= 3; y++) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        BotPlayer bot = TestArena.spawnBot(h, name, BOT);
        bot.getInventory().clearContent();
        bot.getInventory().setItem(0, new ItemStack(Items.STONE_PICKAXE));
        bot.getInventory().setItem(1, new ItemStack(Items.BREAD, 3));
        bot.getInventory().setItem(2, new ItemStack(Items.DIRT, 16));
        bot.getInventory().setItem(3, new ItemStack(Items.TORCH, 8));
        bot.getInventory().selected = 0;
        // Facing south, away from the players north of it.
        bot.setYRot(0); bot.setYHeadRot(0); bot.setXRot(0);
        return bot;
    }

    private static double angleTo(BotPlayer bot, ServerPlayer player) {
        Vec3 wanted = player.getEyePosition().subtract(bot.getEyePosition()).normalize();
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, wanted.dot(bot.getViewVector(1.0F))))));
    }

    private static double moved(GameTestHelper h, BotPlayer bot) {
        return bot.position().subtract(h.absoluteVec(BOT)).horizontalDistance();
    }

    @GameTest(template = "vision_field", skyAccess = true, timeoutTicks = 200)
    public static void turnsToWhoeverSpeaks(GameTestHelper h) {
        BotPlayer bot = bot(h, "BnIdleTurn");
        ServerPlayer silent = TestArena.human(h, "IdleSilent", new Vec3(28.5, 1, 24.5));
        ServerPlayer speaker = TestArena.human(h, "IdleSpeaker", new Vec3(24.5, 1, 16.5));
        h.runAfterDelay(20, () -> IdleLife.heard(speaker));
        h.runAtTickTime(19, () -> h.assertTrue(angleTo(bot, silent) < 20, "before anyone speaks it watches the nearest player"));
        h.runAtTickTime(21, () -> h.assertTrue(angleTo(bot, speaker) > 45, "a head turns no faster than a player's"));
        h.succeedWhen(() -> {
            h.assertTrue(h.getTick() > 21, "after the speaker spoke");
            h.assertTrue(angleTo(bot, speaker) < 10, "faces the speaker, not the nearer silent player: " + angleTo(bot, speaker));
            h.assertTrue(moved(h, bot) < 1, "turned in place");
            BotManager.despawn("BnIdleTurn");
            TestArena.removeHuman(silent);
            TestArena.removeHuman(speaker);
        });
    }

    @GameTest(template = "vision_field", skyAccess = true, timeoutTicks = 800)
    public static void fidgetsWhileTalkedToThenSettles(GameTestHelper h) {
        BotPlayer bot = bot(h, "BnIdleFidget");
        ServerPlayer player = TestArena.human(h, "IdleTalker", new Vec3(24.5, 1, 19.5));
        boolean[] swapped = {false}, crouched = {false};
        double[] farthest = {0};
        h.runAfterDelay(10, () -> IdleLife.heard(player));
        h.onEachTick(() -> {
            if (h.getTick() < 10 || h.getTick() > 10 + 500) return;
            swapped[0] |= bot.getInventory().selected != 0;
            crouched[0] |= bot.isShiftKeyDown();
            farthest[0] = Math.max(farthest[0], moved(h, bot));
        });
        h.succeedWhen(() -> {
            h.assertTrue(h.getTick() > 10 + 500 + 60, "the talk is over");
            h.assertTrue(swapped[0], "turned something else over in its hand while talked to");
            h.assertTrue(crouched[0], "crouched while talked to");
            h.assertTrue(farthest[0] < 2, "only shifted its feet: " + farthest[0]);
            h.assertTrue(bot.getInventory().selected == 0 && !bot.isShiftKeyDown(), "back to the pickaxe, standing");
            BotManager.despawn("BnIdleFidget");
            TestArena.removeHuman(player);
        });
    }

    @GameTest(template = "vision_field", skyAccess = true, timeoutTicks = 600)
    public static void catchesUpWithItsPlayer(GameTestHelper h) {
        BotPlayer bot = bot(h, "BnIdleCatch");
        ServerPlayer player = TestArena.human(h, "IdleWalker", new Vec3(24.5, 1, 6.5));
        h.runAfterDelay(10, () -> IdleLife.heard(player));
        h.succeedWhen(() -> {
            h.assertTrue(bot.distanceTo(player) <= 5.5, "walked back to its player: " + bot.distanceTo(player));
            h.assertTrue(bot.controller().describe().startsWith("idle"), "no task was made of it: " + bot.controller().describe());
            BotManager.despawn("BnIdleCatch");
            TestArena.removeHuman(player);
        });
    }

    @GameTest(template = "vision_field", skyAccess = true, timeoutTicks = 400)
    public static void stopMeansStayHere(GameTestHelper h) {
        BotPlayer bot = bot(h, "BnIdleStay");
        ServerPlayer player = TestArena.human(h, "IdleLeaver", new Vec3(24.5, 1, 11.5));
        h.runAfterDelay(5, () -> bot.controller().stopAndStay());
        h.runAfterDelay(10, () -> IdleLife.heard(player));
        h.runAtTickTime(300, () -> {
            h.assertTrue(moved(h, bot) < 1, "stayed where it was told: moved " + moved(h, bot));
            h.assertTrue(angleTo(bot, player) < 15, "still watching its player");
            BotManager.despawn("BnIdleStay");
            TestArena.removeHuman(player);
            h.succeed();
        });
    }

    @GameTest(template = "vision_field", skyAccess = true, timeoutTicks = 600)
    public static void anOrderOwnsTheBody(GameTestHelper h) {
        BotPlayer bot = bot(h, "BnIdleOrder");
        ServerPlayer player = TestArena.human(h, "IdleBoss", new Vec3(24.5, 1, 19.5));
        List<String> reports = new ArrayList<>();
        boolean[] fidgeted = {false};
        for (long tick = 10; tick < 400; tick += 40) h.runAtTickTime(tick, () -> IdleLife.heard(player));
        h.runAtTickTime(30, () -> bot.controller().goTo(h.absolutePos(new BlockPos(36, 1, 30)), reports::add));
        h.onEachTick(() -> {
            if (h.getTick() > 31 && reports.isEmpty()) fidgeted[0] |= bot.getInventory().selected != 0 || bot.isShiftKeyDown();
        });
        h.succeedWhen(() -> {
            h.assertTrue(reports.size() == 1 && reports.getFirst().contains("arrived"), "the order ran: " + reports);
            h.assertTrue(!fidgeted[0], "no fidgeting during the order");
            BotManager.despawn("BnIdleOrder");
            TestArena.removeHuman(player);
        });
    }
}

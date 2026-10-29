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
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * A bot with no order behaves like a companion: it faces whoever talks and nods, looks where its
 * player looks, walks alongside, and does small jobs near a player who stays busy in one place.
 */
@GameTestHolder(Blocknet.MOD_ID)
@PrefixGameTestTemplate(false)
public final class IdleLifeGameTests {
    private static final Vec3 BOT = new Vec3(24.5, 1, 24.5);
    /** Attention after a word lasts 25 s; chores wait for it to end and for 12 s of idling. */
    private static final int CHORES_FROM = 20 * 25 + 40;

    private IdleLifeGameTests() {}

    private static BotPlayer bot(GameTestHelper h, String name) {
        for (int x = 0; x < 48; x++) for (int z = 0; z < 48; z++) {
            h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            for (int y = 1; y <= 7; y++) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        BotPlayer bot = TestArena.spawnBot(h, name, BOT);
        bot.fullPerception(true);
        bot.getInventory().clearContent();
        bot.getInventory().setItem(0, new ItemStack(Items.STONE_PICKAXE));
        bot.getInventory().setItem(1, new ItemStack(Items.BREAD, 3));
        bot.getInventory().setItem(2, new ItemStack(Items.DIRT, 16));
        bot.getInventory().selected = 0;
        // Facing south, away from the players north of it.
        bot.setYRot(0); bot.setYHeadRot(0); bot.setXRot(0);
        return bot;
    }

    private static void tree(GameTestHelper h, int x, int z) {
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int y = 4; y <= 6; y++) {
            if (Math.abs(dx) + Math.abs(dz) > 3) continue;
            h.setBlock(new BlockPos(x + dx, y, z + dz), Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
        }
        for (int y = 1; y <= 5; y++) h.setBlock(new BlockPos(x, y, z), Blocks.OAK_LOG);
    }

    private static double angleTo(BotPlayer bot, Vec3 point) {
        Vec3 wanted = point.subtract(bot.getEyePosition()).normalize();
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, wanted.dot(bot.getViewVector(1.0F))))));
    }

    private static double moved(GameTestHelper h, BotPlayer bot) {
        return bot.position().subtract(h.absoluteVec(BOT)).horizontalDistance();
    }

    private static int logs(BotPlayer bot) {
        return bot.getInventory().items.stream().filter(s -> s.is(ItemTags.LOGS)).mapToInt(ItemStack::getCount).sum();
    }

    private static boolean said(BotPlayer bot, String kind) {
        return bot.moments().recent().stream().anyMatch(m -> m.get("kind").getAsString().equals(kind));
    }

    private static void done(String bot, ServerPlayer... humans) {
        BotManager.despawn(bot);
        for (ServerPlayer human : humans) TestArena.removeHuman(human);
    }

    @GameTest(template = "vision_field", skyAccess = true, timeoutTicks = 200)
    public static void turnsToWhoeverSpeaks(GameTestHelper h) {
        BotPlayer bot = bot(h, "BnIdleTurn");
        ServerPlayer silent = TestArena.human(h, "IdleSilent", new Vec3(28.5, 1, 24.5));
        ServerPlayer speaker = TestArena.human(h, "IdleSpeaker", new Vec3(24.5, 1, 16.5));
        h.runAfterDelay(20, () -> IdleLife.heard(speaker));
        h.runAtTickTime(19, () -> h.assertTrue(angleTo(bot, silent.getEyePosition()) < 20, "before anyone speaks it watches the nearest player"));
        h.runAtTickTime(21, () -> h.assertTrue(angleTo(bot, speaker.getEyePosition()) > 45, "a head turns no faster than a player's"));
        h.succeedWhen(() -> {
            h.assertTrue(h.getTick() > 40, "after the speaker spoke and the nod");
            h.assertTrue(angleTo(bot, speaker.getEyePosition()) < 10, "faces the speaker, not the nearer silent player");
            h.assertTrue(moved(h, bot) < 1, "turned in place");
            done("BnIdleTurn", silent, speaker);
        });
    }

    /** Listening is calm: one nod when addressed, nothing swapped in the hand, no shuffling. */
    @GameTest(template = "vision_field", skyAccess = true, timeoutTicks = 400)
    public static void nodsWhenAddressedAndStaysCalm(GameTestHelper h) {
        BotPlayer bot = bot(h, "BnIdleNod");
        ServerPlayer player = TestArena.human(h, "IdleTalker", new Vec3(24.5, 1, 19.5));
        float[] pitch = {Float.MAX_VALUE, -Float.MAX_VALUE};
        boolean[] restless = {false};
        h.runAtTickTime(60, () -> IdleLife.heard(player));
        h.onEachTick(() -> {
            if (h.getTick() < 50 || h.getTick() > 300) return;
            pitch[0] = Math.min(pitch[0], bot.getXRot());
            pitch[1] = Math.max(pitch[1], bot.getXRot());
            restless[0] |= bot.getInventory().selected != 0 || bot.isShiftKeyDown() || moved(h, bot) > 0.3;
        });
        h.runAtTickTime(320, () -> {
            h.assertTrue(pitch[1] - pitch[0] >= 8, "nodded: pitch ranged " + pitch[0] + ".." + pitch[1]);
            h.assertTrue(!restless[0], "no hotbar swaps, crouches or steps while listening");
            h.assertTrue(angleTo(bot, player.getEyePosition()) < 10, "eyes on the speaker afterwards");
            done("BnIdleNod", player);
            h.succeed();
        });
    }

    @GameTest(template = "vision_field", skyAccess = true, timeoutTicks = 200)
    public static void looksWhereItsPlayerLooks(GameTestHelper h) {
        BotPlayer bot = bot(h, "BnIdleGaze");
        BlockPos gold = new BlockPos(30, 1, 18);
        h.setBlock(gold, Blocks.GOLD_BLOCK);
        ServerPlayer player = TestArena.human(h, "IdleGazer", new Vec3(24.5, 1, 20.5));
        Vec3 eye = player.getEyePosition(), target = Vec3.atCenterOf(h.absolutePos(gold));
        Vec3 d = target.subtract(eye);
        float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90.0F;
        float pitch = (float) -(Mth.atan2(d.y, d.horizontalDistance()) * Mth.RAD_TO_DEG);
        player.teleportTo(h.getLevel(), player.getX(), player.getY(), player.getZ(), yaw, pitch);
        h.succeedWhen(() -> {
            h.assertTrue(angleTo(bot, target) < 12, "looks at the gold block its player is looking at: " + angleTo(bot, target));
            done("BnIdleGaze", player);
        });
    }

    @GameTest(template = "vision_field", skyAccess = true, timeoutTicks = 600)
    public static void walksAlongsideAWalkingPlayer(GameTestHelper h) {
        BotPlayer bot = bot(h, "BnIdleWalk");
        ServerPlayer player = TestArena.human(h, "IdleStroller", new Vec3(24.5, 1, 21.5));
        double[] worst = {0};
        h.runAfterDelay(5, () -> IdleLife.heard(player));
        // A walk west at about player speed (0.2 blocks a tick), then a stop.
        for (int step = 0; step <= 100; step++) {
            double x = 24.5 - step * 0.2;
            h.runAtTickTime(40 + step, () -> TestArena.moveHuman(h, player, new Vec3(x, 1, 21.5)));
        }
        h.onEachTick(() -> { if (h.getTick() > 80 && h.getTick() <= 140) worst[0] = Math.max(worst[0], bot.distanceTo(player)); });
        h.succeedWhen(() -> {
            h.assertTrue(h.getTick() > 160, "the walk is over");
            h.assertTrue(worst[0] <= 12, "kept up while walking: fell " + worst[0] + " behind");
            h.assertTrue(bot.distanceTo(player) <= 5, "stands beside its player: " + bot.distanceTo(player));
            h.assertTrue(bot.controller().describe().startsWith("idle"), "no task was made of it: " + bot.controller().describe());
            done("BnIdleWalk", player);
        });
    }

    @GameTest(template = "vision_field", skyAccess = true, timeoutTicks = 600)
    public static void catchesUpWithItsPlayer(GameTestHelper h) {
        BotPlayer bot = bot(h, "BnIdleCatch");
        ServerPlayer player = TestArena.human(h, "IdleWalker", new Vec3(24.5, 1, 6.5));
        h.runAfterDelay(10, () -> IdleLife.heard(player));
        h.succeedWhen(() -> {
            h.assertTrue(bot.distanceTo(player) <= 5.5, "walked back to its player: " + bot.distanceTo(player));
            done("BnIdleCatch", player);
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
            h.assertTrue(angleTo(bot, player.getEyePosition()) < 15, "still watching its player");
            done("BnIdleStay", player);
            h.succeed();
        });
    }

    @GameTest(template = "vision_field", skyAccess = true, timeoutTicks = 600)
    public static void anOrderOwnsTheBody(GameTestHelper h) {
        BotPlayer bot = bot(h, "BnIdleOrder");
        ServerPlayer player = TestArena.human(h, "IdleBoss", new Vec3(24.5, 1, 19.5));
        List<String> reports = new ArrayList<>();
        for (long tick = 10; tick < 400; tick += 40) h.runAtTickTime(tick, () -> IdleLife.heard(player));
        h.runAtTickTime(30, () -> bot.controller().goTo(h.absolutePos(new BlockPos(36, 1, 30)), reports::add));
        h.succeedWhen(() -> {
            h.assertTrue(reports.size() == 1 && reports.getFirst().contains("arrived"), "the order ran: " + reports);
            done("BnIdleOrder", player);
        });
    }

    /** Its player stands busy nearby, the bag has no wood, a tree is in sight: it chops the tree and says so. */
    @GameTest(template = "vision_field", skyAccess = true, timeoutTicks = 2400)
    public static void chopsATreeWhileItsPlayerIsBusy(GameTestHelper h) {
        BotPlayer bot = bot(h, "BnIdleChop");
        tree(h, 31, 24);
        ServerPlayer player = TestArena.human(h, "IdleBuilder", new Vec3(26.5, 1, 20.5));
        h.runAfterDelay(5, () -> IdleLife.heard(player));
        h.succeedWhen(() -> {
            h.assertTrue(logs(bot) >= 3, "chopped the tree: " + logs(bot) + " logs");
            h.assertTrue(said(bot, "chore"), "said what it was doing");
            h.assertTrue(h.getTick() >= CHORES_FROM, "not while it was being talked to");
            done("BnIdleChop", player);
        });
    }

    @GameTest(template = "vision_field", skyAccess = true, timeoutTicks = 1200)
    public static void craftsTorchesFromCoalWhenIdle(GameTestHelper h) {
        BotPlayer bot = bot(h, "BnIdleTorch");
        bot.getInventory().setItem(3, new ItemStack(Items.COAL, 2));
        bot.getInventory().setItem(4, new ItemStack(Items.STICK, 2));
        ServerPlayer player = TestArena.human(h, "IdleCrafter", new Vec3(26.5, 1, 20.5));
        h.runAfterDelay(5, () -> IdleLife.heard(player));
        h.succeedWhen(() -> {
            h.assertTrue(bot.getInventory().countItem(Items.TORCH) >= 8, "crafted torches: " + bot.getInventory().countItem(Items.TORCH));
            done("BnIdleTorch", player);
        });
    }

    /** A person drops a job to go with a friend who walks away. */
    @GameTest(template = "vision_field", skyAccess = true, timeoutTicks = 2400)
    public static void dropsAChoreWhenItsPlayerLeaves(GameTestHelper h) {
        BotPlayer bot = bot(h, "BnIdleDrop");
        tree(h, 31, 24);
        ServerPlayer player = TestArena.human(h, "IdleRover", new Vec3(26.5, 1, 20.5));
        boolean[] left = {false};
        h.runAfterDelay(5, () -> IdleLife.heard(player));
        h.onEachTick(() -> {
            if (!left[0] && said(bot, "chore")) {
                left[0] = true;
                TestArena.moveHuman(h, player, new Vec3(8.5, 1, 6.5));
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(left[0], "a chore started");
            h.assertTrue(bot.distanceTo(player) <= 5.5, "went after its player: " + bot.distanceTo(player));
            h.assertTrue(h.getBlockState(new BlockPos(31, 5, 24)).is(Blocks.OAK_LOG) || logs(bot) < 5, "left the tree unfinished");
            done("BnIdleDrop", player);
        });
    }

    /** No human around (other bots are not companions): nothing happens by itself. */
    @GameTest(template = "vision_field", skyAccess = true, timeoutTicks = 1000)
    public static void noChoresWithoutAPlayer(GameTestHelper h) {
        BotPlayer bot = bot(h, "BnIdleAlone");
        bot.getInventory().setItem(3, new ItemStack(Items.COAL, 2));
        bot.getInventory().setItem(4, new ItemStack(Items.STICK, 2));
        tree(h, 31, 24);
        h.runAtTickTime(900, () -> {
            h.assertTrue(bot.getInventory().countItem(Items.TORCH) == 0 && logs(bot) == 0, "did nothing on its own");
            h.assertTrue(moved(h, bot) < 1, "stayed put");
            done("BnIdleAlone");
            h.succeed();
        });
    }
}

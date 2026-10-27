package dev.blocknet.gametest;

import dev.blocknet.Blocknet;
import dev.blocknet.body.BotManager;
import dev.blocknet.body.BotPlayer;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Jumping across gaps instead of walking around them. */
@GameTestHolder(Blocknet.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ParkourGameTests {
    private ParkourGameTests() {}

    /**
     * A "П" of logs two blocks high: legs along z, the crossbar at the far end. From the near end of
     * the left leg to the near end of the right leg a player just jumps the one-block gap; walking
     * around over the crossbar is three times longer.
     */
    @GameTest(template = "arena", timeoutTicks = 300)
    public static void jumpsAcrossTheGapOfAU(GameTestHelper helper) {
        TestArena.floor(helper);
        int legEnd = 7;
        for (int z = 1; z <= legEnd; z++) {
            pillar(helper, 2, z);
            pillar(helper, 4, z);
        }
        pillar(helper, 3, legEnd);
        BotPlayer bot = TestArena.spawnBot(helper, "BnJumper", new Vec3(2.5, 3, 1.5));
        BlockPos goal = helper.absolutePos(new BlockPos(4, 3, 1));
        int startZ = bot.blockPosition().getZ();
        AtomicInteger furthest = new AtomicInteger();
        helper.onEachTick(() -> furthest.set(Math.max(furthest.get(), bot.blockPosition().getZ() - startZ)));
        bot.controller().goTo(goal, message -> {});
        helper.succeedWhen(() -> {
            helper.assertTrue(bot.blockPosition().equals(goal), "bot reached the right leg, is at " + bot.blockPosition().toShortString());
            helper.assertTrue(furthest.get() <= 2, "bot walked around over the crossbar (went " + furthest.get() + " blocks along the legs)");
            BotManager.despawn("BnJumper");
        });
    }

    /** Gaps of 2 and 3 blocks between two platforms 2 high: only a (sprint) jump gets across. */
    @GameTest(template = "arena_tall", timeoutTicks = 300)
    public static void jumpsTwoBlockGap(GameTestHelper helper) {
        jumpAcross(helper, "BnGap2", 2, 0);
    }

    @GameTest(template = "arena_tall", timeoutTicks = 300)
    public static void sprintJumpsThreeBlockGap(GameTestHelper helper) {
        jumpAcross(helper, "BnGap3", 3, 0);
    }

    /** Across a one-block gap onto a platform one block higher. */
    @GameTest(template = "arena_tall", timeoutTicks = 300)
    public static void jumpsUpAcrossGap(GameTestHelper helper) {
        jumpAcross(helper, "BnGapUp", 1, 1);
    }

    /**
     * Uses the tall arena: GameTest puts a barrier ceiling right above a template, and the
     * pathfinder only plans a running jump with three free blocks above its middle.
     *
     * <p>A runway platform (x 0..2) and a landing platform after {@code gap} empty columns, both two
     * blocks high, the landing {@code rise} blocks higher. The floor between them is two blocks down
     * with no way back up, so falling in means failing.
     */
    private static void jumpAcross(GameTestHelper helper, String name, int gap, int rise) {
        TestArena.floor(helper);
        int landingX = 3 + gap;
        for (int z = 3; z <= 5; z++) {
            for (int x = 0; x <= 2; x++) {
                pillar(helper, x, z);
            }
            for (int x = landingX; x <= 8; x++) {
                pillar(helper, x, z);
                if (rise > 0) {
                    helper.setBlock(new BlockPos(x, 3, z), Blocks.OAK_LOG);
                }
            }
        }
        BotPlayer bot = TestArena.spawnBot(helper, name, new Vec3(0.5, 3, 4.5));
        BlockPos goal = helper.absolutePos(new BlockPos(landingX + 1, 3 + rise, 4));
        int platformY = bot.blockPosition().getY();
        AtomicInteger lowest = new AtomicInteger(platformY);
        helper.onEachTick(() -> lowest.set(Math.min(lowest.get(), bot.blockPosition().getY())));
        bot.controller().goTo(goal, message -> {});
        helper.succeedWhen(() -> {
            helper.assertTrue(bot.blockPosition().equals(goal), name + " jumped across, is at " + bot.blockPosition().toShortString());
            helper.assertTrue(lowest.get() >= platformY, name + " fell into the gap (got " + (platformY - lowest.get()) + " blocks lower)");
            for (int x = 0; x <= 8; x++) {
                if (x <= 2 || x >= landingX) {
                    helper.assertBlockPresent(Blocks.OAK_LOG, new BlockPos(x, 2, 4));
                }
            }
            BotManager.despawn(name);
        });
    }

    /** Two logs stacked at y = 1..2. */
    private static void pillar(GameTestHelper helper, int x, int z) {
        helper.setBlock(new BlockPos(x, 1, z), Blocks.OAK_LOG);
        helper.setBlock(new BlockPos(x, 2, z), Blocks.OAK_LOG);
    }
}

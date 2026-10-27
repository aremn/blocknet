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

    /** Two logs stacked at y = 1..2. */
    private static void pillar(GameTestHelper helper, int x, int z) {
        helper.setBlock(new BlockPos(x, 1, z), Blocks.OAK_LOG);
        helper.setBlock(new BlockPos(x, 2, z), Blocks.OAK_LOG);
    }
}

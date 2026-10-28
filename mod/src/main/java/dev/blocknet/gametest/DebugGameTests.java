// SPDX-License-Identifier: MIT
package dev.blocknet.gametest;

import dev.blocknet.Blocknet;
import dev.blocknet.body.BotManager;
import dev.blocknet.body.BotPlayer;
import dev.blocknet.debug.DebugPayload;
import dev.blocknet.debug.DebugWatch;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The reasoning trace and the debug overlay frames, on real navigation. */
@GameTestHolder(Blocknet.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DebugGameTests {
    private DebugGameTests() {}

    /**
     * A block four blocks away can be in reach and still hidden behind a wall. The bot used to drop
     * its route there and push straight into the wall until it gave up ("view blocked"). It must
     * walk around, say why in its trace, and mine the block. Every tick also builds an overlay frame
     * and round-trips it through the network codec.
     */
    @GameTest(template = "arena", timeoutTicks = 400)
    public static void walksAroundWallToHiddenTargetInReach(GameTestHelper helper) {
        TestArena.floor(helper);
        // Obsidian is not worth digging by hand, so the only way is around the end at z = 7..8.
        for (int z = 0; z <= 6; z++) {
            for (int y = 1; y <= 4; y++) helper.setBlock(new BlockPos(4, y, z), Blocks.OBSIDIAN);
        }
        BlockPos dirt = new BlockPos(5, 1, 3);
        helper.setBlock(dirt, Blocks.DIRT);
        BotPlayer bot = TestArena.spawnBot(helper, "BnHiddenAim", new Vec3(3.5, 1, 3.5));
        List<String> reports = new ArrayList<>();
        bot.controller().mine(helper.absolutePos(dirt), reports::add);
        boolean[] sawRoute = {false};
        helper.succeedWhen(() -> {
            DebugPayload frame = DebugWatch.frame(bot);
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            DebugPayload.STREAM_CODEC.encode(buffer, frame);
            helper.assertTrue(DebugPayload.STREAM_CODEC.decode(buffer).equals(frame), "debug frame survives the codec");
            sawRoute[0] |= !frame.path().isEmpty() && !frame.goals().isEmpty();
            helper.assertFalse(reports.stream().anyMatch(r -> r.startsWith("failed")), "no failure: " + reports);
            helper.assertBlockPresent(Blocks.AIR, dirt);
            helper.assertTrue(!reports.isEmpty(), "mining reported once");
            helper.assertTrue(bot.getInventory().countItem(Items.DIRT) >= 1, "bot picked up the dirt");
            helper.assertTrue(sawRoute[0], "an overlay frame showed the route and its goal");
            helper.assertTrue(bot.trace().last(400).stream().anyMatch(e -> e.source().equals("aim")
                && e.text().contains("hidden behind obsidian")), "the trace says why it walked around: " + bot.trace().last(12));
            helper.assertTrue(bot.trace().last(400).stream().anyMatch(e -> e.source().equals("path")), "pathfinder lines reach the trace");
            BotManager.despawn("BnHiddenAim");
        });
    }
}

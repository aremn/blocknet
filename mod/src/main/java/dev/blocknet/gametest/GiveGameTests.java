// SPDX-License-Identifier: MIT
package dev.blocknet.gametest;

import dev.blocknet.Blocknet;
import dev.blocknet.body.BotManager;
import dev.blocknet.body.BotPlayer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(Blocknet.MOD_ID)
@PrefixGameTestTemplate(false)
public final class GiveGameTests {
    private GiveGameTests() {}

    private static void field(GameTestHelper h) {
        for (int x = 0; x < 48; x++) for (int z = 0; z < 48; z++) {
            h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            h.setBlock(new BlockPos(x, 1, z), Blocks.AIR);
        }
        for (int z = 19; z <= 25; z++) for (int y = 1; y <= 3; y++) h.setBlock(new BlockPos(15, y, z), Blocks.STONE);
    }
    private static int coal(BotPlayer b) { return b.getInventory().countItem(Items.COAL); }

    private static void give(GameTestHelper h, String suffix, boolean all, boolean airborne) {
        field(h);
        BotPlayer giver = TestArena.spawnBot(h, "BnGive" + suffix, new Vec3(7.5, 1, 22.5));
        BotPlayer receiver = TestArena.spawnBot(h, "BnRecv" + suffix, new Vec3(22.5, 1, 22.5));
        giver.getInventory().clearContent(); receiver.getInventory().clearContent();
        giver.getInventory().setItem(12, new ItemStack(Items.COAL, 64));
        giver.getInventory().setItem(14, new ItemStack(Items.COAL, 61));
        giver.getInventory().setItem(16, new ItemStack(Items.DIRT, 3));
        h.assertTrue(!giver.hasLineOfSight(receiver), "short wall hides the recipient at start");
        if (airborne) {
            h.setBlock(new BlockPos(22, 1, 22), Blocks.STONE);
            Vec3 at = h.absoluteVec(new Vec3(22.5, 5, 22.5));
            receiver.teleportTo(at.x, at.y, at.z);
        }
        List<String> reports = new ArrayList<>();
        h.runAtTickTime(250, () -> Blocknet.LOGGER.info("Give fixture {}: giver {}, receiver {}, items {}", suffix,
            giver.position(), receiver.position(), h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                giver.getBoundingBox().inflate(8)).stream().map(e -> e.getItem() + " at " + e.position()).toList()));
        // Exercise command parsing, the bridge's path, and the exact same task.
        if (all) {
            var args = new com.google.gson.JsonObject();
            args.addProperty("player", receiver.getScoreboardName()); args.addProperty("item", "all");
            dev.blocknet.bridge.BridgeTools.run(giver, dev.blocknet.bridge.BridgeTools.get("give"), args, new java.util.HashMap<>(), reports::add);
        } else giver.controller().give(receiver, "coal", 10, reports::add);
        h.succeedWhen(() -> {
            int count = all ? 125 : 10;
            h.assertTrue(h.getTick() >= 40, "real walking and vanilla pickup delay elapsed");
            h.assertTrue(coal(receiver) == count, "receiver has exactly " + count + " coal; got " + coal(receiver) + "; " + reports);
            h.assertTrue(coal(giver) == 125 - count, "giver kept the remainder");
            h.assertTrue(receiver.getInventory().countItem(Items.DIRT) == (all ? 3 : 0), "all includes other items");
            h.assertTrue(reports.size() == 1 && reports.getFirst().contains("gave " + (all ? 128 : 10)), "one successful report: " + reports);
            BotManager.despawn(giver.getScoreboardName()); BotManager.despawn(receiver.getScoreboardName());
        });
    }
    @GameTest(template = "vision_field", skyAccess = true, timeoutTicks = 1000)
    public static void givesTenCoalAroundWall(GameTestHelper h) { give(h, "Ten", false, false); }
    @GameTest(template = "vision_field", skyAccess = true, timeoutTicks = 1000)
    public static void givesAllOwnedItems(GameTestHelper h) { give(h, "All", true, false); }
    @GameTest(template = "vision_field", skyAccess = true, timeoutTicks = 1000)
    public static void givesToAirbornePlayer(GameTestHelper h) { give(h, "Air", false, true); }

    @GameTest(template = "vision_field", skyAccess = true, timeoutTicks = 500)
    public static void gotoProjectsHighFlyingPlayerToGround(GameTestHelper h) {
        field(h);
        BotPlayer bot = TestArena.spawnBot(h, "BnGotoFly", new Vec3(7.5, 1, 22.5));
        BotPlayer target = TestArena.spawnBot(h, "BnFlyTarget", new Vec3(22.5, 28, 22.5));
        bot.getInventory().clearContent(); target.getInventory().clearContent();
        target.setNoGravity(true);
        List<String> reports = new ArrayList<>();
        bot.controller().goTo(target.getScoreboardName(), reports::add);
        h.succeedWhen(() -> {
            h.assertTrue(reports.size() == 1 && reports.getFirst().startsWith("arrived"), "arrives under flying player: " + reports);
            h.assertTrue(bot.position().subtract(target.position()).horizontalDistance() <= 3, "within three horizontal blocks");
            BotManager.despawn(bot.getScoreboardName()); BotManager.despawn(target.getScoreboardName());
        });
    }
}

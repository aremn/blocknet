// SPDX-License-Identifier: MIT
package dev.blocknet.gametest;

import dev.blocknet.Blocknet;
import dev.blocknet.body.BotManager;
import dev.blocknet.body.BotPlayer;
import dev.blocknet.map.MapMemory;
import dev.blocknet.map.MapScanner;
import dev.blocknet.map.WorldMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** ADR-0011: the map, the radar, hunting and walking to places. */
@GameTestHolder(Blocknet.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MapGameTests {
    private MapGameTests() {}

    /** The vision_field floor and outer walls; the walls keep neighbouring fixtures out of sight. */
    private static void field(GameTestHelper helper) {
        for (int x = 0; x < 48; x++) for (int z = 0; z < 48; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            helper.setBlock(new BlockPos(x, 1, z), Blocks.AIR);
        }
        for (int x = 0; x < 48; x++) for (int y = 1; y < 22; y++) {
            helper.setBlock(new BlockPos(x, y, 0), Blocks.STONE);
            helper.setBlock(new BlockPos(x, y, 47), Blocks.STONE);
            helper.setBlock(new BlockPos(0, y, x), Blocks.STONE);
            helper.setBlock(new BlockPos(47, y, x), Blocks.STONE);
        }
    }

    private static Sheep sheep(GameTestHelper helper, BlockPos at) {
        Sheep sheep = helper.spawn(EntityType.SHEEP, at);
        // A still target keeps the chase deterministic; the wall still hides it from the bot.
        sheep.setNoAi(true);
        return sheep;
    }

    private static Set<Long> chunks(GameTestHelper helper, BlockPos from, BlockPos to) {
        Set<Long> keys = new HashSet<>();
        BlockPos a = helper.absolutePos(from), b = helper.absolutePos(to);
        for (int x = Math.min(a.getX(), b.getX()) >> 4; x <= Math.max(a.getX(), b.getX()) >> 4; x++)
            for (int z = Math.min(a.getZ(), b.getZ()) >> 4; z <= Math.max(a.getZ(), b.getZ()) >> 4; z++) keys.add(ChunkPos.asLong(x, z));
        return keys;
    }

    @GameTest(template = "vision_field", skyAccess = true, timeoutTicks = 900)
    public static void mapShowsCaveEntranceAndHiddenSheepThenGotoEntersCave(GameTestHelper helper) {
        field(helper);
        // A rock mass with a room inside and one low tunnel to the open field: a cave with a sky-lit mouth.
        for (int x = 14; x <= 45; x++) for (int z = 14; z <= 45; z++) for (int y = 1; y <= 16; y++)
            helper.setBlock(new BlockPos(x, y, z), x >= 16 && x <= 43 && z >= 16 && z <= 43 && y >= 2 && y <= 7 ? Blocks.AIR : Blocks.STONE);
        for (int x = 14; x <= 15; x++) for (int y = 2; y <= 3; y++) for (int z = 29; z <= 30; z++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        BotPlayer bot = TestArena.spawnBot(helper, "BnMapCave", new Vec3(6.5, 1, 29.5));
        Sheep sheep = sheep(helper, new BlockPos(30, 2, 30));
        Set<Long> mass = chunks(helper, new BlockPos(14, 1, 14), new BlockPos(45, 16, 45));
        BlockPos lo = helper.absolutePos(new BlockPos(14, 1, 14)), hi = helper.absolutePos(new BlockPos(45, 16, 45));
        List<String> reports = new ArrayList<>();
        String[] cave = new String[1];
        String[] map = new String[1];
        helper.onEachTick(() -> {
            if (cave[0] != null || helper.getTick() < 20) return;
            // Light settles a few ticks after the blocks; rescan this fixture's chunks until the mouth is lit.
            mass.forEach(chunk -> MapScanner.scan(helper.getLevel(), chunk));
            var found = MapMemory.of(helper.getLevel()).caves(bot.blockPosition()).stream()
                .filter(c -> c.entrance() != null && c.entrance().getX() >= lo.getX() && c.entrance().getX() <= hi.getX()
                    && c.entrance().getZ() >= lo.getZ() && c.entrance().getZ() <= hi.getZ()).findFirst();
            if (found.isEmpty()) return;
            cave[0] = found.get().ref();
            map[0] = WorldMap.text(bot, "");
            bot.controller().goTo(cave[0], reports::add);
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(cave[0] != null, "map found the cave");
            helper.assertTrue(map[0].contains(cave[0] + " cave entrance"), "map lists the cave: " + map[0]);
            var contact = bot.radar().find(sheep.getUUID().toString());
            helper.assertTrue(contact != null && map[0].contains(contact.ref() + " sheep"), "map lists the hidden sheep: " + map[0]);
            helper.assertTrue(map[0].lines().filter(line -> line.matches("[@PSC~!^v.?]( [@PSC~!^v.?]){8}")).count() == 9, "9x9 grid: " + map[0]);
            helper.assertTrue(reports.size() == 1 && reports.getFirst().contains("arrived"), "goto cave: " + reports);
            BlockPos feet = bot.blockPosition();
            helper.assertTrue(feet.getX() >= lo.getX() && feet.getZ() >= lo.getZ() && feet.getY() < hi.getY(), "bot is in the cave mouth: " + feet);
            BotManager.despawn("BnMapCave");
        });
    }

    @GameTest(template = "vision_field", skyAccess = true, timeoutTicks = 1600)
    public static void exploreCavesWalksToMappedEntranceOutOfSight(GameTestHelper helper) {
        // The cave walk inside stays sight-based (and this room is dark); this checks the map leg.
        field(helper);
        // The cave opens to the south, away from the bot: no ray can see its mouth from the start.
        for (int x = 14; x <= 45; x++) for (int z = 14; z <= 45; z++) for (int y = 1; y <= 16; y++)
            helper.setBlock(new BlockPos(x, y, z), x >= 16 && x <= 43 && z >= 16 && z <= 43 && y >= 2 && y <= 7 ? Blocks.AIR : Blocks.STONE);
        // Cover a full 4x3 scanner cell regardless of the randomly translated test origin.
        // A two-block mouth can fall entirely between sample columns (or their sample heights).
        for (int z = 42; z <= 45; z++) for (int y = 2; y <= 7; y++) for (int x = 28; x <= 35; x++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        BotPlayer bot = TestArena.spawnBot(helper, "BnMapExplore", new Vec3(6.5, 1, 6.5));
        // Bots keep their inventory between runs like players; start empty.
        bot.getInventory().clearContent();
        bot.getInventory().setItem(0, new ItemStack(Items.STONE_PICKAXE));
        Set<Long> mass = chunks(helper, new BlockPos(14, 1, 14), new BlockPos(45, 16, 45));
        List<String> reports = new ArrayList<>();
        boolean[] started = new boolean[1];
        String[] scan = {""};
        BlockPos lo = helper.absolutePos(new BlockPos(14, 1, 14)), hi = helper.absolutePos(new BlockPos(45, 16, 45));
        helper.onEachTick(() -> {
            if (started[0] || helper.getTick() < 20) return;
            mass.forEach(chunk -> MapScanner.scan(helper.getLevel(), chunk));
            if (helper.getTick() % 100 == 0) scan[0] = mass.stream()
                .map(chunk -> String.valueOf(MapMemory.of(helper.getLevel()).caveChunk(chunk))).toList().toString();
            // The shared test world has other fixtures' caves too; name this one, as the brain would from map.
            var cave = MapMemory.of(helper.getLevel()).caves(bot.blockPosition()).stream()
                .filter(c -> c.entrance() != null && c.entrance().getX() >= lo.getX() && c.entrance().getX() <= hi.getX()
                    && c.entrance().getZ() >= lo.getZ() && c.entrance().getZ() <= hi.getZ()).findFirst();
            if (cave.isEmpty()) return;
            started[0] = true;
            bot.controller().exploreCaves(Items.COAL, 1, cave.get().ref(), reports::add);
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(started[0], "map found the entrance: " + scan[0]);
            helper.assertTrue(reports.size() == 1, "explore_caves finished: " + reports);
            helper.assertTrue(!reports.getFirst().contains("no cave seen"), "walked to the mapped cave: " + reports);
            // It started outside the rock in the north-west corner; the only way in is the south mouth.
            BlockPos inside = helper.absolutePos(new BlockPos(16, 1, 16));
            helper.assertTrue(bot.getBlockX() >= inside.getX() && bot.getBlockZ() >= inside.getZ(), "went into the cave: " + bot.blockPosition());
            BotManager.despawn("BnMapExplore");
        });
    }

    @GameTest(template = "vision_field", skyAccess = true, timeoutTicks = 900)
    public static void attackChasesAroundWallAndKills(GameTestHelper helper) {
        field(helper);
        for (int z = 10; z <= 38; z++) for (int y = 1; y <= 4; y++) helper.setBlock(new BlockPos(16, y, z), Blocks.STONE);
        BotPlayer bot = TestArena.spawnBot(helper, "BnHunt", new Vec3(8.5, 1, 24.5));
        // Bots keep their inventory between runs like players; start empty.
        bot.getInventory().clearContent();
        bot.getInventory().setItem(0, new ItemStack(Items.IRON_SWORD));
        Sheep sheep = sheep(helper, new BlockPos(24, 1, 24));
        List<String> reports = new ArrayList<>();
        boolean[] hidden = new boolean[1];
        helper.runAfterDelay(10, () -> {
            hidden[0] = !bot.hasLineOfSight(sheep);
            bot.controller().attack(sheep.getUUID().toString(), reports::add);
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(hidden[0], "the sheep starts behind the wall");
            helper.assertTrue(reports.size() == 1, "one report: " + reports);
            helper.assertTrue(reports.getFirst().startsWith("killed ") && reports.getFirst().contains(" sheep; picked up "), "killed and looted: " + reports);
            helper.assertTrue(!sheep.isAlive(), "sheep is dead");
            helper.assertTrue(bot.getInventory().countItem(Items.WHITE_WOOL) >= 1, "picked up the wool: " + reports);
            // Walking round the wall and two sword swings with cooldown take real ticks.
            helper.assertTrue(helper.getTick() >= 40, "took survival time: " + helper.getTick());
            BotManager.despawn("BnHunt");
        });
    }

    @GameTest(template = "vision_field", skyAccess = true, timeoutTicks = 600)
    public static void pickupWalksToItemByRef(GameTestHelper helper) {
        field(helper);
        BotPlayer bot = TestArena.spawnBot(helper, "BnPickup", new Vec3(8.5, 1, 8.5));
        // Bots keep their inventory between runs like players; start empty.
        bot.getInventory().clearContent();
        var wool = new net.minecraft.world.entity.item.ItemEntity(helper.getLevel(), helper.absoluteVec(new Vec3(20.5, 1.2, 30.5)).x,
            helper.absoluteVec(new Vec3(20.5, 1.2, 30.5)).y, helper.absoluteVec(new Vec3(20.5, 1.2, 30.5)).z, new ItemStack(Items.WHITE_WOOL, 3));
        wool.setDeltaMovement(Vec3.ZERO);
        helper.getLevel().addFreshEntity(wool);
        // Another item the bot must leave alone.
        var stick = new net.minecraft.world.entity.item.ItemEntity(helper.getLevel(), helper.absoluteVec(new Vec3(30.5, 1.2, 10.5)).x,
            helper.absoluteVec(new Vec3(30.5, 1.2, 10.5)).y, helper.absoluteVec(new Vec3(30.5, 1.2, 10.5)).z, new ItemStack(Items.STICK));
        stick.setDeltaMovement(Vec3.ZERO);
        helper.getLevel().addFreshEntity(stick);
        List<String> reports = new ArrayList<>();
        String[] ref = new String[1];
        helper.runAfterDelay(10, () -> {
            var contact = bot.radar().find(wool.getUUID().toString());
            ref[0] = contact == null ? null : contact.ref();
            String map = WorldMap.text(bot, "items");
            helper.assertTrue(ref[0] != null && ref[0].startsWith("i") && map.contains(ref[0] + " white_wool x3"), "map lists the wool: " + map);
            bot.controller().pickup(ref[0], reports::add);
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(reports.size() == 1 && reports.getFirst().equals("picked up 3 white_wool"), "picked up: " + reports);
            helper.assertTrue(bot.getInventory().countItem(Items.WHITE_WOOL) == 3 && bot.getInventory().countItem(Items.STICK) == 0, "only the wool");
            stick.discard();
            BotManager.despawn("BnPickup");
        });
    }

    @GameTest(template = "vision_field", skyAccess = true, timeoutTicks = 600)
    public static void gotoPlayerByName(GameTestHelper helper) {
        field(helper);
        for (int z = 10; z <= 38; z++) for (int y = 1; y <= 4; y++) helper.setBlock(new BlockPos(16, y, z), Blocks.STONE);
        BotPlayer bot = TestArena.spawnBot(helper, "BnGotoName", new Vec3(8.5, 1, 24.5));
        BotPlayer friend = TestArena.spawnBot(helper, "BnGotoFriend", new Vec3(30.5, 1, 24.5));
        List<String> reports = new ArrayList<>();
        helper.runAfterDelay(5, () -> bot.controller().goTo("BnGotoFriend", reports::add));
        helper.succeedWhen(() -> {
            helper.assertTrue(reports.size() == 1 && reports.getFirst().startsWith("arrived"), "arrived: " + reports);
            helper.assertTrue(bot.distanceTo(friend) <= 3.5, "next to the player: " + bot.distanceTo(friend));
            BotManager.despawn("BnGotoName");
            BotManager.despawn("BnGotoFriend");
        });
    }

    @GameTest(template = "vision_field", skyAccess = true, timeoutTicks = 1600)
    public static void fullPerceptionObtainsBuriedDiamond(GameTestHelper helper) {
        field(helper);
        // A solid block of stone with one diamond ore inside: no ray can ever see it.
        for (int x = 20; x <= 30; x++) for (int z = 20; z <= 30; z++) for (int y = 1; y <= 8; y++)
            helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
        helper.setBlock(new BlockPos(25, 3, 25), Blocks.DIAMOND_ORE);
        BotPlayer bot = TestArena.spawnBot(helper, "BnFullDiamond", new Vec3(8.5, 1, 24.5));
        // Bots keep their inventory between runs like players; start empty.
        bot.getInventory().clearContent();
        bot.fullPerception(true);
        bot.getInventory().setItem(0, new ItemStack(Items.IRON_PICKAXE));
        List<String> reports = new ArrayList<>();
        helper.runAfterDelay(5, () -> bot.controller().obtain(Items.DIAMOND, 1, reports::add));
        helper.succeedWhen(() -> {
            helper.assertTrue(reports.size() == 1, "obtain finished: " + reports);
            helper.assertTrue(reports.getFirst().startsWith("obtained 1 diamond") && reports.getFirst().contains("from known ore"), "from the known ore: " + reports);
            helper.assertTrue(bot.getInventory().countItem(Items.DIAMOND) >= 1, "has a diamond");
            helper.assertTrue(helper.getBlockState(new BlockPos(25, 3, 25)).isAir(), "the ore block was broken");
            BotManager.despawn("BnFullDiamond");
        });
    }

    @GameTest(template = "vision_field", skyAccess = true, timeoutTicks = 1600)
    public static void worldObtainMinesInsteadOfTakingFromAChest(GameTestHelper helper) {
        field(helper);
        for (int x = 20; x <= 30; x++) for (int z = 20; z <= 30; z++) for (int y = 1; y <= 6; y++)
            helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
        for (int x = 24; x <= 26; x++) helper.setBlock(new BlockPos(x, 2, 25), Blocks.COAL_ORE);
        helper.setBlock(new BlockPos(10, 1, 10), Blocks.CHEST);
        var chest = (net.minecraft.world.level.block.entity.ChestBlockEntity) helper.getBlockEntity(new BlockPos(10, 1, 10));
        chest.setItem(0, new ItemStack(Items.COAL, 5));
        BotPlayer bot = TestArena.spawnBot(helper, "BnWorldCoal", new Vec3(8.5, 1, 12.5));
        bot.getInventory().clearContent();
        bot.fullPerception(true);
        bot.getInventory().setItem(0, new ItemStack(Items.STONE_PICKAXE));
        List<String> reports = new ArrayList<>(), located = new ArrayList<>();
        helper.runAfterDelay(5, () -> {
            located.add(dev.blocknet.map.WorldFind.find(bot, "coal_ore", 48));
            located.add(dev.blocknet.map.WorldFind.find(bot, "coal", 48));
            // The bot has seen and remembers the chest, which an "any" obtain would raid first.
            bot.containerMemory().observe(bot, helper.absolutePos(new BlockPos(10, 1, 10)));
            bot.controller().obtain(Items.COAL, 3, true, reports::add);
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(located.size() == 2, "locate ran");
            // Nearest first; other tests nearby may add their own ore further down the list.
            List<String> nearest = java.util.Arrays.stream(located.get(0).replace("coal_ore blocks: ", "").split("; ")).limit(3).toList();
            for (int x = 24; x <= 26; x++) {
                BlockPos ore = helper.absolutePos(new BlockPos(x, 2, 25));
                helper.assertTrue(nearest.stream().anyMatch(e -> e.startsWith(ore.getX() + " " + ore.getY() + " " + ore.getZ())), "locate finds the buried ore first: " + located);
            }
            helper.assertTrue(located.get(1).contains("coal stored in: chest with 5"), "locate reads the chest: " + located);
            helper.assertTrue(reports.size() == 1 && reports.getFirst().startsWith("obtained 3 coal") && reports.getFirst().contains("from known ore"), "mined: " + reports);
            helper.assertTrue(chest.countItem(Items.COAL) == 5, "the chest was not touched");
            helper.assertTrue(!bot.controller().worldOnly(), "world-only mode ends with the task");
            BotManager.despawn("BnWorldCoal");
        });
    }

    @GameTest(template = "vision_field", skyAccess = true, timeoutTicks = 600)
    public static void losingFightDigsInAndCapsTheHole(GameTestHelper helper) {
        field(helper);
        for (int x = 10; x <= 20; x++) for (int z = 10; z <= 20; z++) for (int y = 1; y <= 5; y++)
            helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
        BotPlayer bot = TestArena.spawnBot(helper, "BnShelter", new Vec3(15.5, 6, 15.5));
        bot.getInventory().clearContent();
        bot.getInventory().setItem(0, new ItemStack(Items.IRON_PICKAXE));
        bot.getInventory().setItem(1, new ItemStack(Items.DIRT, 16));
        bot.setHealth(4);
        var zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(18, 6, 15));
        zombie.setNoAi(true);
        helper.runAfterDelay(10, () -> bot.controller().attack(zombie.getUUID().toString(), ignored -> {}));
        helper.succeedWhen(() -> {
            helper.assertTrue(bot.isAlive(), "alive");
            helper.assertTrue(bot.getBlockY() <= helper.absolutePos(new BlockPos(0, 3, 0)).getY(), "dug three blocks down: " + bot.blockPosition());
            helper.assertTrue(!helper.getBlockState(new BlockPos(15, 5, 15)).isAir(), "the hole is capped");
            zombie.discard();
            BotManager.despawn("BnShelter");
        });
    }

    /**
     * Live: "make a bed" with sheep around, and bones in the bag for white dye. obtain chased a
     * re-dyed wool cycle and gave up; a player kills the sheep for their wool and crafts the bed.
     */
    @GameTest(template = "vision_field", skyAccess = true, timeoutTicks = 2400)
    public static void obtainBedHuntsSheepForWool(GameTestHelper helper) {
        field(helper);
        BotPlayer bot = TestArena.spawnBot(helper, "BnBedWool", new Vec3(8.5, 1, 8.5));
        bot.getInventory().clearContent();
        bot.getInventory().setItem(0, new ItemStack(Items.IRON_SWORD));
        bot.getInventory().setItem(1, new ItemStack(Items.OAK_PLANKS, 3));
        bot.getInventory().setItem(2, new ItemStack(Items.CRAFTING_TABLE));
        bot.getInventory().setItem(3, new ItemStack(Items.BONE, 3));
        List<Sheep> flock = new ArrayList<>();
        for (int i = 0; i < 3; i++) flock.add(sheep(helper, new BlockPos(16 + 4 * i, 1, 14)));
        List<String> reports = new ArrayList<>();
        helper.runAfterDelay(10, () -> bot.controller().obtain(Items.WHITE_BED, 1, true, reports::add));
        helper.succeedWhen(() -> {
            helper.assertTrue(reports.size() == 1, "one report: " + reports);
            helper.assertTrue(reports.getFirst().startsWith("obtained 1 white_bed") && reports.getFirst().contains("hunted sheep"),
                "hunted and crafted: " + reports);
            helper.assertTrue(bot.getInventory().countItem(Items.WHITE_BED) == 1, "bed in the inventory");
            helper.assertTrue(flock.stream().noneMatch(Sheep::isAlive), "all three sheep hunted");
            BotManager.despawn("BnBedWool");
        });
    }

    @GameTest(template = "vision_field", skyAccess = true, timeoutTicks = 300)
    public static void attackReportsLostWhenQuarryVanishes(GameTestHelper helper) {
        field(helper);
        for (int z = 10; z <= 38; z++) for (int y = 1; y <= 4; y++) helper.setBlock(new BlockPos(16, y, z), Blocks.STONE);
        BotPlayer bot = TestArena.spawnBot(helper, "BnHuntLost", new Vec3(8.5, 1, 24.5));
        Sheep sheep = sheep(helper, new BlockPos(30, 1, 24));
        List<String> reports = new ArrayList<>();
        helper.runAfterDelay(10, () -> bot.controller().attack(sheep.getUUID().toString(), reports::add));
        helper.runAfterDelay(20, sheep::discard);
        helper.succeedWhen(() -> {
            helper.assertTrue(reports.size() == 1 && reports.getFirst().startsWith("failed: lost ") && reports.getFirst().contains("sheep"),
                "lost, never killed: " + reports);
            BotManager.despawn("BnHuntLost");
        });
    }

    @GameTest(template = "vision_field", skyAccess = true, timeoutTicks = 900)
    public static void gotoStructureStandsOnGroundNotRoof(GameTestHelper helper) {
        field(helper);
        // A closed hut: planks walls, a roof at y=5. Its center column is the roof.
        for (int x = 20; x <= 26; x++) for (int z = 20; z <= 26; z++) for (int y = 1; y <= 5; y++) {
            boolean shell = y == 5 || x == 20 || x == 26 || z == 20 || z == 26;
            helper.setBlock(new BlockPos(x, y, z), shell ? Blocks.OAK_PLANKS : Blocks.AIR);
        }
        BotPlayer bot = TestArena.spawnBot(helper, "BnHutGoto", new Vec3(6.5, 1, 23.5));
        BlockPos a = helper.absolutePos(new BlockPos(20, 1, 20)), b = helper.absolutePos(new BlockPos(26, 5, 26));
        String ref = MapMemory.of(helper.getLevel()).structure("test_hut_" + a.asLong(), new ChunkPos(a),
            BoundingBox.fromCorners(a, b), 1, false);
        List<String> reports = new ArrayList<>();
        helper.runAfterDelay(5, () -> bot.controller().goTo(ref, reports::add));
        helper.succeedWhen(() -> {
            helper.assertTrue(reports.size() == 1 && reports.getFirst().startsWith("arrived"), "arrived: " + reports);
            helper.assertTrue(bot.getBlockY() == helper.absolutePos(new BlockPos(0, 1, 0)).getY(), "on the ground, not the roof: " + bot.blockPosition());
            helper.assertTrue(bot.blockPosition().closerThan(a.offset(3, 0, 3), 12), "next to the hut: " + bot.blockPosition());
            BotManager.despawn("BnHutGoto");
        });
    }
}

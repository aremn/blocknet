package dev.blocknet.gametest;

import dev.blocknet.Blocknet;
import dev.blocknet.body.BotManager;
import dev.blocknet.body.BotPlayer;
import dev.blocknet.memory.WorldMemory;
import dev.blocknet.vision.*;
import java.util.*;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(Blocknet.MOD_ID)
@PrefixGameTestTemplate(false)
public final class VisionGameTests {
    private VisionGameTests() {}

    private static BotPlayer field(GameTestHelper helper, String name) {
        // Templates start one block above helper coordinates. Keep the established feet-at-y=1 convention.
        for (int x = 0; x < 48; x++) for (int z = 0; z < 48; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            helper.setBlock(new BlockPos(x, 1, z), Blocks.AIR);
        }
        // Isolate parallel test scenes optically; a 64-block ray may otherwise see the next fixture.
        for (int x = 0; x < 48; x++) for (int y = 1; y < 22; y++) {
            helper.setBlock(new BlockPos(x, y, 0), Blocks.STONE);
            helper.setBlock(new BlockPos(x, y, 47), Blocks.STONE);
            helper.setBlock(new BlockPos(0, y, x), Blocks.STONE);
            helper.setBlock(new BlockPos(47, y, x), Blocks.STONE);
        }
        return TestArena.spawnBot(helper, name, new Vec3(24.5, 1, 24.5));
    }

    private static void tree(GameTestHelper helper, int x, int z, Block log, Block leaves) {
        for (int y = 1; y <= 5; y++) helper.setBlock(new BlockPos(x, y, z), log);
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int y = 4; y <= 6; y++) {
            if (dx == 0 && dz == 0 && y <= 5) continue;
            helper.setBlock(new BlockPos(x + dx, y, z + dz), leaves.defaultBlockState()
                .setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true));
        }
    }

    /**
     * Newly placed fixtures start unlit until the light engine catches up, which under a full,
     * parallel GameTest run can take a while; the honest darkness rule would then see "dark".
     */
    private static void whenLit(GameTestHelper helper, Runnable action) {
        boolean lit = true;
        for (BlockPos probe : List.of(new BlockPos(24, 2, 24), new BlockPos(24, 2, 8), new BlockPos(40, 2, 24),
                new BlockPos(8, 2, 36), new BlockPos(24, 7, 20))) {
            lit &= VoxelRay.visibleLight(helper.getLevel(), helper.absolutePos(probe)) >= 8;
        }
        if (lit) {
            action.run();
        } else {
            helper.runAfterDelay(1, () -> whenLit(helper, action));
        }
    }

    private static String dimension(BotPlayer bot) { return bot.level().dimension().location().toString(); }

    @GameTest(template = "vision_field", skyAccess = true, timeoutTicks = 400)
    public static void lookFindsThreeTreesWithSpeciesAndDirections(GameTestHelper helper) {
        BotPlayer bot = field(helper, "BnVisionTrees");
        tree(helper, 24, 12, Blocks.OAK_LOG, Blocks.OAK_LEAVES);
        tree(helper, 36, 24, Blocks.BIRCH_LOG, Blocks.BIRCH_LEAVES);
        tree(helper, 12, 36, Blocks.SPRUCE_LOG, Blocks.SPRUCE_LEAVES);
        List<String> reports = new ArrayList<>();
        whenLit(helper, () -> bot.controller().look(reports::add));
        helper.succeedWhen(() -> {
            helper.assertTrue(reports.size() == 1 && helper.getTick() >= 20, "panorama completed");
            var trees = bot.vision().objects().stream().filter(object -> object.category().equals("tree")).toList();
            helper.assertTrue(trees.size() == 3, "three trees: " + reports);
            Map<String, String> directions = trees.stream().collect(Collectors.toMap(ObjectGraph.ObjectView::kind,
                object -> Frontiers.direction(object.center().subtract(bot.position()))));
            helper.assertTrue(directions.equals(Map.of("oak", "N", "birch", "E", "spruce", "SW")), directions.toString());
            Blocknet.LOGGER.info("VISION_SAMPLE /blocknet see BnVisionTrees: {}", reports.getFirst());
            // Stable refs and compact SavedData round-trip use only the observed cells.
            WorldMemory restored = WorldMemory.load(bot.worldMemory().save(new net.minecraft.nbt.CompoundTag(),
                helper.getLevel().registryAccess()), helper.getLevel().registryAccess());
            helper.assertTrue(restored.observed().evidence(dimension(bot)).equals(bot.worldMemory().observed().evidence(dimension(bot))),
                "map persistence preserves all evidence");
            var refs = trees.stream().map(ObjectGraph.ObjectView::ref).collect(Collectors.toSet());
            helper.assertTrue(restored.observed().graph(dimension(bot)).objects(bot.getEyePosition(), 0).stream()
                .filter(object -> object.category().equals("tree")).map(ObjectGraph.ObjectView::ref).collect(Collectors.toSet()).equals(refs),
                "refs survive reload");
            BotManager.despawn("BnVisionTrees");
        });
    }

    @GameTest(template = "vision_field", skyAccess = true, timeoutTicks = 400)
    public static void wallHidesTreeAndItsCells(GameTestHelper helper) {
        BotPlayer bot = field(helper, "BnVisionHidden");
        for (int x = 1; x < 47; x++) for (int y = 1; y < 22; y++) helper.setBlock(new BlockPos(x, y, 16), Blocks.STONE);
        tree(helper, 24, 8, Blocks.OAK_LOG, Blocks.OAK_LEAVES);
        List<String> reports = new ArrayList<>();
        bot.controller().look(reports::add);
        helper.succeedWhen(() -> {
            helper.assertTrue(reports.size() == 1, "look finished");
            helper.assertTrue(bot.vision().objects().stream().noneMatch(object -> object.category().equals("tree")), reports.toString());
            for (BlockPos pos : BlockPos.betweenClosed(22, 1, 6, 26, 6, 10)) {
                helper.assertTrue(!bot.worldMemory().observed().known(dimension(bot), helper.absolutePos(pos)), "hidden tree cell stays unknown " + pos);
            }
            BotManager.despawn("BnVisionHidden");
        });
    }

    @GameTest(template = "vision_field", skyAccess = true, timeoutTicks = 400)
    public static void hiddenOreCannotChangeObservedMap(GameTestHelper helper) {
        BotPlayer bot = field(helper, "BnVisionLeak");
        for (int x = 1; x < 47; x++) for (int y = 1; y < 22; y++) for (int z = 12; z <= 16; z++)
            helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
        whenLit(helper, () -> compareHiddenOre(helper, bot));
    }

    private static void compareHiddenOre(GameTestHelper helper, BotPlayer bot) {
        compareHiddenOre(helper, bot, scan(helper, bot), 0);
    }

    /**
     * Scans until two scans without the ore agree, so the light engine has settled the shade on the
     * new wall; only then hides the ore inside the wall and requires the next scan to be identical.
     */
    private static void compareHiddenOre(GameTestHelper helper, BotPlayer bot, Object previous, int attempts) {
        helper.runAfterDelay(5, () -> {
            var current = scan(helper, bot);
            if (!current.equals(previous) && attempts < 40) {
                compareHiddenOre(helper, bot, current, attempts + 1);
                return;
            }
            helper.setBlock(new BlockPos(24, 3, 14), Blocks.COAL_ORE);
            var after = scan(helper, bot);
            helper.assertTrue(!after.toString().isEmpty() && current.equals(after),
                "identical visible surfaces give identical occupied AND free cells");
            helper.assertTrue(!bot.worldMemory().observed().known(dimension(bot), helper.absolutePos(new BlockPos(24, 3, 14))),
                "hidden ore unknown");
            BotManager.despawn("BnVisionLeak");
            helper.succeed();
        });
    }

    /** A fresh map from rays at every cell of the wall's middle plane; returns its evidence. */
    private static Object scan(GameTestHelper helper, BotPlayer bot) {
        bot.getServer().overworld().getDataStorage().set("blocknet_world_" + bot.getUUID(), new WorldMemory());
        var map = bot.worldMemory().observed();
        map.begin(dimension(bot), helper.getLevel().getGameTime());
        VoxelRay ray = new VoxelRay(bot);
        for (int x = 2; x < 47; x++) for (int y = 1; y < 22; y++) {
            Vec3 delta = helper.absoluteVec(new Vec3(x + .5, y + .5, 14.5)).subtract(bot.getEyePosition());
            ray.cast(bot.getEyePosition(), delta.normalize(), delta.length(), map);
        }
        return bot.worldMemory().observed().evidence(dimension(bot));
    }

    @GameTest(template = "vision_field", skyAccess = true, timeoutTicks = 400)
    public static void exposedCoalAndCoalBehindGlassAreSeen(GameTestHelper helper) {
        BotPlayer bot = field(helper, "BnVisionGlass");
        for (int x = 15; x <= 34; x++) for (int y = 1; y <= 8; y++) helper.setBlock(new BlockPos(x, y, 14), Blocks.STONE);
        BlockPos exposed = new BlockPos(22, 3, 14), glazed = new BlockPos(28, 3, 14);
        helper.setBlock(exposed, Blocks.COAL_ORE);
        helper.setBlock(glazed, Blocks.COAL_ORE);
        for (int x = 26; x <= 31; x++) for (int y = 1; y <= 7; y++) helper.setBlock(new BlockPos(x, y, 17), Blocks.GLASS);
        List<String> reports = new ArrayList<>();
        whenLit(helper, () -> bot.controller().look(reports::add));
        helper.succeedWhen(() -> {
            helper.assertTrue(reports.size() == 1, "look finished");
            for (BlockPos pos : List.of(exposed, glazed)) {
                var cell = bot.worldMemory().observed().cell(dimension(bot), helper.absolutePos(pos));
                helper.assertTrue(cell != null && cell.stateId() == Block.getId(Blocks.COAL_ORE.defaultBlockState()), "coal seen " + pos + ": " + reports);
            }
            helper.assertTrue(bot.vision().objects().stream().filter(object -> object.category().equals("ore") && object.kind().equals("coal")).count() == 2,
                "two coal veins: " + reports);
            BotManager.despawn("BnVisionGlass");
        });
    }

    @GameTest(template = "vision_field", skyAccess = true, timeoutTicks = 400)
    public static void darknessHidesIdentityExceptAtCloseRange(GameTestHelper helper) {
        BotPlayer bot = field(helper, "BnVisionDark");
        for (int x = 10; x <= 38; x++) for (int z = 10; z <= 38; z++) helper.setBlock(new BlockPos(x, 9, z), Blocks.STONE);
        for (int a = 10; a <= 38; a++) for (int y = 1; y <= 9; y++) {
            helper.setBlock(new BlockPos(a, y, 10), Blocks.STONE);
            helper.setBlock(new BlockPos(a, y, 38), Blocks.STONE);
            helper.setBlock(new BlockPos(10, y, a), Blocks.STONE);
            helper.setBlock(new BlockPos(38, y, a), Blocks.STONE);
        }
        BlockPos ore = new BlockPos(24, 2, 14);
        helper.setBlock(ore, Blocks.COAL_ORE);
        helper.runAfterDelay(15, () -> {
            BlockPos absolute = helper.absolutePos(ore);
            helper.assertTrue(VoxelRay.visibleLight(helper.getLevel(), absolute.south()) < 4, "closed room is dark");
            var map = bot.worldMemory().observed();
            map.begin(dimension(bot), helper.getLevel().getGameTime());
            Vec3 delta = Vec3.atCenterOf(absolute).subtract(bot.getEyePosition());
            new VoxelRay(bot).cast(bot.getEyePosition(), delta.normalize(), delta.length(), map);
            var far = bot.worldMemory().observed().cell(dimension(bot), absolute);
            helper.assertTrue(far != null && far.stateId() == ObservedMap.DARK_STATE && far.semantic().category().equals("dark"), "far ore has no identity");
            helper.assertTrue(bot.vision().objects().stream().noneMatch(object -> object.category().equals("ore")), "no coal vein in darkness");
            bot.setPos(helper.absoluteVec(new Vec3(24.5, 1, 17.5)));
            delta = Vec3.atCenterOf(absolute).subtract(bot.getEyePosition());
            new VoxelRay(bot).cast(bot.getEyePosition(), delta.normalize(), delta.length(), map);
            var close = bot.worldMemory().observed().cell(dimension(bot), absolute);
            helper.assertTrue(close != null && close.stateId() == Block.getId(Blocks.COAL_ORE.defaultBlockState()), "3-block ore identified");
            BotManager.despawn("BnVisionDark");
            helper.succeed();
        });
    }

    @GameTest(template = "vision_field", skyAccess = true, timeoutTicks = 400)
    public static void openSideIsAnUnexploredFrontier(GameTestHelper helper) {
        BotPlayer bot = field(helper, "BnVisionFront");
        // Three close walls; north opens beyond the template, away from parallel fixtures.
        for (int x = 1; x < 47; x++) for (int y = 1; y <= 24; y++) {
            helper.setBlock(new BlockPos(x, y, 0), Blocks.AIR);
            helper.setBlock(new BlockPos(x, y, -1), Blocks.AIR); // GameTest's enclosing barrier
        }
        for (int a = 17; a <= 31; a++) for (int y = 1; y <= 8; y++) {
            helper.setBlock(new BlockPos(31, y, a), Blocks.STONE);
            helper.setBlock(new BlockPos(a, y, 31), Blocks.STONE);
            helper.setBlock(new BlockPos(17, y, a), Blocks.STONE);
        }
        List<String> reports = new ArrayList<>();
        whenLit(helper, () -> bot.controller().look(reports::add));
        helper.succeedWhen(() -> {
            helper.assertTrue(reports.size() == 1, "look finished");
            var frontiers = bot.vision().frontiers();
            var open = frontiers.stream().filter(frontier -> frontier.direction().equals("N")).findFirst().orElseThrow();
            helper.assertTrue(!open.blocked() && !bot.worldMemory().observed().known(dimension(bot), open.unknownStart()), "north starts at unknown: " + frontiers);
            helper.assertTrue(frontiers.stream().filter(frontier -> Set.of("E", "S", "W").contains(frontier.direction()))
                .allMatch(Frontiers.Frontier::blocked), "three walls are observed: " + frontiers);
            BotManager.despawn("BnVisionFront");
        });
    }

    @GameTest(template = "vision_field", skyAccess = true, timeoutTicks = 300)
    public static void lookAndBackgroundRespectVisionBudget(GameTestHelper helper) {
        BotPlayer bot = field(helper, "BnVisionBudget");
        tree(helper, 24, 12, Blocks.OAK_LOG, Blocks.OAK_LEAVES);
        List<String> reports = new ArrayList<>();
        bot.controller().look(reports::add);
        for (int delay : new int[] {60, 100, 140}) {
            helper.runAfterDelay(delay, () -> bot.controller().look(reports::add));
        }
        helper.runAfterDelay(220, () -> {
            helper.assertTrue(reports.size() == 4, "four looks completed with background intervals");
            var timing = bot.vision().timing();
            helper.assertTrue(timing.frameCount() > 4, "completed full frames measured");
            Blocknet.LOGGER.info("VISION_MEASUREMENTS {} | {}", timing.report(), bot.vision().report());
            helper.assertTrue(timing.tickP95() < VisionSensor.BUDGET_NANOS / 1_000_000.0, "p95 below budget: " + timing.report());
            BotManager.despawn("BnVisionBudget");
            helper.succeed();
        });
    }
}

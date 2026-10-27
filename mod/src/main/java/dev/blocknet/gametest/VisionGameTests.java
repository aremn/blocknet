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
            // Wider peripheral sampling can see the next parallel fixture beyond the open exit.
            helper.assertTrue(open.knownDistance() >= 24 || !open.blocked()
                && !bot.worldMemory().observed().known(dimension(bot), open.unknownStart()), "north exit is open: " + frontiers);
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
    @GameTest(template = "vision_field", skyAccess = true, timeoutTicks = 600)
    public static void sceneGroupsTreesAndSecondLookHasNoChange(GameTestHelper helper) {
        BotPlayer bot = field(helper, "BnScene");
        tree(helper, 24, 12, Blocks.OAK_LOG, Blocks.OAK_LEAVES);
        tree(helper, 36, 24, Blocks.BIRCH_LOG, Blocks.BIRCH_LEAVES);
        tree(helper, 12, 36, Blocks.OAK_LOG, Blocks.OAK_LEAVES);
        // A fourth, different species is completely behind a tall wall.
        for (int z = 1; z < 47; z++) for (int y = 1; y < 22; y++) helper.setBlock(new BlockPos(7, y, z), Blocks.STONE);
        tree(helper, 3, 24, Blocks.SPRUCE_LOG, Blocks.SPRUCE_LEAVES);
        var reports = new ArrayList<String>();
        whenLit(helper, () -> bot.controller().look(message -> {
            reports.add(message);
            bot.controller().look(reports::add);
        }));
        helper.succeedWhen(() -> {
            helper.assertTrue(reports.size() == 2, "two looks completed: " + reports);
            String scene = reports.getFirst();
            helper.assertTrue(scene.contains("you: on stone, facing") && scene.contains("holding nothing"), scene);
            helper.assertTrue(scene.matches("(?s).*trees: .*t[0-9]+.*oak x2 .*m.*") && scene.contains("birch x1 E"), scene);
            helper.assertTrue(!scene.contains("spruce"), "hidden species absent: " + scene);
            helper.assertTrue(reports.get(1).startsWith("no change since last look"), reports.toString());
            Blocknet.LOGGER.info("SCENE_SAMPLE {}", scene.replace('\n', '|'));
            BotManager.despawn("BnScene");
        });
    }

    @GameTest(template = "vision_field", skyAccess = true, timeoutTicks = 1000)
    public static void gatherRefCollectsOnlyItsTree(GameTestHelper helper) {
        BotPlayer bot = field(helper, "BnGatherRef");
        tree(helper, 24, 19, Blocks.OAK_LOG, Blocks.OAK_LEAVES);
        tree(helper, 34, 24, Blocks.BIRCH_LOG, Blocks.BIRCH_LEAVES);
        bot.getInventory().clearContent();
        var reports = new ArrayList<String>();
        whenLit(helper, () -> bot.controller().look(message -> {
            var tree = bot.vision().objects().stream().filter(o -> o.category().equals("tree") && o.kind().equals("oak")).findFirst().orElseThrow();
            // Exercise the same command parser the bridge uses, including a ref and omitted count.
            var source = bot.createCommandSourceStack().withPermission(2);
            bot.getServer().getCommands().performPrefixedCommand(source, "blocknet gather BnGatherRef " + tree.ref() + " 3");
            reports.add(message);
        }));
        helper.succeedWhen(() -> {
            helper.assertTrue(!reports.isEmpty() && bot.getInventory().countItem(net.minecraft.world.item.Items.OAK_LOG) >= 3, "three oak logs collected");
            helper.assertTrue(bot.getInventory().countItem(net.minecraft.world.item.Items.BIRCH_LOG) == 0, "no birch collected");
            for (int y = 1; y <= 5; y++) helper.assertBlockPresent(Blocks.BIRCH_LOG, new BlockPos(34, y, 24));
            BotManager.despawn("BnGatherRef");
        });
    }

    @GameTest(template = "vision_field", skyAccess = true, timeoutTicks = 400)
    public static void unseenLogsSuggestExploreAndStoneRequiresPickaxe(GameTestHelper helper) {
        BotPlayer bot = field(helper, "BnGatherHint");
        bot.getInventory().clearContent();
        var reports = new ArrayList<String>();
        whenLit(helper, () -> bot.controller().look(message -> {
            bot.controller().gather("logs", 3, text -> {
                reports.add(text);
                bot.controller().gather("cobblestone", 5, reports::add);
            });
        }));
        helper.succeedWhen(() -> {
            helper.assertTrue(reports.size() == 2, "two results: " + reports);
            helper.assertTrue(reports.getFirst().contains("none seen") && reports.getFirst().contains("unexplored:") && reports.getFirst().contains("try explore"), reports.toString());
            helper.assertTrue(reports.get(1).startsWith("blocked: need a pickaxe for stone") && reports.get(1).contains("craft wooden_pickaxe: needs"), reports.toString());
            for (int x = 1; x < 47; x++) for (int z = 1; z < 47; z++) helper.assertBlockPresent(Blocks.STONE, new BlockPos(x, 0, z));
            helper.assertTrue(bot.getInventory().countItem(net.minecraft.world.item.Items.COBBLESTONE) == 0, "nothing broken");
            Blocknet.LOGGER.info("GATHER_HINT_SAMPLE {}", reports);
            BotManager.despawn("BnGatherHint");
        });
    }

    @GameTest(template = "vision_field", skyAccess = true, timeoutTicks = 800)
    public static void exploreOpenSideRevealsHiddenTree(GameTestHelper helper) {
        BotPlayer bot = field(helper, "BnExplore");
        // North wall hides a tree; east is a known, safe route around the end of that wall.
        for (int x = 16; x <= 28; x++) for (int y = 1; y < 22; y++) helper.setBlock(new BlockPos(x, y, 19), Blocks.STONE);
        tree(helper, 24, 13, Blocks.OAK_LOG, Blocks.OAK_LEAVES);
        var reports = new ArrayList<String>();
        whenLit(helper, () -> bot.controller().look(message -> {
            helper.assertTrue(bot.vision().objects().stream().noneMatch(o -> o.category().equals("tree")), "tree initially hidden");
            bot.controller().explore("E", 12, reports::add);
        }));
        helper.succeedWhen(() -> {
            helper.assertTrue(reports.size() == 1, "explore finished: " + reports);
            helper.assertTrue(reports.getFirst().startsWith("new:") && reports.getFirst().contains("oak") && reports.getFirst().matches(".*t[0-9]+.*"), reports.toString());
            helper.assertTrue(bot.getX() > helper.absolutePos(new BlockPos(28, 1, 24)).getX(), "walked around the starting wall");
            Blocknet.LOGGER.info("EXPLORE_SAMPLE {}", reports.getFirst());
            BotManager.despawn("BnExplore");
        });
    }

    @GameTest(template = "vision_field", skyAccess = true, timeoutTicks = 1200)
    public static void gatherFollowsNewlyExposedCoalVein(GameTestHelper helper) {
        coalVein(helper, "BnCoalVein", false);
    }

    @GameTest(template = "vision_field", skyAccess = true, timeoutTicks = 1200)
    public static void gatherItemArgumentAcceptsObjectRef(GameTestHelper helper) {
        coalVein(helper, "BnCoalItemRef", true);
    }

    private static void coalVein(GameTestHelper helper, String name, boolean ref) {
        BotPlayer bot = field(helper, name);
        for (int x = 22; x <= 26; x++) for (int y = 1; y <= 5; y++) for (int z = 19; z <= 22; z++)
            helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
        for (int z = 20; z <= 22; z++) helper.setBlock(new BlockPos(24, 2, z), Blocks.COAL_ORE);
        bot.getInventory().clearContent();
        bot.getInventory().setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE_PICKAXE));
        var reports = new ArrayList<String>();
        {
            TestArena.see(helper, bot, new BlockPos(24, 2, 22));
            helper.assertTrue(!bot.worldMemory().observed().known(dimension(bot), helper.absolutePos(new BlockPos(24, 2, 21))), "second ore is initially hidden");
            if (ref) {
                String oreRef = bot.vision().objects().stream().filter(o -> o.category().equals("ore")).findFirst().orElseThrow().ref();
                var args = new com.google.gson.JsonObject(); args.addProperty("item", oreRef); args.addProperty("count", 3);
                dev.blocknet.bridge.BridgeTools.run(bot, dev.blocknet.bridge.BridgeTools.get("gather"), args, new HashMap<>(), reports::add);
            } else bot.controller().gather("coal", 3, reports::add);
        }
        helper.succeedWhen(() -> {
            helper.assertTrue(reports.size() == 1 && reports.getFirst().contains("gathered 3 coal"), reports.toString());
            helper.assertTrue(bot.getInventory().countItem(net.minecraft.world.item.Items.COAL) == 3, "three coal picked up");
            for (int z = 20; z <= 22; z++) helper.assertBlockPresent(Blocks.AIR, new BlockPos(24, 2, z));
            helper.assertTrue(helper.getTick() >= 45, "survival mining time");
            BotManager.despawn(name);
        });
    }

    @GameTest(template = "vision_field", skyAccess = true, timeoutTicks = 500)
    public static void recentLookUsesFrameAndFreshTurns(GameTestHelper helper) {
        BotPlayer bot = field(helper, "BnLookCache");
        var reports = new ArrayList<String>();
        double[] quickTurn = {0}, freshTurn = {0};
        float[] previous = {bot.getYRot()};
        int[] phase = {0};
        helper.onEachTick(() -> {
            double change = Math.abs(net.minecraft.util.Mth.wrapDegrees(bot.getYRot() - previous[0]));
            if (phase[0] == 1) quickTurn[0] += change;
            if (phase[0] == 2) freshTurn[0] += change;
            previous[0] = bot.getYRot();
        });
        bot.controller().look(first -> {
            helper.runAfterDelay(100, () -> {
                phase[0] = 1; previous[0] = bot.getYRot();
                bot.controller().look(second -> {
                    reports.add(second);
                    phase[0] = 2; previous[0] = bot.getYRot();
                    // Exercise the command syntax used by fresh:true in the bridge.
                    var args = new com.google.gson.JsonObject(); args.addProperty("fresh", true);
                    dev.blocknet.bridge.BridgeTools.run(bot, dev.blocknet.bridge.BridgeTools.get("look"), args, new HashMap<>(), reports::add);
                });
            });
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(reports.size() == 2, "cached and fresh reports: " + reports);
            helper.assertTrue(reports.getFirst().matches("(?s).*\\(looked [5-9][0-9]*s ago\\).*"), reports.toString());
            helper.assertTrue(quickTurn[0] < 120, "cached yaw travel " + quickTurn[0]);
            helper.assertTrue(freshTurn[0] > 300, "fresh yaw travel " + freshTurn[0]);
            BotManager.despawn("BnLookCache");
        });
    }

    @GameTest(template = "vision_field", skyAccess = true, timeoutTicks = 2000)
    public static void tunnelReportsUncoveredIronWithRef(GameTestHelper helper) {
        BotPlayer bot = field(helper, "BnTunnelIron");
        for (int x = 22; x <= 26; x++) for (int y = 1; y <= 4; y++) for (int z = 13; z <= 23; z++)
            helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
        helper.setBlock(new BlockPos(23, 2, 18), Blocks.IRON_ORE);
        bot.getInventory().clearContent();
        bot.getInventory().setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE_PICKAXE));
        var reports = new ArrayList<String>();
        var args = new com.google.gson.JsonObject(); args.addProperty("direction", "N"); args.addProperty("length", 10);
        dev.blocknet.bridge.BridgeTools.run(bot, dev.blocknet.bridge.BridgeTools.get("tunnel"), args, new HashMap<>(), reports::add);
        helper.succeedWhen(() -> {
            helper.assertTrue(reports.size() == 1 && reports.getFirst().matches("(?s).*tunneled 10 N; uncovered:.*iron x1 [a-z][0-9]+.*"), reports.toString());
            helper.assertBlockPresent(Blocks.IRON_ORE, new BlockPos(23, 2, 18));
            helper.assertTrue(helper.getTick() >= 150, "real break time");
            BotManager.despawn("BnTunnelIron");
        });
    }

    @GameTest(template = "vision_field", skyAccess = true, timeoutTicks = 800)
    public static void descendStopsBeforeLava(GameTestHelper helper) {
        BotPlayer bot = field(helper, "BnDescendLava");
        for (int x = 22; x <= 26; x++) for (int y = 1; y <= 8; y++) for (int z = 18; z <= 26; z++)
            helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
        helper.setBlock(new BlockPos(24, 7, 22), Blocks.LAVA);
        bot.setPos(helper.absoluteVec(new Vec3(24.5, 9, 24.5)));
        bot.getInventory().clearContent();
        bot.getInventory().setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE_PICKAXE));
        var reports = new ArrayList<String>();
        var args = new com.google.gson.JsonObject(); args.addProperty("y", helper.absolutePos(new BlockPos(24, 4, 24)).getY()); args.addProperty("direction", "N");
        dev.blocknet.bridge.BridgeTools.run(bot, dev.blocknet.bridge.BridgeTools.get("descend"), args, new HashMap<>(), reports::add);
        helper.succeedWhen(() -> {
            helper.assertTrue(reports.size() == 1 && reports.getFirst().contains("stopped before lava"), reports.toString());
            helper.assertTrue(bot.getZ() > helper.absolutePos(new BlockPos(24, 7, 22)).getZ() + 1, "stopped outside lava");
            helper.assertTrue(!bot.isInLava() && bot.getHealth() == bot.getMaxHealth(), "no lava damage");
            helper.assertBlockPresent(Blocks.LAVA, new BlockPos(24, 7, 22));
            BotManager.despawn("BnDescendLava");
        });
    }

}

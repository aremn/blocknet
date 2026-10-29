package dev.blocknet.gametest;

import com.google.gson.JsonObject;
import dev.blocknet.Blocknet;
import dev.blocknet.body.BotManager;
import dev.blocknet.body.BotPlayer;
import dev.blocknet.bridge.BridgeTools;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The exam: whole player orders, each sent exactly as the planner sends a step (the bridge tool
 * registry), in a small but lived-in world: grass over dirt, trees, a three-deep river, a stone
 * cliff with coal and iron, wandering sheep, a chest, another player. The bot starts with the
 * messy inventory of a real session and full perception, as it plays live.
 *
 * Exam tests are optional: a failure does not fail the build, it is a measured gap.
 * {@code node brain/bench/exam-report.js build/gametest.log} prints the pass rate; every live
 * failure becomes an exam order first, then gets fixed.
 */
@GameTestHolder(Blocknet.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ExamGameTests {
    private static final String WORLD = "vision_field";
    /** Same rule as the brain's MCP classification of task reports (session-log.js failed()). */
    private static final Pattern FAILED = Pattern.compile(
        "^(?:\\[[^\\]]+\\] )?(?:failed|blocked|cancelled|not hungry|stopped|unsupported|none seen)\\b|; stopped(?: before|:)",
        Pattern.CASE_INSENSITIVE);
    private static final int SURFACE = 3, FEET = 4, RIVER_FROM = 30, RIVER_TO = 34;
    private static final BlockPos START = new BlockPos(24, FEET, 20), CHEST = new BlockPos(28, FEET, 16);

    private ExamGameTests() {}

    // ---- the world ----

    /** 48x48: stone floor, two dirt layers, grass; stone cliffs all round with coal and iron in the west one. */
    private static void meadow(GameTestHelper h) {
        for (int x = 0; x < 48; x++) for (int z = 0; z < 48; z++) {
            boolean wall = x == 0 || z == 0 || x == 47 || z == 47;
            boolean river = z >= RIVER_FROM && z <= RIVER_TO && !wall;
            h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            for (int y = 1; y <= 8; y++) {
                Block block = wall ? Blocks.STONE : y > SURFACE ? Blocks.AIR : river ? Blocks.WATER : y == SURFACE ? Blocks.GRASS_BLOCK : Blocks.DIRT;
                h.setBlock(new BlockPos(x, y, z), block);
            }
        }
        for (int z = 9; z <= 11; z++) for (int y = FEET; y <= FEET + 1; y++) h.setBlock(new BlockPos(0, y, z), Blocks.COAL_ORE);
        for (int z = 14; z <= 16; z++) h.setBlock(new BlockPos(0, FEET, z), Blocks.IRON_ORE);
        tree(h, 8, 8); tree(h, 14, 5); tree(h, 19, 9); tree(h, 37, 10); tree(h, 40, 22);
    }

    private static void tree(GameTestHelper h, int x, int z) {
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int y = FEET + 3; y <= FEET + 5; y++) {
            if (Math.abs(dx) + Math.abs(dz) > 3 || y == FEET + 5 && Math.abs(dx) + Math.abs(dz) > 1) continue;
            h.setBlock(new BlockPos(x + dx, y, z + dz), Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
        }
        for (int y = FEET; y <= FEET + 4; y++) h.setBlock(new BlockPos(x, y, z), Blocks.OAK_LOG);
    }

    private static List<Sheep> flock(GameTestHelper h, int count) {
        List<Sheep> sheep = new ArrayList<>();
        for (int i = 0; i < count; i++) sheep.add(h.spawn(EntityType.SHEEP, new BlockPos(30 + 4 * i, FEET, 10 + 3 * i)));
        return sheep;
    }

    private static Container chest(GameTestHelper h, BlockPos at) {
        h.setBlock(at, Blocks.CHEST);
        return (Container) h.getBlockEntity(at);
    }

    // ---- the bot ----

    /**
     * The inventory of the live session that failed on 2026-10-29 (AE2 parts swapped for vanilla
     * junk): no building block in the hotbar, logs in the main inventory, tools scattered.
     */
    private static void liveBag(BotPlayer bot) {
        var inv = bot.getInventory();
        inv.clearContent();
        ItemStack pick = new ItemStack(Items.STONE_PICKAXE); pick.setDamageValue(39);
        Object[][] slots = {
            {0, pick}, {1, Items.GUNPOWDER, 2}, {2, Items.EGG, 3}, {3, Items.DIRT, 20}, {4, Items.FLINT, 1},
            {5, Items.IRON_NUGGET, 16}, {6, Items.BOW, 1}, {7, Items.BONE, 3}, {8, Items.GOLDEN_LEGGINGS, 1},
            {9, Items.OAK_LOG, 3}, {10, Items.NETHERITE_SWORD, 1}, {11, Items.CRAFTING_TABLE, 1}, {12, Items.GLISTERING_MELON_SLICE, 6},
            {13, Items.FURNACE, 1}, {14, Items.GOLDEN_BOOTS, 1}, {15, Items.CHEST, 1}, {16, Items.STRING, 4},
            {17, Items.COBBLESTONE, 34}, {18, Items.FEATHER, 2}, {19, Items.ROTTEN_FLESH, 1}, {21, Items.WOODEN_PICKAXE, 1},
            {22, Items.COOKED_BEEF, 64},
        };
        for (Object[] slot : slots) {
            ItemStack stack = slot[1] instanceof ItemStack s ? s : new ItemStack((Item) slot[1], (Integer) slot[2]);
            inv.setItem((Integer) slot[0], stack);
        }
    }

    private static BotPlayer bot(GameTestHelper h, String name, boolean live) {
        meadow(h);
        BotPlayer bot = TestArena.spawnBot(h, name, Vec3.atBottomCenterOf(START));
        bot.fullPerception(true);
        if (live) liveBag(bot); else bot.getInventory().clearContent();
        return bot;
    }

    private static BotPlayer player(GameTestHelper h, String name, BlockPos at) {
        BotPlayer player = TestArena.spawnBot(h, name, Vec3.atBottomCenterOf(at));
        player.getInventory().clearContent();
        return player;
    }

    private static ServerPlayer human(GameTestHelper h, String name, BlockPos at) {
        return TestArena.human(h, name, Vec3.atBottomCenterOf(at));
    }

    private static int count(BotPlayer bot, Item item) { return bot.getInventory().countItem(item); }

    private static JsonObject args(Object... pairs) {
        JsonObject args = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i + 1] instanceof Number n) args.addProperty((String) pairs[i], n); else args.addProperty((String) pairs[i], String.valueOf(pairs[i + 1]));
        }
        return args;
    }

    private static JsonObject at(GameTestHelper h, BlockPos local, Object... more) {
        BlockPos abs = h.absolutePos(local);
        JsonObject args = args(more);
        args.addProperty("x", abs.getX()); args.addProperty("y", abs.getY()); args.addProperty("z", abs.getZ());
        return args;
    }

    // ---- the examiner ----

    /**
     * Sends one order through the bridge once the world is lit, then grades it: the report must not
     * be a failure (same rule as the brain) and {@code check} must hold (given the report: null when
     * done, else why not).
     * {@code untilCheck}: orders that never finish by themselves (follow) pass when check holds.
     */
    private static void exam(GameTestHelper h, String id, int timeout, BotPlayer bot, String tool, JsonObject args,
            Function<String, String> check, boolean untilCheck, String... others) {
        List<String> reports = new ArrayList<>();
        int[] started = {-1};
        boolean[] over = {false};
        h.onEachTick(() -> {
            if (over[0]) return;
            if (started[0] < 0) {
                boolean lit = h.getLevel().getMaxLocalRawBrightness(h.absolutePos(START.above())) >= 8;
                if (h.getTick() < 20 || !lit && h.getTick() < 200) return;
                started[0] = (int) h.getTick();
                try {
                    BridgeTools.run(bot, BridgeTools.get(tool), args, new HashMap<>(), reports::add);
                } catch (RuntimeException e) {
                    reports.add("failed: order rejected: " + e.getMessage());
                }
                return;
            }
            long ticks = h.getTick() - started[0];
            String why = null;
            if (bot.isDeadOrDying() || bot.isRemoved()) why = "died: " + bot.getLastDamageSource();
            else if (!reports.isEmpty() && FAILED.matcher(reports.getFirst()).find()) why = "reported " + reports.getFirst();
            else if (!reports.isEmpty() || untilCheck) {
                String missing = check.apply(reports.isEmpty() ? null : reports.getFirst());
                if (missing == null) {
                    over[0] = true;
                    Blocknet.LOGGER.info("EXAM {} PASS in {} ticks: {}", id, ticks, reports.isEmpty() ? "(running)" : reports.getFirst());
                    cleanup(bot, others);
                    h.succeed();
                    return;
                }
                if (!reports.isEmpty()) why = "reported '" + reports.getFirst() + "' but " + missing;
            }
            if (why == null && h.getTick() >= timeout - 5) why = "timed out while " + bot.controller().describe();
            if (why != null) {
                over[0] = true;
                Blocknet.LOGGER.info("EXAM {} FAIL in {} ticks: {}", id, ticks, why);
                cleanup(bot, others);
                h.fail(id + ": " + why);
            }
        });
    }

    private static void cleanup(BotPlayer bot, String... others) {
        bot.controller().stop();
        BotManager.despawn(bot.getGameProfile().getName());
        for (String other : others) {
            if (BotManager.get(other) != null) BotManager.despawn(other);
            else {
                var list = bot.getServer().getPlayerList();
                ServerPlayer human = list.getPlayerByName(other);
                if (human != null) TestArena.removeHuman(human);
            }
        }
    }

    // ---- the orders (planner step form in each comment) ----

    /** "obtain minecraft:white_bed 1 world" - live 2026-10-29: re-dyed wool cycle, then a craft cursor race. */
    @GameTest(template = WORLD, skyAccess = true, required = false, timeoutTicks = 6000)
    public static void exam01BedFromSheep(GameTestHelper h) {
        BotPlayer bot = bot(h, "ExBed", true);
        flock(h, 3);
        exam(h, "bed_from_sheep", 6000, bot, "obtain", args("item", "minecraft:white_bed", "count", 1, "source", "world"),
            r -> count(bot, Items.WHITE_BED) >= 1 ? null : "no white_bed", false);
    }

    /** "craft minecraft:oak_planks 4" with the live hotbar. */
    @GameTest(template = WORLD, skyAccess = true, required = false, timeoutTicks = 600)
    public static void exam02PlanksMessyHotbar(GameTestHelper h) {
        BotPlayer bot = bot(h, "ExPlanks", true);
        exam(h, "planks_messy_hotbar", 600, bot, "craft", args("item", "minecraft:oak_planks", "count", 4),
            r -> count(bot, Items.OAK_PLANKS) >= 4 ? null : "planks " + count(bot, Items.OAK_PLANKS), false);
    }

    /** "obtain minecraft:stone_pickaxe 1 world" from an empty inventory: trees, then the cliff. */
    @GameTest(template = WORLD, skyAccess = true, required = false, timeoutTicks = 8000)
    public static void exam03StonePickaxeFromNothing(GameTestHelper h) {
        BotPlayer bot = bot(h, "ExStonePick", false);
        exam(h, "stone_pickaxe_from_nothing", 8000, bot, "obtain", args("item", "minecraft:stone_pickaxe", "count", 1, "source", "world"),
            r -> count(bot, Items.STONE_PICKAXE) >= 1 ? null : "no stone_pickaxe", false);
    }

    /** "obtain minecraft:torch 8 world": coal from the cliff, sticks from logs. */
    @GameTest(template = WORLD, skyAccess = true, required = false, timeoutTicks = 8000)
    public static void exam04Torches(GameTestHelper h) {
        BotPlayer bot = bot(h, "ExTorch", false);
        bot.getInventory().setItem(0, new ItemStack(Items.WOODEN_PICKAXE));
        exam(h, "torches", 8000, bot, "obtain", args("item", "minecraft:torch", "count", 8, "source", "world"),
            r -> count(bot, Items.TORCH) >= 8 ? null : "torches " + count(bot, Items.TORCH), false);
    }

    /** "give ExCoalP minecraft:coal" - "принеси уголь": everything matching, across the meadow. */
    @GameTest(template = WORLD, skyAccess = true, required = false, timeoutTicks = 1500)
    public static void exam05BringCoal(GameTestHelper h) {
        BotPlayer bot = bot(h, "ExCoal", true);
        bot.getInventory().setItem(20, new ItemStack(Items.COAL, 20));
        BotPlayer player = player(h, "ExCoalP", new BlockPos(10, FEET, 22));
        exam(h, "bring_coal", 1500, bot, "give", args("player", "ExCoalP", "item", "minecraft:coal"),
            r -> count(player, Items.COAL) == 20 ? null : "player has " + count(player, Items.COAL) + " coal", false, "ExCoalP");
    }

    /** "deposit x y z loot" - "убери всё в сундук": junk goes in, tools, weapons, armor and food stay. */
    @GameTest(template = WORLD, skyAccess = true, required = false, timeoutTicks = 1500)
    public static void exam06PutLootAway(GameTestHelper h) {
        BotPlayer bot = bot(h, "ExLoot", true);
        Container chest = chest(h, CHEST);
        exam(h, "put_loot_away", 1500, bot, "deposit", at(h, CHEST, "filter", "loot"), r -> {
            int cobble = 0;
            for (int i = 0; i < chest.getContainerSize(); i++) if (chest.getItem(i).is(Items.COBBLESTONE)) cobble += chest.getItem(i).getCount();
            if (cobble != 34) return "chest has " + cobble + " cobblestone";
            if (count(bot, Items.NETHERITE_SWORD) != 1 || count(bot, Items.STONE_PICKAXE) != 1) return "a tool went into the chest";
            return count(bot, Items.COOKED_BEEF) == 64 ? null : "food went into the chest";
        }, false);
    }

    /** "withdraw x y z minecraft:iron_ingot 3" from the far half of a double chest. */
    @GameTest(template = WORLD, skyAccess = true, required = false, timeoutTicks = 1200)
    public static void exam07TakeIronFromDoubleChest(GameTestHelper h) {
        BotPlayer bot = bot(h, "ExWithdraw", true);
        BlockPos left = CHEST, right = CHEST.east();
        h.setBlock(left, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.LEFT));
        h.setBlock(right, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.RIGHT));
        ((Container) h.getBlockEntity(right)).setItem(5, new ItemStack(Items.IRON_INGOT, 10));
        exam(h, "take_iron_double_chest", 1200, bot, "withdraw", at(h, right, "filter", "minecraft:iron_ingot", "count", 3),
            r -> count(bot, Items.IRON_INGOT) == 3 ? null : "has " + count(bot, Items.IRON_INGOT) + " iron", false);
    }

    /** "look-in x y z" on the other half of a double chest - live 2026-10-29: "did not open". */
    @GameTest(template = WORLD, skyAccess = true, required = false, timeoutTicks = 1200)
    public static void exam08LookIntoDoubleChest(GameTestHelper h) {
        BotPlayer bot = bot(h, "ExLookIn", true);
        BlockPos left = CHEST, right = CHEST.east();
        h.setBlock(left, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.LEFT));
        h.setBlock(right, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.RIGHT));
        ((Container) h.getBlockEntity(right)).setItem(5, new ItemStack(Items.DIAMOND, 2));
        exam(h, "look_into_double_chest", 1200, bot, "look-in", at(h, left),
            r -> r.contains("diamond") ? null : "the diamonds in the other half were not reported", false);
    }

    /** "come ExRiverP" - the player is across a three-deep river. */
    @GameTest(template = WORLD, skyAccess = true, required = false, timeoutTicks = 2400)
    public static void exam09ComeAcrossRiver(GameTestHelper h) {
        BotPlayer bot = bot(h, "ExCome", true);
        ServerPlayer player = human(h, "ExComeP", new BlockPos(24, FEET, 40));
        exam(h, "come_across_river", 2400, bot, "come", args("player", "ExComeP"), r -> {
            if (bot.distanceTo(player) > 5) return String.format("still %.1f blocks away", bot.distanceTo(player));
            return bot.getHealth() >= bot.getMaxHealth() ? null : "hurt on the way: health " + bot.getHealth();
        }, false, "ExComeP");
    }

    /** "follow ExLeadP" while the player swims across the river - live 2026-10-29: the bot nearly drowned. */
    @GameTest(template = WORLD, skyAccess = true, required = false, timeoutTicks = 2400)
    public static void exam10FollowAcrossRiver(GameTestHelper h) {
        BotPlayer bot = bot(h, "ExFollow", true);
        ServerPlayer leader = human(h, "ExLeadP", new BlockPos(24, FEET, 24));
        // Like a player walking south and swimming across: a block every five ticks from tick 60,
        // at water level over the river.
        for (int step = 0; step <= 18; step++) {
            int z = 24 + step;
            double y = z >= RIVER_FROM && z <= RIVER_TO ? SURFACE + 0.6 : FEET;
            h.runAtTickTime(60 + 5L * step, () -> TestArena.moveHuman(h, leader, new Vec3(24.5, y, z + 0.5)));
        }
        exam(h, "follow_across_river", 2400, bot, "follow", args("player", "ExLeadP"), r -> {
            if (leader.getZ() < h.absolutePos(new BlockPos(0, 0, 40)).getZ()) return "leader still crossing";
            if (bot.distanceTo(leader) > 6) return String.format("%.1f blocks behind", bot.distanceTo(leader));
            if (bot.getHealth() < bot.getMaxHealth()) return "hurt on the way: health " + bot.getHealth();
            return bot.isInWater() ? "still in the water" : null;
        }, true, "ExLeadP");
    }

    /** "pickup" - drops spread on grass and in the river shallows, as after a death. */
    @GameTest(template = WORLD, skyAccess = true, required = false, timeoutTicks = 2400)
    public static void exam11PickUpScatteredDrops(GameTestHelper h) {
        BotPlayer bot = bot(h, "ExPickup", true);
        Item[] drops = {Items.IRON_INGOT, Items.GOLD_INGOT, Items.DIAMOND, Items.REDSTONE, Items.EMERALD, Items.LAPIS_LAZULI};
        for (int i = 0; i < drops.length; i++) h.spawnItem(drops[i], new Vec3(16 + 3 * i, FEET + 0.5, 24 + (i % 3) * 3));
        exam(h, "pick_up_scattered", 2400, bot, "pickup", new JsonObject(), r -> {
            for (Item drop : drops) if (count(bot, drop) < 1) return "missed " + drop;
            return null;
        }, false);
    }

    /** "attack sheep" - a wandering sheep, its wool picked up. */
    @GameTest(template = WORLD, skyAccess = true, required = false, timeoutTicks = 1500)
    public static void exam12HuntSheep(GameTestHelper h) {
        BotPlayer bot = bot(h, "ExHunt", true);
        List<Sheep> sheep = flock(h, 2);
        exam(h, "hunt_sheep", 1500, bot, "attack", args("target", "sheep"), r -> {
            if (sheep.stream().allMatch(Sheep::isAlive)) return "no sheep dead";
            return count(bot, Items.WHITE_WOOL) >= 1 ? null : "wool not picked up";
        }, false);
    }

    /** "obtain minecraft:cooked_mutton 2 world": hunt, place the furnace, cook with logs. */
    @GameTest(template = WORLD, skyAccess = true, required = false, timeoutTicks = 6000)
    public static void exam13CookMutton(GameTestHelper h) {
        BotPlayer bot = bot(h, "ExMutton", true);
        flock(h, 3);
        exam(h, "cook_mutton", 6000, bot, "obtain", args("item", "minecraft:cooked_mutton", "count", 2, "source", "world"),
            r -> count(bot, Items.COOKED_MUTTON) >= 2 ? null : "cooked " + count(bot, Items.COOKED_MUTTON), false);
    }

    /** "eat": hungry, with rotten flesh in the hotbar and steak in the bag. */
    @GameTest(template = WORLD, skyAccess = true, required = false, timeoutTicks = 600)
    public static void exam14EatWhenHungry(GameTestHelper h) {
        BotPlayer bot = bot(h, "ExEat", true);
        bot.getFoodData().setFoodLevel(13);
        exam(h, "eat_when_hungry", 600, bot, "eat", new JsonObject(), r -> {
            if (count(bot, Items.ROTTEN_FLESH) != 1) return "ate rotten flesh";
            return bot.getFoodData().getFoodLevel() > 13 ? null : "still hungry: " + bot.getFoodData().getFoodLevel();
        }, false);
    }

    /** "obtain minecraft:oak_log 8 world" - several trees, leaves in the way. */
    @GameTest(template = WORLD, skyAccess = true, required = false, timeoutTicks = 6000)
    public static void exam15ChopLogs(GameTestHelper h) {
        BotPlayer bot = bot(h, "ExLogs", false);
        exam(h, "chop_logs", 6000, bot, "obtain", args("item", "minecraft:oak_log", "count", 8, "source", "world"),
            r -> count(bot, Items.OAK_LOG) >= 8 ? null : "logs " + count(bot, Items.OAK_LOG), false);
    }

    /** "obtain minecraft:iron_pickaxe 1 world" from nothing: the whole early game in one order. */
    @GameTest(template = WORLD, skyAccess = true, required = false, timeoutTicks = 24000)
    public static void exam16IronPickaxeFromNothing(GameTestHelper h) {
        BotPlayer bot = bot(h, "ExIronPick", false);
        exam(h, "iron_pickaxe_from_nothing", 24000, bot, "obtain", args("item", "minecraft:iron_pickaxe", "count", 1, "source", "world"),
            r -> count(bot, Items.IRON_PICKAXE) >= 1 ? null : "no iron_pickaxe", false);
    }
}

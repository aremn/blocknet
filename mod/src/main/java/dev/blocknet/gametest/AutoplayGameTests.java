package dev.blocknet.gametest;

import com.google.gson.JsonObject;
import dev.blocknet.Blocknet;
import dev.blocknet.body.BotManager;
import dev.blocknet.bridge.BridgeTools;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(Blocknet.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AutoplayGameTests {
    private AutoplayGameTests() {}

    @GameTest(template = "arena", timeoutTicks = 700)
    public static void approachingZombiePausesGatherFightsAndResumes(GameTestHelper helper) {
        for (int x = 0; x < 9; x++) for (int z = 0; z < 9; z++)
            for (int y = 0; y < 4; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
        var bot = TestArena.spawnBot(helper, "BnGuardGather", new Vec3(4.5, 4, 2.5));
        bot.getInventory().clearContent();
        bot.getInventory().setItem(0, new ItemStack(Items.IRON_PICKAXE));
        bot.getInventory().setItem(9, new ItemStack(Items.IRON_SWORD));
        var zombie = helper.spawn(EntityType.ZOMBIE, new Vec3(4.5, 4, 7.5));
        zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        zombie.setTarget(bot);
        var reports = new ArrayList<String>();
        bot.controller().gather("cobblestone", 2, reports::add);
        AtomicBoolean paused = new AtomicBoolean(), sword = new AtomicBoolean();
        helper.succeedWhen(() -> {
            if (bot.controller().defending()) {
                paused.set(true);
                sword.set(sword.get() || bot.getMainHandItem().is(Items.IRON_SWORD));
                helper.assertTrue(reports.isEmpty(), "gather remains pending during defense");
            }
            helper.assertTrue(paused.get() && sword.get(), "gather paused=" + paused + ", sword=" + sword
                + ", held=" + bot.getMainHandItem() + ", events=" + bot.moments().recent());
            helper.assertTrue(!zombie.isAlive(), "zombie killed through player attacks");
            // An extra nearby drop may be picked up; the report states the real count.
            var gathered = java.util.regex.Pattern.compile("gathered ([0-9]+) cobblestone").matcher(reports.isEmpty() ? "" : reports.getFirst());
            helper.assertTrue(reports.size() == 1 && gathered.matches() && Integer.parseInt(gathered.group(1)) >= 2, "gather resumed: " + reports);
            helper.assertTrue(bot.getInventory().countItem(Items.COBBLESTONE) == Integer.parseInt(gathered.group(1)), "gather finished");
            helper.assertTrue(helper.getTick() >= 40, "attacks obey player cooldown");
            BotManager.despawn("BnGuardGather");
        });
    }

    @GameTest(template = "arena_tall", skyAccess = true, timeoutTicks = 700)
    public static void defenseBeforeGatherWaitsForLandingAndResurveys(GameTestHelper helper) {
        for (int x = 0; x < 9; x++) for (int z = 0; z < 9; z++)
            for (int y = 0; y < 4; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
        var bot = TestArena.spawnBot(helper, "BnResumeSurvey", new Vec3(4.5, 4, 2.5));
        bot.getInventory().clearContent();
        bot.getInventory().setItem(0, new ItemStack(Items.IRON_PICKAXE));
        bot.getInventory().setItem(9, new ItemStack(Items.IRON_SWORD));
        // Remember a real source, then interrupt before gather has selected a mining target.
        TestArena.see(helper, bot, new BlockPos(4, 3, 3));
        var zombie = helper.spawn(EntityType.ZOMBIE, new Vec3(4.5, 4, 5));
        zombie.setNoAi(true);
        zombie.setHealth(1);
        zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        var reports = new ArrayList<String>();
        bot.controller().gather("cobblestone", 2, reports::add);
        bot.controller().mobHurt(bot.damageSources().mobAttack(zombie));
        AtomicBoolean displaced = new AtomicBoolean();
        helper.succeedWhen(() -> {
            if (!zombie.isAlive() && bot.controller().defending() && !displaced.getAndSet(true)) {
                // Deterministically model displacement at the end of defense. Physics must
                // settle before selection, rather than pathfinding to old ground from midair.
                bot.teleportTo(bot.getX(), bot.getY() + 6, bot.getZ());
                bot.setOnGround(false);
                bot.setDeltaMovement(Vec3.ZERO);
            }
            if (displaced.get() && !bot.onGround()) {
                helper.assertTrue(reports.isEmpty(), "gather stays pending during the fall: " + reports);
                helper.assertTrue(!bot.controller().isNavigating(), "wait for landing before choosing a route to a source");
            }
            helper.assertTrue(displaced.get() && !bot.controller().defending(), "defense completed before gather resumed");
            var gathered = java.util.regex.Pattern.compile("gathered ([0-9]+) cobblestone").matcher(reports.isEmpty() ? "" : reports.getFirst());
            helper.assertTrue(reports.size() == 1 && gathered.matches() && Integer.parseInt(gathered.group(1)) >= 2,
                "remembered source gathered after resurvey: " + reports);
            helper.assertTrue(bot.getInventory().countItem(Items.COBBLESTONE) == Integer.parseInt(gathered.group(1)), "real count reported");
            BotManager.despawn("BnResumeSurvey");
        });
    }

    @GameTest(template = "arena", timeoutTicks = 400)
    public static void foodLookContainerHintsAndCommonRefs(GameTestHelper helper) {
        TestArena.floor(helper);
        var bot = TestArena.spawnBot(helper, "BnAutoTools", new Vec3(4.5, 1, 4.5));
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.CHEST);
        helper.setBlock(new BlockPos(5, 1, 5), Blocks.STONE);
        helper.setBlock(new BlockPos(3, 0, 3), Blocks.FARMLAND);
        helper.setBlock(new BlockPos(3, 1, 3), Blocks.WHEAT);
        helper.setBlock(new BlockPos(6, 1, 3), Blocks.OAK_LOG);
        TestArena.see(helper, bot, new BlockPos(3, 1, 5));
        TestArena.see(helper, bot, new BlockPos(3, 1, 3));
        TestArena.see(helper, bot, new BlockPos(6, 1, 3));
        String ref = bot.vision().objects().stream().filter(o -> o.category().equals("tree")).findFirst().orElseThrow().ref();
        var look = new ArrayList<String>();
        JsonObject args = new JsonObject(); args.addProperty("focus", "food");
        BridgeTools.run(bot, BridgeTools.get("look"), args, new HashMap<>(), look::add);
        var inspect = new ArrayList<String>(); var go = new ArrayList<String>();
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(look.size() == 1, "food look completed"))
            .thenExecute(() -> {
                helper.assertTrue(look.getFirst().contains("wheat"), "food includes observed crops: " + look);
                bot.controller().lookIn(helper.absolutePos(new BlockPos(5, 1, 5)), inspect::add);
            })
            .thenWaitUntil(() -> helper.assertTrue(inspect.size() == 1, "inspection completed"))
            .thenExecute(() -> {
                helper.assertTrue(inspect.getFirst().contains("remembered container"), "stone hints remembered chest: " + inspect);
                JsonObject move = new JsonObject();
                JsonObject nested = new JsonObject(); nested.addProperty("ref", "\"" + ref + ",t999\""); move.add("ref", nested);
                BridgeTools.run(bot, BridgeTools.get("goto"), move, new HashMap<>(), go::add);
            })
            .thenWaitUntil(() -> helper.assertTrue(go.size() == 1, "goto completed"))
            .thenExecute(() -> {
                helper.assertTrue(go.getFirst().contains("arrived"), "first ref used: " + go);
                BotManager.despawn("BnAutoTools");
            })
            .thenSucceed();
    }
}

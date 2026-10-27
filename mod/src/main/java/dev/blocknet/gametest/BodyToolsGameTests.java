package dev.blocknet.gametest;

import com.google.gson.JsonObject;
import dev.blocknet.Blocknet;
import dev.blocknet.body.BotManager;
import dev.blocknet.body.BotPlayer;
import dev.blocknet.bridge.BridgeTools;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(Blocknet.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BodyToolsGameTests {
    private BodyToolsGameTests() {}

    private static void call(BotPlayer bot, String tool, JsonObject args, List<String> reports) {
        BridgeTools.run(bot, BridgeTools.get(tool), args, new HashMap<>(), reports::add);
    }

    @GameTest(template = "arena", timeoutTicks = 150)
    public static void eatsWhenHungryThroughPlayerUse(GameTestHelper helper) {
        TestArena.floor(helper);
        BotPlayer bot = TestArena.spawnBot(helper, "BnEatHungry", new Vec3(4.5, 1, 4.5));
        bot.getInventory().clearContent();
        bot.getInventory().setItem(12, new ItemStack(Items.BREAD, 2));
        bot.getInventory().setItem(0, new ItemStack(Items.ROTTEN_FLESH, 2));
        bot.getFoodData().setFoodLevel(14);
        var reports = new ArrayList<String>();
        call(bot, "eat", new JsonObject(), reports);
        helper.succeedWhen(() -> {
            helper.assertTrue(reports.size() == 1 && reports.getFirst().contains("ate bread (food 14 -> 19)"), "report: " + reports);
            helper.assertTrue(bot.getFoodData().getFoodLevel() == 19, "bread raised food");
            helper.assertTrue(bot.getInventory().countItem(Items.BREAD) == 1, "one bread consumed");
            helper.assertTrue(bot.getInventory().countItem(Items.ROTTEN_FLESH) == 2, "harmful food avoided");
            helper.assertTrue(helper.getTick() >= 32, "held use for normal eating duration");
            BotManager.despawn("BnEatHungry");
        });
    }

    @GameTest(template = "arena", timeoutTicks = 60)
    public static void fullBotRefusesOrdinaryFood(GameTestHelper helper) {
        TestArena.floor(helper);
        BotPlayer bot = TestArena.spawnBot(helper, "BnEatFull", new Vec3(4.5, 1, 4.5));
        bot.getInventory().clearContent(); bot.getInventory().setItem(0, new ItemStack(Items.BREAD, 2));
        bot.getFoodData().setFoodLevel(20);
        var reports = new ArrayList<String>();
        JsonObject args = new JsonObject(); args.addProperty("item", "bread");
        call(bot, "eat", args, reports);
        helper.succeedWhen(() -> {
            helper.assertTrue(reports.size() == 1 && reports.getFirst().contains("not hungry"), "report: " + reports);
            helper.assertTrue(bot.getInventory().countItem(Items.BREAD) == 2 && !bot.isUsingItem(), "nothing consumed or started");
            BotManager.despawn("BnEatFull");
        });
    }

    @GameTest(template = "arena", timeoutTicks = 200)
    public static void placesChestOnChestWithoutOpeningMenu(GameTestHelper helper) {
        TestArena.floor(helper);
        helper.setBlock(new BlockPos(4, 1, 4), Blocks.CHEST);
        BotPlayer bot = TestArena.spawnBot(helper, "BnPlaceChest", new Vec3(4.5, 1, 1.5));
        bot.getInventory().clearContent(); bot.getInventory().setItem(12, new ItemStack(Items.CHEST));
        var reports = new ArrayList<String>();
        BlockPos target = helper.absolutePos(new BlockPos(4, 2, 4));
        JsonObject args = position(target); args.addProperty("item", "chest"); args.addProperty("face", "up");
        call(bot, "place", args, reports);
        helper.onEachTick(() -> helper.assertTrue(bot.containerMenu == bot.inventoryMenu, "lower chest must never open"));
        helper.succeedWhen(() -> {
            helper.assertTrue(reports.size() == 1 && reports.getFirst().contains("placed chest at"), "report: " + reports);
            helper.assertBlockPresent(Blocks.CHEST, new BlockPos(4, 2, 4));
            helper.assertTrue(bot.getInventory().countItem(Items.CHEST) == 0 && !bot.isShiftKeyDown(), "item consumed, sneak restored");
            BotManager.despawn("BnPlaceChest");
        });
    }

    @GameTest(template = "arena", timeoutTicks = 400)
    public static void walksToPlaceBlockOnFloor(GameTestHelper helper) {
        TestArena.floor(helper);
        BotPlayer bot = TestArena.spawnBot(helper, "BnPlaceFloor", new Vec3(4.5, 1, 1.5));
        Vec3 start = bot.position();
        bot.getInventory().clearContent(); bot.getInventory().setItem(0, new ItemStack(Items.COBBLESTONE));
        var reports = new ArrayList<String>();
        JsonObject args = position(helper.absolutePos(new BlockPos(4, 1, 7))); args.addProperty("item", "cobblestone");
        call(bot, "place", args, reports);
        helper.succeedWhen(() -> {
            helper.assertTrue(reports.size() == 1 && reports.getFirst().contains("placed cobblestone at"), "report: " + reports);
            helper.assertBlockPresent(Blocks.COBBLESTONE, new BlockPos(4, 1, 7));
            helper.assertTrue(start.distanceToSqr(bot.position()) > 0.25, "walked into reach");
            BotManager.despawn("BnPlaceFloor");
        });
    }

    @GameTest(template = "arena", timeoutTicks = 180)
    public static void zombieDamageBurstProducesOneMoment(GameTestHelper helper) {
        TestArena.floor(helper);
        BotPlayer bot = TestArena.spawnBot(helper, "BnMomentDamage", new Vec3(4.5, 1, 4.5));
        bot.setHealth(20); bot.getFoodData().setFoodLevel(14);
        var zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(4, 1, 5)); zombie.setNoAi(true);
        zombie.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        float before = bot.getHealth();
        // ServerPlayer protects newly joined players from ordinary damage for 60 ticks.
        helper.runAfterDelay(65, () -> zombie.doHurtTarget(bot));
        helper.runAfterDelay(85, () -> zombie.doHurtTarget(bot));
        helper.succeedWhen(() -> {
            var damage = bot.moments().recent().stream().filter(e -> e.get("kind").getAsString().equals("damage")).toList();
            helper.assertTrue(damage.size() == 1, "one merged damage moment: " + damage);
            var moment = damage.getFirst();
            helper.assertTrue(moment.get("attacker").getAsString().equals("minecraft:zombie"), "attacker type");
            helper.assertTrue(moment.get("health_before").getAsFloat() == before, "initial health");
            helper.assertTrue(moment.get("health_after").getAsFloat() == bot.getHealth() && bot.getHealth() < before, "final health");
            helper.assertTrue(moment.getAsJsonObject("snapshot").has("health_before") && moment.getAsJsonObject("snapshot").has("health_after"), "snapshot health range");
            helper.assertTrue(helper.getTick() >= 125, "waited for merged damage window");
            zombie.discard(); BotManager.despawn("BnMomentDamage");
        });
    }

    private static JsonObject position(BlockPos pos) {
        JsonObject args = new JsonObject(); args.addProperty("x", pos.getX()); args.addProperty("y", pos.getY()); args.addProperty("z", pos.getZ()); return args;
    }
}

package dev.blocknet.gametest;

import dev.blocknet.Blocknet;
import dev.blocknet.body.BotManager;
import dev.blocknet.body.BotPlayer;
import dev.blocknet.state.ItemDescriber;
import dev.blocknet.state.ToolFacts;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(Blocknet.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BodyStateGameTests {
    private static final String FIXTURE_NAMESPACE = "ae2";
    private BodyStateGameTests() {}

    private static ItemStack wornPick(GameTestHelper helper) {
        ItemStack pick = new ItemStack(Items.IRON_PICKAXE);
        pick.setDamageValue(pick.getMaxDamage() - 12);
        pick.enchant(helper.getLevel().registryAccess().registryOrThrow(Registries.ENCHANTMENT).getHolderOrThrow(Enchantments.EFFICIENCY), 2);
        return pick;
    }

    @GameTest(template = "arena")
    public static void describesDurabilityFoodAndToolCapabilities(GameTestHelper helper) {
        var pick = ItemDescriber.describe(wornPick(helper));
        helper.assertTrue(pick.line().contains("12/250 almost broken") && pick.line().contains("efficiency 2")
            && pick.line().contains("mines up to diamond_ore, not obsidian"), pick.line());
        var bread = ItemDescriber.describe(new ItemStack(Items.BREAD));
        helper.assertTrue(bread.food().nutrition() == 5 && bread.line().contains("food 5"), bread.line());
        var axe = ItemDescriber.describe(new ItemStack(Items.WOODEN_AXE));
        helper.assertTrue(axe.kinds().contains("axe") && axe.attackDamage() == 7 && Math.abs(axe.attackSpeed() - .8) < .001,
            "wooden axe capabilities: " + axe.line());
        ItemStack helmet = new ItemStack(Items.IRON_HELMET);
        helper.assertTrue(ItemDescriber.describe(helmet).armor() == 2, "armor attributes");
        ItemStack threshold = new ItemStack(Items.IRON_PICKAXE);
        threshold.setDamageValue(188); // 62/250, below 25%, above 10% and 16 uses.
        helper.assertTrue(ItemDescriber.wear(threshold).equals("worn"), "worn threshold");
        threshold.setDamageValue(225);
        helper.assertTrue(ItemDescriber.wear(threshold).equals("worn"), "10% boundary is strict");
        threshold.setDamageValue(226);
        helper.assertTrue(ItemDescriber.wear(threshold).equals("almost broken"), "below 10%");
        Blocknet.LOGGER.info("Item facts sample: {}", pick.line());
        helper.succeed();
    }

    @GameTest(template = "arena")
    public static void describesTaggedModdedTool(GameTestHelper helper) {
        var tool = BuiltInRegistries.ITEM.entrySet().stream()
            .filter(e -> e.getKey().location().getNamespace().equals(FIXTURE_NAMESPACE))
            .map(e -> new ItemStack(e.getValue())).filter(s -> !ToolFacts.kinds(s).isEmpty()).findFirst();
        if (tool.isEmpty()) {
            Blocknet.LOGGER.info("SKIP describesTaggedModdedTool: fixture has no tagged tool");
        } else {
            var description = ItemDescriber.describe(tool.get());
            helper.assertTrue(description.id().startsWith(FIXTURE_NAMESPACE + ":") && !description.kinds().isEmpty(), description.line());
            Blocknet.LOGGER.info("Modded tool facts: {}", description.line());
        }
        helper.succeed();
    }

    @GameTest(template = "arena")
    public static void describesContainerAndBundleContents(GameTestHelper helper) {
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CUSTOM_NAME, Component.literal("Supplies"));
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.COAL, 8),
            new ItemStack(Items.BREAD, 3), new ItemStack(Items.DIRT, 6), new ItemStack(Items.STICK, 1))));
        var description = ItemDescriber.describe(box);
        helper.assertTrue(description.container().count() == 18 && description.container().topIds().equals(List.of("8 coal", "6 dirt", "3 bread"))
            && description.customName().equals("Supplies"), description.line());
        ItemStack bundle = new ItemStack(Items.BUNDLE);
        bundle.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(List.of(new ItemStack(Items.COAL, 4))));
        helper.assertTrue(ItemDescriber.describe(bundle).line().contains("bundle 4 items [4 coal]"), "bundle contents");
        helper.succeed();
    }

    @GameTest(template = "arena")
    public static void revisionsAndDeltasAreIndependent(GameTestHelper helper) {
        TestArena.floor(helper);
        BotPlayer bot = TestArena.spawnBot(helper, "BnStateRev", new Vec3(1.5, 1, 4.5));
        bot.getInventory().clearContent();
        bot.setHealth(bot.getMaxHealth());
        var tracker = bot.bodyState();
        var initial = tracker.snapshot();
        bot.getInventory().setItem(10, new ItemStack(Items.COAL, 3));
        var delta = tracker.since(initial.revisions());
        helper.assertTrue(delta.sections().keySet().equals(java.util.Set.of("inventory")), "coal only changes inventory: " + delta);
        helper.assertTrue(delta.inventoryDiff().equals(List.of("+ 3 coal")), "coal diff " + delta.inventoryDiff());
        var coal = tracker.snapshot();
        helper.assertTrue(coal.sections().get("inventory").revision() == initial.sections().get("inventory").revision() + 1, "one revision increment");
        helper.assertTrue(tracker.since(coal.revisions()).sections().isEmpty(), "unchanged snapshot gives empty delta");
        // Bypass the new player's spawn protection without waiting (and changing effect timers).
        bot.hurt(bot.damageSources().genericKill(), 2);
        helper.assertTrue(tracker.since(coal.revisions()).sections().keySet().equals(java.util.Set.of("body")), "damage only changes body");
        var damaged = tracker.snapshot();
        bot.getInventory().setItem(11, new ItemStack(Items.COAL, 2));
        tracker.snapshot();
        bot.getInventory().setItem(10, ItemStack.EMPTY);
        helper.assertTrue(tracker.since(coal.revisions()).inventoryDiff().equals(List.of("- coal")), "diff across intermediate snapshots");
        helper.assertTrue(tracker.since(damaged.revisions()).inventoryDiff().equals(List.of("- coal")), "same inventory baseline");
        bot.controller().goTo(helper.absolutePos(new BlockPos(2, 1, 4)), message -> {});
        var beforeTask = tracker.snapshot();
        bot.controller().stop();
        helper.assertTrue(tracker.since(beforeTask.revisions()).sections().keySet().equals(java.util.Set.of("task")), "task revision is independent");
        helper.assertTrue(tracker.since(Map.of()).inventoryReset(), "unknown baseline explicitly returns full inventory");
        bot.getInventory().setItem(0, wornPick(helper));
        var beforeWear = tracker.snapshot();
        bot.getInventory().getItem(0).setDamageValue(239);
        var wearDiff = tracker.since(beforeWear.revisions()).inventoryDiff();
        helper.assertTrue(wearDiff.size() == 2 && wearDiff.stream().anyMatch(s -> s.startsWith("- iron_pickaxe 12/250"))
            && wearDiff.stream().anyMatch(s -> s.startsWith("+ iron_pickaxe 11/250")), "tool line replacement: " + wearDiff);
        for (int i = 0; i < 65; i++) {
            bot.getInventory().selected = i % 9;
            tracker.snapshot();
        }
        helper.assertTrue(tracker.since(beforeWear.revisions()).inventoryReset(), "evicted revision explicitly requests full reset");
        BotManager.despawn("BnStateRev");
        helper.succeed();
    }

    @GameTest(template = "arena", timeoutTicks = 60)
    public static void missingToolBlocksBeforeMoving(GameTestHelper helper) {
        TestArena.floor(helper);
        BlockPos stone = new BlockPos(7, 1, 4);
        helper.setBlock(stone, Blocks.STONE);
        BotPlayer bot = TestArena.spawnBot(helper, "BnStateBlock", new Vec3(1.5, 1, 4.5));
        bot.getInventory().clearContent();
        Vec3 start = bot.position();
        List<String> reports = new ArrayList<>();
        bot.controller().mine(helper.absolutePos(stone), reports::add);
        helper.startSequence().thenExecuteAfter(20, () -> {
            helper.assertTrue(reports.equals(List.of("blocked: need a tool for minecraft:stone (pickaxe, at least wooden tier)")), "single blocked report: " + reports);
            helper.assertBlockPresent(Blocks.STONE, stone);
            helper.assertTrue(bot.position().distanceToSqr(start) < .01 && bot.controller().describe().startsWith("idle"), "bot stayed put");
            BotManager.despawn("BnStateBlock");
        }).thenSucceed();
    }

    @GameTest(template = "arena", timeoutTicks = 200)
    public static void wornCorrectToolStillMinesAndReportsOnce(GameTestHelper helper) {
        TestArena.floor(helper);
        BlockPos stone = new BlockPos(4, 1, 4);
        helper.setBlock(stone, Blocks.STONE);
        BotPlayer bot = TestArena.spawnBot(helper, "BnStateMine", new Vec3(1.5, 1, 4.5));
        bot.getInventory().clearContent();
        bot.getInventory().setItem(20, wornPick(helper));
        List<String> reports = new ArrayList<>();
        bot.controller().mine(helper.absolutePos(stone), reports::add);
        helper.succeedWhen(() -> {
            helper.assertBlockPresent(Blocks.AIR, stone);
            helper.assertTrue(reports.size() == 1 && reports.getFirst().startsWith("mined block")
                && reports.getFirst().endsWith("(tool almost broken)"), "one warning in terminal report: " + reports);
            helper.assertTrue(bot.getInventory().countItem(Items.COBBLESTONE) >= 1, "tool from main inventory earned drops");
            BotManager.despawn("BnStateMine");
        });
    }

    @GameTest(template = "arena")
    public static void commandsPrintSummaryAndKeepChangesBaseline(GameTestHelper helper) {
        TestArena.floor(helper);
        BotPlayer bot = TestArena.spawnBot(helper, "BnStateCmd", new Vec3(7.5, 1, 4.5));
        bot.getInventory().clearContent();
        bot.getInventory().setItem(0, wornPick(helper));
        bot.getInventory().setItem(9, new ItemStack(Items.BREAD, 3));
        bot.getInventory().setItem(10, new ItemStack(Items.COBBLESTONE, 16));
        bot.getInventory().setItem(11, new ItemStack(Items.COAL, 3));
        bot.getInventory().setItem(12, new ItemStack(Items.COAL, 2));
        List<String> reports = new ArrayList<>();
        var source = CommandGameTests.commandSource(helper, reports);
        var commands = helper.getLevel().getServer().getCommands();
        commands.performPrefixedCommand(source, "blocknet inv");
        helper.assertTrue(reports.size() == 1 && reports.getFirst().contains("summary:")
            && reports.getFirst().contains("best mining_tool/pickaxe iron_pickaxe 12/250 almost broken")
            && reports.getFirst().contains("food points 15, building blocks 16, free slots 31")
            && reports.getFirst().contains("5 coal slots [11, 12]"), "inventory command: " + reports);
        Blocknet.LOGGER.info("INV sample:\n{}", reports.getFirst());
        reports.clear();
        commands.performPrefixedCommand(source, "blocknet status BnStateCmd");
        helper.assertTrue(reports.size() == 2 && reports.getFirst().startsWith("body r") && reports.getLast().startsWith("task r"), "status sections");
        Blocknet.LOGGER.info("STATUS sample:\n{}", String.join("\n", reports));
        reports.clear();
        commands.performPrefixedCommand(source, "blocknet changes BnStateCmd");
        reports.clear();
        commands.performPrefixedCommand(source, "blocknet changes BnStateCmd");
        helper.assertTrue(reports.equals(List.of("no changes")), "stable command baseline: " + reports);
        bot.getInventory().setItem(13, new ItemStack(Items.COAL, 3));
        reports.clear();
        commands.performPrefixedCommand(source, "blocknet inv BnStateCmd");
        reports.clear();
        commands.performPrefixedCommand(source, "blocknet changes BnStateCmd");
        helper.assertTrue(reports.size() == 2 && reports.getFirst().startsWith("inventory r")
            && reports.getLast().equals("+ 3 coal"), "inv did not advance changes baseline: " + reports);
        bot.getInventory().setItem(13, ItemStack.EMPTY);
        reports.clear();
        commands.performPrefixedCommand(source.withPermission(0), "blocknet inv BnStateCmd");
        helper.assertTrue(reports.stream().noneMatch(s -> s.contains("summary:")), "op only");
        // Warm the JIT before measuring repeated on-demand captures; log evidence, no flaky time assertion.
        for (int i = 0; i < 20; i++) bot.bodyState().snapshot();
        long started = System.nanoTime();
        for (int i = 0; i < 100; i++) bot.bodyState().snapshot();
        Blocknet.LOGGER.info("BodyState capture: {} us mean (100 warm captures, 5 occupied slots); last {} us",
            (System.nanoTime() - started) / 100_000, bot.bodyState().lastCaptureNanos() / 1000);
        BotManager.despawn("BnStateCmd");
        helper.succeed();
    }
}

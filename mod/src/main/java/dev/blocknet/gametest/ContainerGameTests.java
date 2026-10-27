package dev.blocknet.gametest;

import dev.blocknet.Blocknet;
import dev.blocknet.body.BotManager;
import dev.blocknet.body.BotPlayer;
import dev.blocknet.crafting.CraftPlan;
import dev.blocknet.crafting.CraftingCalculation;
import dev.blocknet.crafting.ItemKey;
import dev.blocknet.control.CraftStations;
import dev.blocknet.memory.ContainerMemory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(Blocknet.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ContainerGameTests {
    private static final BlockPos CHEST = new BlockPos(4, 1, 5);

    private ContainerGameTests() {}

    private static BotPlayer bot(GameTestHelper helper, String name) {
        TestArena.floor(helper);
        BotPlayer bot = TestArena.spawnBot(helper, name, new Vec3(4.5, 1, 2.5));
        bot.getInventory().clearContent();
        // Test worlds can reuse player/SavedData files from a previous run.
        for (var entry : bot.containerMemory().entries()) {
            if (entry.location().dimension().equals(bot.level().dimension().location().toString())) {
                bot.containerMemory().forget(bot, BlockPos.of(entry.location().position()));
            }
        }
        return bot;
    }

    private static ChestBlockEntity chest(GameTestHelper helper) {
        helper.setBlock(CHEST, Blocks.CHEST);
        return (ChestBlockEntity) helper.getBlockEntity(CHEST);
    }

    private static ItemKey key(net.minecraft.world.item.Item item) {
        return new ItemKey(new ItemStack(item));
    }

    private static Map<ItemKey, Long> remembered(BotPlayer bot) {
        var result = new TreeMap<ItemKey, Long>();
        bot.containerMemory().entries().forEach(entry -> entry.contents().forEach((key, count) -> result.merge(key, count, Long::sum)));
        return result;
    }

    private static void report(GameTestHelper helper, BotPlayer bot, List<String> reports, String prefix) {
        helper.assertTrue(reports.size() == 1 && reports.getFirst().startsWith(prefix), "one report: " + reports);
        helper.assertTrue(bot.containerMenu == bot.inventoryMenu, "menu closed");
    }

    @GameTest(template = "arena", timeoutTicks = 350)
    public static void walksIntoReachAndRemembersOnlyMenuContents(GameTestHelper helper) {
        BotPlayer bot = bot(helper, "BnContainerLook");
        bot.teleportTo(helper.absoluteVec(new Vec3(1.5, 1, 1.5)).x,
            helper.absoluteVec(new Vec3(1.5, 1, 1.5)).y, helper.absoluteVec(new Vec3(1.5, 1, 1.5)).z);
        ChestBlockEntity chest = chest(helper);
        chest.setItem(0, new ItemStack(Items.COBBLESTONE, 32));
        ItemStack pick = new ItemStack(Items.IRON_PICKAXE);
        pick.setDamageValue(50);
        pick.set(DataComponents.CUSTOM_NAME, Component.literal("Remember me"));
        chest.setItem(1, pick);
        helper.assertTrue(!bot.canInteractWithBlock(helper.absolutePos(CHEST), -0.5), "starts out of reach");
        var start = bot.position();
        List<String> reports = new ArrayList<>();
        bot.controller().lookIn(helper.absolutePos(CHEST), reports::add);
        helper.succeedWhen(() -> {
            report(helper, bot, reports, "looked in ");
            helper.assertTrue(remembered(bot).equals(Map.of(key(Items.COBBLESTONE), 32L, new ItemKey(pick), 1L)), "exact component-aware memory");
            helper.assertTrue(bot.position().distanceToSqr(start) > 0.01 && helper.getTick() >= 4
                && bot.canInteractWithBlock(helper.absolutePos(CHEST), -0.5), "walked into reach at tick " + helper.getTick());
            Blocknet.LOGGER.info("Container memory sample: {}", bot.containerMemory().text(bot.getServer().overworld().getGameTime(), null));
            BotManager.despawn("BnContainerLook");
        });
    }

    @GameTest(template = "arena", timeoutTicks = 250)
    public static void depositJunkKeepsToolsFoodArmorAndPathingBlocks(GameTestHelper helper) {
        BotPlayer bot = bot(helper, "BnContainerJunk");
        ChestBlockEntity chest = chest(helper);
        bot.getInventory().add(new ItemStack(Items.IRON_PICKAXE));
        bot.getInventory().add(new ItemStack(Items.SHEARS));
        bot.getInventory().add(new ItemStack(Items.BREAD, 7));
        bot.getInventory().add(new ItemStack(Items.COBBLESTONE, 32));
        bot.getInventory().add(new ItemStack(Items.IRON_CHESTPLATE));
        bot.getInventory().add(new ItemStack(Items.ROTTEN_FLESH, 4)); // Food is kept, even poor food.
        bot.getInventory().add(new ItemStack(Items.FLINT, 12));
        List<String> reports = new ArrayList<>();
        bot.controller().deposit(helper.absolutePos(CHEST), "junk", 0, reports::add);
        helper.succeedWhen(() -> {
            report(helper, bot, reports, "deposited 12 flint into chest");
            helper.assertTrue(bot.getInventory().countItem(Items.FLINT) == 0 && chest.countItem(Items.FLINT) == 12, "junk totals");
            helper.assertTrue(bot.getInventory().countItem(Items.IRON_PICKAXE) == 1 && bot.getInventory().countItem(Items.BREAD) == 7
                && bot.getInventory().countItem(Items.COBBLESTONE) == 32 && bot.getInventory().countItem(Items.IRON_CHESTPLATE) == 1
                && bot.getInventory().countItem(Items.ROTTEN_FLESH) == 4 && bot.getInventory().countItem(Items.SHEARS) == 1,
                "kept essentials");
            helper.assertTrue(remembered(bot).equals(Map.of(key(Items.FLINT), 12L)), "memory updated after deposit");
            BotManager.despawn("BnContainerJunk");
        });
    }

    @GameTest(template = "arena", timeoutTicks = 200)
    public static void withdrawsExactCountFromSecondHalfOfDoubleChest(GameTestHelper helper) {
        BotPlayer bot = bot(helper, "BnDoubleChest");
        BlockPos second = CHEST.east();
        helper.setBlock(CHEST, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH)
            .setValue(ChestBlock.TYPE, ChestType.LEFT));
        helper.setBlock(second, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH)
            .setValue(ChestBlock.TYPE, ChestType.RIGHT));
        ChestBlockEntity right = (ChestBlockEntity) helper.getBlockEntity(second);
        right.setItem(0, new ItemStack(Items.IRON_INGOT, 10));
        List<String> reports = new ArrayList<>();
        bot.controller().withdraw(helper.absolutePos(CHEST), "minecraft:iron_ingot", 3, reports::add);
        helper.startSequence().thenWaitUntil(() -> {
            report(helper, bot, reports, "withdrew 3 iron_ingot");
            helper.assertTrue(bot.getInventory().countItem(Items.IRON_INGOT) == 3 && right.countItem(Items.IRON_INGOT) == 7, "exact totals");
            helper.assertTrue(helper.getTick() >= 15, "menu clicks take ticks");
            helper.assertTrue(remembered(bot).equals(Map.of(key(Items.IRON_INGOT), 7L)), "both halves remembered");
        }).thenExecute(() -> {
            reports.clear();
            bot.controller().lookIn(helper.absolutePos(second), reports::add);
        }).thenWaitUntil(() -> {
            report(helper, bot, reports, "looked in ");
            helper.assertTrue(bot.containerMemory().entries().size() == 1, "double chest is one entry from either side");
        }).thenExecute(() -> BotManager.despawn("BnDoubleChest")).thenSucceed();
    }

    @GameTest(template = "arena", timeoutTicks = 120)
    public static void fullChestFailsOnceWithoutLosingItems(GameTestHelper helper) {
        BotPlayer bot = bot(helper, "BnContainerFull");
        ChestBlockEntity chest = chest(helper);
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            chest.setItem(slot, new ItemStack(Items.DIRT, 64));
        }
        bot.getInventory().add(new ItemStack(Items.IRON_INGOT, 5));
        List<String> reports = new ArrayList<>();
        bot.controller().deposit(helper.absolutePos(CHEST), "all", 0, reports::add);
        helper.startSequence().thenWaitUntil(() -> report(helper, bot, reports, "failed: no room"))
            .thenIdle(12).thenExecute(() -> {
                report(helper, bot, reports, "failed: no room");
                helper.assertTrue(bot.getInventory().countItem(Items.IRON_INGOT) == 5 && chest.countItem(Items.DIRT) == 27 * 64
                    && chest.countItem(Items.IRON_INGOT) == 0, "nothing lost or changed");
                BotManager.despawn("BnContainerFull");
            }).thenSucceed();
    }

    @GameTest(template = "arena", timeoutTicks = 150)
    public static void mergesExistingStacksBeforeUsingLastEmptySlot(GameTestHelper helper) {
        BotPlayer bot = bot(helper, "BnContainerMerge");
        ChestBlockEntity chest = chest(helper);
        for (int slot = 2; slot < chest.getContainerSize(); slot++) {
            chest.setItem(slot, new ItemStack(Items.DIRT, 64));
        }
        chest.setItem(1, new ItemStack(Items.IRON_INGOT, 63));
        bot.getInventory().add(new ItemStack(Items.IRON_INGOT));
        bot.getInventory().add(new ItemStack(Items.GOLD_INGOT, 64));
        List<String> reports = new ArrayList<>();
        bot.controller().deposit(helper.absolutePos(CHEST), "all", 0, reports::add);
        helper.succeedWhen(() -> {
            report(helper, bot, reports, "deposited ");
            helper.assertTrue(chest.countItem(Items.IRON_INGOT) == 64 && chest.countItem(Items.GOLD_INGOT) == 64
                && bot.getInventory().countItem(Items.IRON_INGOT) == 0 && bot.getInventory().countItem(Items.GOLD_INGOT) == 0,
                "compatible stack merged before last empty slot");
            BotManager.despawn("BnContainerMerge");
        });
    }

    @GameTest(template = "arena", timeoutTicks = 200)
    public static void memoryStaysStaleUntilMenuOpensAgain(GameTestHelper helper) {
        BotPlayer bot = bot(helper, "BnContainerStale");
        ChestBlockEntity chest = chest(helper);
        chest.setItem(0, new ItemStack(Items.IRON_INGOT, 3));
        List<String> reports = new ArrayList<>();
        bot.controller().lookIn(helper.absolutePos(CHEST), reports::add);
        helper.startSequence().thenWaitUntil(() -> report(helper, bot, reports, "looked in "))
            .thenExecute(() -> chest.setItem(1, new ItemStack(Items.DIAMOND, 9))).thenIdle(12)
            .thenExecute(() -> {
                helper.assertTrue(remembered(bot).equals(Map.of(key(Items.IRON_INGOT), 3L)), "no live chest queries");
                helper.assertTrue(bot.containerMemory().whereIs(key(Items.DIAMOND)).equals("no remembered diamond"), "where uses memory");
                reports.clear();
                bot.controller().lookIn(helper.absolutePos(CHEST), reports::add);
            }).thenWaitUntil(() -> {
                report(helper, bot, reports, "looked in ");
                helper.assertTrue(remembered(bot).equals(Map.of(key(Items.IRON_INGOT), 3L, key(Items.DIAMOND), 9L)), "new observation");
            }).thenExecute(() -> BotManager.despawn("BnContainerStale")).thenSucceed();
    }

    @GameTest(template = "arena", timeoutTicks = 180)
    public static void memorySurvivesRespawnAndSavedDataSerialization(GameTestHelper helper) {
        BotPlayer bot = bot(helper, "BnContainerSave");
        ItemStack pick = new ItemStack(Items.IRON_PICKAXE);
        pick.setDamageValue(80);
        pick.set(DataComponents.CUSTOM_NAME, Component.literal("Saved pick"));
        chest(helper).setItem(0, pick);
        List<String> reports = new ArrayList<>();
        bot.controller().lookIn(helper.absolutePos(CHEST), reports::add);
        helper.startSequence().thenWaitUntil(() -> report(helper, bot, reports, "looked in "))
            .thenExecute(() -> {
                var memory = bot.containerMemory();
                CompoundTag saved = memory.save(new CompoundTag(), bot.registryAccess());
                var restored = ContainerMemory.load(saved, bot.registryAccess());
                helper.assertTrue(restored.entries().equals(memory.entries()), "all dimensions, ticks and components serialize");
                // Replace the storage cache with the deserialized value, simulating a fresh server load.
                bot.getServer().overworld().getDataStorage().set("blocknet_containers_" + bot.getUUID(), restored);
                BotManager.despawn("BnContainerSave");
                BotPlayer again = TestArena.spawnBot(helper, "BnContainerSave", new Vec3(4.5, 1, 2.5));
                helper.assertTrue(remembered(again).equals(Map.of(new ItemKey(pick), 1L)), "same name recovers memory");
                helper.assertTrue(again.containerMemory().whereIs(new ItemKey(pick)).contains(" in chest "), "brain query");
                BotManager.despawn("BnContainerSave");
            }).thenSucceed();
    }

    @GameTest(template = "arena", timeoutTicks = 350)
    public static void craftsWithWithdrawStepFromRememberedChest(GameTestHelper helper) {
        BotPlayer bot = bot(helper, "BnContainerCraft");
        ChestBlockEntity chest = chest(helper);
        chest.setItem(0, new ItemStack(Items.IRON_INGOT, 3));
        helper.setBlock(new BlockPos(3, 1, 4), Blocks.CRAFTING_TABLE);
        bot.getInventory().add(new ItemStack(Items.STICK, 2));
        List<String> reports = new ArrayList<>();
        bot.controller().lookIn(helper.absolutePos(CHEST), reports::add);
        helper.startSequence().thenWaitUntil(() -> report(helper, bot, reports, "looked in "))
            .thenExecute(() -> {
                var plan = new CraftingCalculation(bot.serverLevel(), dev.blocknet.crafting.PlannerCatalogs.get(bot.serverLevel()))
                    .plan(new ItemStack(Items.IRON_PICKAXE), 1, bot.getInventory().items,
                        CraftStations.visible(bot).keySet(), bot.containerMemory().sources(bot));
                helper.assertTrue(plan.status() == CraftPlan.Status.OK && plan.withdrawals().size() == 1
                    && plan.withdrawals().getFirst().count() == 3, plan.text());
                Blocknet.LOGGER.info("Container craft plan sample:\n{}", plan.text());
                reports.clear();
                bot.controller().craft(new ItemStack(Items.IRON_PICKAXE), 1, reports::add);
            }).thenWaitUntil(() -> {
                report(helper, bot, reports, "crafted 1 iron_pickaxe");
                helper.assertTrue(bot.getInventory().countItem(Items.IRON_PICKAXE) == 1 && chest.countItem(Items.IRON_INGOT) == 0, "craft consumed chest iron");
                helper.assertTrue(remembered(bot).isEmpty(), "memory remembers empty chest");
            }).thenExecute(() -> BotManager.despawn("BnContainerCraft")).thenSucceed();
    }

    @GameTest(template = "arena", timeoutTicks = 500)
    public static void replansOnceWhenRememberedChestWasEmptied(GameTestHelper helper) {
        BotPlayer bot = bot(helper, "BnContainerRetry");
        ChestBlockEntity near = chest(helper);
        near.setItem(0, new ItemStack(Items.IRON_INGOT, 3));
        BlockPos farPos = new BlockPos(6, 1, 6);
        helper.setBlock(farPos, Blocks.BARREL);
        var far = (net.minecraft.world.Container) helper.getBlockEntity(farPos);
        far.setItem(0, new ItemStack(Items.IRON_INGOT, 3));
        helper.setBlock(new BlockPos(3, 1, 4), Blocks.CRAFTING_TABLE);
        bot.getInventory().add(new ItemStack(Items.STICK, 2));
        List<String> reports = new ArrayList<>();
        bot.controller().lookIn(helper.absolutePos(farPos), reports::add);
        helper.startSequence().thenWaitUntil(() -> report(helper, bot, reports, "looked in "))
            .thenExecute(() -> {
                reports.clear();
                bot.controller().lookIn(helper.absolutePos(CHEST), reports::add);
            }).thenWaitUntil(() -> report(helper, bot, reports, "looked in "))
            .thenExecute(() -> {
                near.clearContent();
                reports.clear();
                bot.controller().craft(new ItemStack(Items.IRON_PICKAXE), 1, reports::add);
            }).thenWaitUntil(() -> {
                report(helper, bot, reports, "crafted 1 iron_pickaxe");
                helper.assertTrue(far.countItem(Items.IRON_INGOT) == 0 && remembered(bot).isEmpty(), "replanned from refreshed memory");
                helper.assertTrue(bot.getInventory().countItem(Items.IRON_PICKAXE) == 1, "craft succeeded after stale source");
            }).thenExecute(() -> BotManager.despawn("BnContainerRetry")).thenSucceed();
    }

    @GameTest(template = "arena", timeoutTicks = 220)
    public static void commandFiltersAndCountsUsePlayerMenu(GameTestHelper helper) {
        BotPlayer bot = bot(helper, "BnContainerCmd");
        ChestBlockEntity chest = chest(helper);
        bot.getInventory().add(new ItemStack(Items.IRON_INGOT, 8));
        var messages = new ArrayList<String>();
        var output = new net.minecraft.commands.CommandSource() {
            public void sendSystemMessage(Component message) { messages.add(message.getString()); }
            public boolean acceptsSuccess() { return true; }
            public boolean acceptsFailure() { return true; }
            public boolean shouldInformAdmins() { return false; }
        };
        var source = bot.createCommandSourceStack().withSource(output).withPermission(4);
        BlockPos pos = helper.absolutePos(CHEST);
        String coordinates = pos.getX() + " " + pos.getY() + " " + pos.getZ();
        var commands = helper.getLevel().getServer().getCommands();
        commands.performPrefixedCommand(source, "blocknet deposit BnContainerCmd " + coordinates + " #c:ingots/iron 3");
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(messages.size() == 1 && messages.getFirst().contains("deposited 3 iron_ingot"), "tag/count command: " + messages);
            helper.assertTrue(chest.countItem(Items.IRON_INGOT) == 3 && bot.getInventory().countItem(Items.IRON_INGOT) == 5, "exact deposit count");
        }).thenExecute(() -> {
            messages.clear();
            commands.performPrefixedCommand(source, "blocknet memory BnContainerCmd minecraft:iron_ingot");
            helper.assertTrue(messages.size() == 1 && messages.getFirst().contains("3 iron_ingot"), "memory command: " + messages);
            messages.clear();
            commands.performPrefixedCommand(source, "blocknet withdraw " + coordinates + " minecraft:iron_ingot");
        }).thenWaitUntil(() -> {
            helper.assertTrue(messages.size() == 1 && messages.getFirst().contains("withdrew 3 iron_ingot"), "nearest bot/default count command: " + messages);
            helper.assertTrue(chest.countItem(Items.IRON_INGOT) == 0 && bot.getInventory().countItem(Items.IRON_INGOT) == 8, "round trip totals");
        }).thenExecute(() -> BotManager.despawn("BnContainerCmd")).thenSucceed();
    }

    @GameTest(template = "arena", timeoutTicks = 150)
    public static void cancellationReturnsCursorAndReportsOnce(GameTestHelper helper) {
        BotPlayer bot = bot(helper, "BnContainerStop");
        ChestBlockEntity chest = chest(helper);
        chest.setItem(0, new ItemStack(Items.IRON_INGOT, 20));
        List<String> reports = new ArrayList<>();
        bot.controller().withdraw(helper.absolutePos(CHEST), "all", 3, reports::add);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(!bot.containerMenu.getCarried().isEmpty(), "pickup happened"))
            .thenExecute(() -> bot.controller().stop()).thenIdle(10).thenExecute(() -> {
                report(helper, bot, reports, "failed: cancelled");
                helper.assertTrue(chest.countItem(Items.IRON_INGOT) + bot.getInventory().countItem(Items.IRON_INGOT) == 20, "no lost cursor items");
                helper.assertTrue(remembered(bot).getOrDefault(key(Items.IRON_INGOT), 0L) == chest.countItem(Items.IRON_INGOT), "final memory");
                BotManager.despawn("BnContainerStop");
            }).thenSucceed();
    }

    @GameTest(template = "arena", timeoutTicks = 240)
    public static void forgetsRemovedContainerButRetainsLockedObservation(GameTestHelper helper) {
        BotPlayer bot = bot(helper, "BnContainerGone");
        ChestBlockEntity chest = chest(helper);
        chest.setItem(0, new ItemStack(Items.IRON_INGOT, 3));
        List<String> reports = new ArrayList<>();
        bot.controller().lookIn(helper.absolutePos(CHEST), reports::add);
        helper.startSequence().thenWaitUntil(() -> report(helper, bot, reports, "looked in "))
            .thenExecute(() -> {
                CompoundTag data = chest.saveWithFullMetadata(bot.registryAccess());
                data.putString("Lock", "locked fixture");
                chest.loadWithComponents(data, bot.registryAccess());
                reports.clear();
                bot.controller().lookIn(helper.absolutePos(CHEST), reports::add);
            }).thenWaitUntil(() -> {
                report(helper, bot, reports, "failed: ");
                helper.assertTrue(remembered(bot).equals(Map.of(key(Items.IRON_INGOT), 3L)), "lock does not reveal or erase old contents");
            }).thenExecute(() -> {
                helper.setBlock(CHEST, Blocks.STONE);
                reports.clear();
                bot.controller().lookIn(helper.absolutePos(CHEST), reports::add);
            }).thenWaitUntil(() -> {
                report(helper, bot, reports, "failed: ");
                helper.assertTrue(bot.containerMemory().entries().isEmpty(), "observed replacement forgotten");
            }).thenExecute(() -> BotManager.despawn("BnContainerGone")).thenSucceed();
    }

    @GameTest(template = "arena", timeoutTicks = 80)
    public static void plannerUsesInventoryThenNearestComponentAwareSource(GameTestHelper helper) {
        BotPlayer bot = bot(helper, "BnContainerPlan");
        helper.setBlock(CHEST, Blocks.CRAFTING_TABLE);
        bot.getInventory().add(new ItemStack(Items.STICK, 2));
        bot.getInventory().add(new ItemStack(Items.IRON_INGOT, 1));
        ItemStack named = new ItemStack(Items.IRON_INGOT);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Near iron"));
        String dimension = bot.level().dimension().location().toString();
        var near = new dev.blocknet.crafting.RememberedSource(dimension, helper.absolutePos(new BlockPos(3, 1, 3)).asLong(),
            "minecraft:barrel", Map.of(new ItemKey(named), 4L));
        var far = new dev.blocknet.crafting.RememberedSource(dimension, helper.absolutePos(new BlockPos(8, 1, 8)).asLong(),
            "minecraft:chest", Map.of(key(Items.IRON_INGOT), 10L));
        var plan = new CraftingCalculation(bot.serverLevel(), dev.blocknet.crafting.PlannerCatalogs.get(bot.serverLevel()))
            .plan(new ItemStack(Items.IRON_PICKAXE), 1, bot.getInventory().items, CraftStations.visible(bot).keySet(), List.of(near, far));
        helper.assertTrue(plan.status() == CraftPlan.Status.OK && plan.taken().getOrDefault(key(Items.IRON_INGOT), 0L) == 1
            && plan.withdrawals().size() == 1 && plan.withdrawals().getFirst().source().equals(near)
            && plan.withdrawals().getFirst().count() == 2 && plan.withdrawals().getFirst().item().equals(new ItemKey(named)), plan.text());
        BotManager.despawn("BnContainerPlan");
        helper.succeed();
    }

    @GameTest(template = "arena", timeoutTicks = 500)
    public static void furnaceExecutionRemembersInputsAndFinalEmptyMenu(GameTestHelper helper) {
        BotPlayer bot = bot(helper, "BnContainerCook");
        helper.setBlock(CHEST, Blocks.FURNACE);
        bot.getInventory().add(new ItemStack(Items.RAW_IRON));
        bot.getInventory().add(new ItemStack(Items.COAL));
        List<String> reports = new ArrayList<>();
        bot.controller().craft(new ItemStack(Items.IRON_INGOT), 1, reports::add);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(
            remembered(bot).getOrDefault(key(Items.RAW_IRON), 0L) == 1, "saw input through furnace menu"))
            .thenWaitUntil(() -> {
                report(helper, bot, reports, "crafted 1 iron_ingot");
                helper.assertTrue(bot.getInventory().countItem(Items.IRON_INGOT) == 1 && helper.getTick() >= 200, "real cooking");
                helper.assertTrue(bot.containerMemory().entries().size() == 1 && remembered(bot).isEmpty(), "final furnace contents remembered");
                helper.assertTrue(bot.containerMemory().entries().getFirst().blockId().equals("minecraft:furnace"), "observed station identity");
            }).thenExecute(() -> BotManager.despawn("BnContainerCook")).thenSucceed();
    }

    @GameTest(template = "arena", timeoutTicks = 120)
    public static void readOnlyOutputRefusesUnsafePartialWithdrawal(GameTestHelper helper) {
        BotPlayer bot = bot(helper, "BnContainerOut");
        helper.setBlock(CHEST, Blocks.FURNACE);
        var furnace = (net.minecraft.world.Container) helper.getBlockEntity(CHEST);
        furnace.setItem(2, new ItemStack(Items.IRON_INGOT, 10));
        List<String> reports = new ArrayList<>();
        bot.controller().withdraw(helper.absolutePos(CHEST), "minecraft:iron_ingot", 3, reports::add);
        helper.succeedWhen(() -> {
            report(helper, bot, reports, "failed: cannot split a read-only output slot");
            helper.assertTrue(furnace.countItem(Items.IRON_INGOT) == 10 && bot.getInventory().countItem(Items.IRON_INGOT) == 0,
                "no oversized cursor withdrawal from output-only slot");
            BotManager.despawn("BnContainerOut");
        });
    }

    @GameTest(template = "arena", timeoutTicks = 150)
    public static void hiddenRemovalDoesNotEraseRememberedContents(GameTestHelper helper) {
        BotPlayer bot = bot(helper, "BnContainerWall");
        chest(helper).setItem(0, new ItemStack(Items.IRON_INGOT, 3));
        List<String> reports = new ArrayList<>();
        bot.controller().lookIn(helper.absolutePos(CHEST), reports::add);
        helper.startSequence().thenWaitUntil(() -> report(helper, bot, reports, "looked in "))
            .thenExecute(() -> {
                helper.setBlock(CHEST, Blocks.AIR);
                for (int x = 0; x < 9; x++) {
                    for (int y = 1; y < 5; y++) {
                        helper.setBlock(new BlockPos(x, y, 4), Blocks.STONE);
                    }
                }
                reports.clear();
                bot.controller().lookIn(helper.absolutePos(CHEST), reports::add);
            }).thenWaitUntil(() -> {
                report(helper, bot, reports, "failed: cannot see container");
                helper.assertTrue(remembered(bot).equals(Map.of(key(Items.IRON_INGOT), 3L)), "no knowledge of hidden removal");
            }).thenExecute(() -> {
                for (int x = 0; x < 9; x++) {
                    for (int y = 1; y < 5; y++) {
                        helper.setBlock(new BlockPos(x, y, 4), Blocks.AIR);
                    }
                }
                reports.clear();
                bot.controller().lookIn(helper.absolutePos(CHEST), reports::add);
            }).thenWaitUntil(() -> {
                report(helper, bot, reports, "failed: not a container");
                helper.assertTrue(bot.containerMemory().entries().isEmpty(), "visible absence forgotten");
            }).thenExecute(() -> BotManager.despawn("BnContainerWall")).thenSucceed();
    }

    @GameTest(template = "arena", timeoutTicks = 200)
    public static void discoversModdedContainerByMenuSlots(GameTestHelper helper) {
        BotPlayer bot = bot(helper, "BnContainerMod");
        BlockPos absolute = helper.absolutePos(CHEST);
        net.minecraft.world.level.block.Block fixture = null;
        // Fixtures only: discover behavior, never hard-code a storage block name in the body.
        for (var block : BuiltInRegistries.BLOCK) {
            if (BuiltInRegistries.BLOCK.getKey(block).getNamespace().equals("minecraft")) {
                continue;
            }
            helper.setBlock(CHEST, block);
            bot.gameMode.useItemOn(bot, bot.serverLevel(), ItemStack.EMPTY, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(absolute), Direction.NORTH, absolute, false));
            boolean hasSlots = !ContainerMemory.containerSlots(bot).isEmpty();
            bot.closeContainer();
            if (hasSlots) {
                fixture = block;
                break;
            }
        }
        if (fixture == null) {
            Blocknet.LOGGER.info("Container fixture skipped: no modded block opens a menu with container slots");
            BotManager.despawn("BnContainerMod");
            helper.succeed();
            return;
        }
        String id = BuiltInRegistries.BLOCK.getKey(fixture).toString();
        List<String> reports = new ArrayList<>();
        bot.controller().lookIn(absolute, reports::add);
        helper.succeedWhen(() -> {
            report(helper, bot, reports, "looked in ");
            helper.assertTrue(bot.containerMemory().entries().size() == 1 && bot.containerMemory().entries().getFirst().blockId().equals(id), "modded menu remembered: " + id);
            Blocknet.LOGGER.info("Container fixture observed by behavior: {}", id);
            BotManager.despawn("BnContainerMod");
        });
    }
}

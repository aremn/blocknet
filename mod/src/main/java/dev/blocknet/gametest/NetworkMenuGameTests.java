// SPDX-License-Identifier: MIT
package dev.blocknet.gametest;

import dev.blocknet.Blocknet;
import dev.blocknet.body.BotManager;
import dev.blocknet.body.BotPlayer;
import dev.blocknet.control.MenuAccess;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * A storage-network terminal shows its stored contents through the generic menu section. The fixture
 * mod's network is assembled in the scene, the bot places the cable and terminal itself, and every
 * stored item gets into the network through the bot's own quick-move clicks.
 */
@GameTestHolder(Blocknet.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NetworkMenuGameTests {
    private static final String CABLE = "ae2:light_gray_smart_cable", TERMINAL = "ae2:terminal", CELL = "ae2:item_storage_cell_1k";
    private static final String CONTROLLER = "ae2:controller", DRIVE = "ae2:drive", ENERGY = "ae2:creative_energy_cell";
    private static final BlockPos SPOT = new BlockPos(4, 1, 4);

    private static Item item(String id) { return BuiltInRegistries.ITEM.get(ResourceLocation.parse(id)); }
    private static Block block(String id) { return BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id)); }
    private static boolean fixturePresent() {
        return BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(TERMINAL)) && BuiltInRegistries.BLOCK.containsKey(ResourceLocation.parse(DRIVE));
    }

    private static BotPlayer network(GameTestHelper h, String name, boolean powered) { return network(h, name, powered, true); }

    /** Controller and drive (with an empty cell when asked) beside the cable spot; power only when asked. */
    private static BotPlayer network(GameTestHelper h, String name, boolean powered, boolean cell) {
        TestArena.floor(h);
        h.setBlock(SPOT.south(), block(CONTROLLER));
        h.setBlock(SPOT.east(), block(DRIVE));
        if (powered) h.setBlock(SPOT.west(), block(ENERGY));
        var drive = h.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, h.absolutePos(SPOT.east()), null);
        if (cell) h.assertTrue(drive != null && drive.insertItem(0, new ItemStack(item(CELL)), false).isEmpty(), "cell inserted into drive");
        var bot = TestArena.spawnBot(h, name, new Vec3(4.5, 1, 2.5));
        bot.getInventory().clearContent();
        bot.getInventory().setItem(0, new ItemStack(item(CABLE), 2));
        bot.getInventory().setItem(1, new ItemStack(item(TERMINAL), 2));
        return bot;
    }

    private static void assemble(GameTestHelper h, BotPlayer bot, int[] stage, List<String> reports) { assemble(h, bot, stage, reports, true); }

    /** Cable, then the terminal on its north face, then (when asked) open it: stage 3 follows the attachment. */
    private static void assemble(GameTestHelper h, BotPlayer bot, int[] stage, List<String> reports, boolean open) {
        assemble(h, bot, stage, reports, open, bot.getInventory().countItem(item("ae2:pattern_encoding_terminal")) > 0
            ? "ae2:pattern_encoding_terminal" : TERMINAL);
    }

    private static void assemble(GameTestHelper h, BotPlayer bot, int[] stage, List<String> reports, boolean open, String terminal) {
        if (stage[0] == 0) {
            stage[0] = 1;
            bot.controller().place(new ItemStack(item(CABLE)), h.absolutePos(SPOT), Direction.UP, reports::add);
        } else if (stage[0] == 1 && reports.size() == 1) {
            h.assertTrue(reports.getFirst().startsWith("placed "), "cable placed: " + reports);
            stage[0] = 2;
            bot.controller().place(new ItemStack(item(terminal)), h.absolutePos(SPOT), Direction.NORTH, reports::add);
        } else if (stage[0] == 2 && reports.size() == 2) {
            h.assertTrue(reports.getLast().startsWith("attached "), "terminal attached: " + reports);
            stage[0] = 3;
            if (open) bot.controller().use(h.absolutePos(SPOT), reports::add);
        }
    }

    private static int playerSlot(BotPlayer bot, int inventoryIndex) {
        for (var slot : bot.containerMenu.slots)
            if (slot.container == bot.getInventory() && slot.getContainerSlot() == inventoryIndex) return slot.index;
        throw new IllegalStateException("no menu slot for inventory " + inventoryIndex);
    }

    @GameTest(template = "arena", timeoutTicks = 600)
    public static void terminalReportsExactNetworkStockAndKeepsComponentsApart(GameTestHelper h) {
        if (!fixturePresent()) { h.succeed(); return; }
        var bot = network(h, "BnNetStock", true);
        var named = new ItemStack(Items.IRON_INGOT, 3);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Kit"));
        bot.getInventory().setItem(2, new ItemStack(Items.COBBLESTONE, 32));
        bot.getInventory().setItem(3, named);
        bot.getInventory().setItem(4, new ItemStack(Items.IRON_INGOT, 5));
        List<String> reports = new ArrayList<>(), seen = new ArrayList<>();
        int[] stage = {0}, inventory = {2, 3, 4};
        int[] last = {-100}, clicks = {0};
        String[] done = {null};
        h.onEachTick(() -> {
            if (stage[0] < 3) { assemble(h, bot, stage, reports); return; }
            if (reports.size() < 3 || done[0] != null) return;
            h.assertTrue(bot.containerMenu != bot.inventoryMenu, "terminal open: " + reports);
            String menu = MenuAccess.snapshot(bot);
            seen.add(menu);
            h.assertTrue(menu.contains("slot "), "physical slots stay listed");
            if (stage[0] == 3) {
                // The grid boots over some ticks; before that the section never claims stock.
                h.assertTrue(!menu.contains("network ready"), "no stock before anything was inserted: " + menu);
                if (menu.contains("network empty")) stage[0] = 4;
            } else if (stage[0] == 4 && clicks[0] < inventory.length && h.getTick() - last[0] >= 3) {
                String clicked = MenuAccess.click(bot, bot.containerMenu.containerId, playerSlot(bot, inventory[clicks[0]]), 0, "quick_move");
                h.assertTrue(clicked.startsWith("clicked;"), "quick move accepted: " + clicked);
                last[0] = (int) h.getTick();
                if (++clicks[0] == inventory.length) stage[0] = 5;
            } else if (stage[0] == 5 && menu.contains("32 cobblestone") && menu.contains("; 5 iron_ingot") && menu.contains("3 iron_ingot{")) {
                done[0] = menu;
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(done[0] != null, "network shows inserted stock: " + (seen.isEmpty() ? reports : seen.getLast()));
            h.assertTrue(done[0].contains("stored kinds 3"), "named ingots are a separate kind: " + done[0]);
            h.assertTrue(bot.getInventory().countItem(Items.COBBLESTONE) == 0 && bot.getInventory().countItem(Items.IRON_INGOT) == 0,
                "items really left the inventory");
            String filtered = MenuAccess.snapshot(bot, "iron", null);
            h.assertTrue(filtered.contains("matching 2") && !filtered.contains("cobblestone"), "filter narrows the section: " + filtered);
            String stale = MenuAccess.snapshot(bot, null, bot.containerMenu.containerId + ".999.0");
            h.assertTrue(stale.contains("failed: stale network page"), "old page rejected: " + stale);
            bot.closeContainer();
            BotManager.despawn("BnNetStock");
        });
    }

    @GameTest(template = "arena", timeoutTicks = 400)
    public static void unpoweredTerminalIsDisconnectedNotEmpty(GameTestHelper h) {
        if (!fixturePresent()) { h.succeed(); return; }
        var bot = network(h, "BnNetUnpowered", false);
        List<String> reports = new ArrayList<>();
        int[] stage = {0}, opened = {-1};
        String[] menu = {""};
        h.onEachTick(() -> {
            if (stage[0] < 3) { assemble(h, bot, stage, reports); return; }
            if (reports.size() < 3) return;
            if (opened[0] < 0) opened[0] = (int) h.getTick();
            menu[0] = MenuAccess.snapshot(bot);
            h.assertTrue(!menu[0].contains("network empty") && !menu[0].contains("network ready"), "unpowered network is never empty: " + menu[0]);
        });
        h.succeedWhen(() -> {
            h.assertTrue(opened[0] >= 0 && h.getTick() - opened[0] >= 40, "watched the open terminal for two seconds");
            h.assertTrue(menu[0].contains("network disconnected") && menu[0].contains("contents unknown"), "disconnected: " + menu[0]);
            bot.closeContainer();
            BotManager.despawn("BnNetUnpowered");
        });
    }

    @GameTest(template = "arena", timeoutTicks = 2400)
    public static void depositsAndWithdrawsExactCountsThroughTerminal(GameTestHelper h) {
        if (!fixturePresent()) { h.succeed(); return; }
        var bot = network(h, "BnNetMove", true);
        bot.getInventory().setItem(2, new ItemStack(Items.COBBLESTONE, 64));
        bot.getInventory().setItem(3, new ItemStack(Items.COBBLESTONE, 6));
        List<String> reports = new ArrayList<>();
        int[] stage = {0};
        h.onEachTick(() -> {
            if (stage[0] < 3) { assemble(h, bot, stage, reports, false); return; }
            var pos = h.absolutePos(SPOT);
            if (stage[0] == 3) {
                // Started right after building: the task itself waits for the network to boot.
                stage[0] = 4;
                bot.controller().deposit(pos, "cobblestone", 40, reports::add);
            } else if (stage[0] == 4 && reports.size() == 3) {
                h.assertTrue(reports.getLast().startsWith("deposited 40 cobblestone into "), "deposit: " + reports);
                h.assertTrue(bot.getInventory().countItem(Items.COBBLESTONE) == 30, "exactly 40 left the inventory");
                stage[0] = 5;
                bot.controller().withdraw(pos, "cobblestone", 25, reports::add);
            } else if (stage[0] == 5 && reports.size() == 4) {
                h.assertTrue(reports.getLast().startsWith("withdrew 25 cobblestone"), "withdraw: " + reports);
                h.assertTrue(bot.getInventory().countItem(Items.COBBLESTONE) == 55, "exactly 25 came back");
                h.assertTrue(bot.containerMenu == bot.inventoryMenu && bot.containerMenu.getCarried().isEmpty(), "menu closed, cursor empty");
                stage[0] = 6;
                bot.controller().lookIn(pos, reports::add);
            } else if (stage[0] == 6 && reports.size() == 5) {
                h.assertTrue(reports.getLast().contains("network ready") && reports.getLast().contains("; 15 cobblestone"),
                    "the network keeps the rest: " + reports.getLast());
                // The remembered network is a planner source: a furnace takes 8 of its cobblestone.
                h.assertTrue(bot.containerMemory().sources(bot).stream().anyMatch(s -> s.contents().values().contains(15L)), "network remembered");
                bot.getInventory().clearContent();
                bot.getInventory().setItem(0, new ItemStack(Items.CRAFTING_TABLE));
                stage[0] = 7;
                bot.controller().craft(new ItemStack(Items.FURNACE), 1, reports::add);
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(stage[0] == 7 && reports.size() == 6, "craft finished: " + reports);
            h.assertTrue(reports.getLast().startsWith("crafted") && bot.getInventory().countItem(Items.FURNACE) == 1, "furnace: " + reports.getLast());
            h.assertTrue(bot.containerMemory().sources(bot).stream().anyMatch(s -> s.contents().values().contains(7L)),
                "8 cobblestone came out of the network: " + bot.containerMemory().text(0, null));
            BotManager.despawn("BnNetMove");
        });
    }

    @GameTest(template = "arena", timeoutTicks = 800)
    public static void networkWithoutStorageRefusesDepositAndKeepsItems(GameTestHelper h) {
        if (!fixturePresent()) { h.succeed(); return; }
        // Connected and powered, but the drive holds no cell: nothing can be stored.
        var bot = network(h, "BnNetNoCell", true, false);
        bot.getInventory().setItem(2, new ItemStack(Items.COBBLESTONE, 10));
        List<String> reports = new ArrayList<>();
        int[] stage = {0};
        h.onEachTick(() -> {
            if (stage[0] < 3) { assemble(h, bot, stage, reports, false); return; }
            if (stage[0] == 3) { stage[0] = 4; bot.controller().deposit(h.absolutePos(SPOT), "cobblestone", 10, reports::add); }
        });
        h.succeedWhen(() -> {
            h.assertTrue(reports.size() == 3, "deposit finished: " + reports);
            h.assertTrue(reports.getLast().startsWith("failed: network refused cobblestone"), "refusal reported: " + reports.getLast());
            h.assertTrue(bot.getInventory().countItem(Items.COBBLESTONE) == 10 && bot.containerMenu.getCarried().isEmpty(),
                "every item back in the inventory");
            BotManager.despawn("BnNetNoCell");
        });
    }

    @GameTest(template = "arena", timeoutTicks = 1200)
    public static void terminalFacingAwayOpensThroughAnotherPartOfTheCable(GameTestHelper h) {
        if (!fixturePresent()) { h.succeed(); return; }
        TestArena.floor(h);
        // The network sits in the floor so every side of the cable is free.
        BlockPos base = SPOT.below();
        h.setBlock(base, block(CONTROLLER));
        h.setBlock(base.east(), block(DRIVE));
        h.setBlock(base.west(), block(ENERGY));
        var drive = h.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, h.absolutePos(base.east()), null);
        h.assertTrue(drive != null && drive.insertItem(0, new ItemStack(item(CELL)), false).isEmpty(), "cell inserted into drive");
        var bot = TestArena.spawnBot(h, "BnNetBackside", new Vec3(4.5, 1, 2.5));
        bot.getInventory().clearContent();
        bot.getInventory().setItem(0, new ItemStack(item(CABLE)));
        bot.getInventory().setItem(1, new ItemStack(item(TERMINAL)));
        bot.getInventory().setItem(2, new ItemStack(Items.COBBLESTONE, 10));
        List<String> reports = new ArrayList<>();
        int[] stage = {0};
        h.onEachTick(() -> {
            if (stage[0] < 3) { assemble(h, bot, stage, reports, false); return; }
            if (stage[0] == 3) {
                // Walk round to the back: from here the first ray meets the bare cable, not the terminal.
                stage[0] = 4;
                var behind = h.absoluteVec(new Vec3(4.5, 1, 6.5));
                bot.teleportTo(behind.x, behind.y, behind.z);
                bot.controller().deposit(h.absolutePos(SPOT), "cobblestone", 10, reports::add);
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(reports.size() == 3, "deposit finished: " + reports);
            h.assertTrue(reports.getLast().startsWith("deposited 10 cobblestone"), "opened from behind: " + reports.getLast());
            h.assertTrue(bot.getInventory().countItem(Items.COBBLESTONE) == 0, "all of it went into the network");
            BotManager.despawn("BnNetBackside");
        });
    }

    @GameTest(template = "arena", timeoutTicks = 1600)
    public static void autocraftReportsShortageThenSubmitsThroughTerminal(GameTestHelper h) {
        if (!fixturePresent()) { h.succeed(); return; }
        TestArena.floor(h);
        BlockPos base = SPOT.below();
        h.setBlock(base, block(CONTROLLER));
        h.setBlock(base.east(), block(DRIVE));
        h.setBlock(base.west(), block(ENERGY));
        h.setBlock(base.south(), block("ae2:pattern_provider"));
        h.setBlock(base.north(), block("ae2:1k_crafting_storage"));
        var drive = h.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, h.absolutePos(base.east()), null);
        h.assertTrue(drive != null && drive.insertItem(0, new ItemStack(item(CELL)), false).isEmpty(), "cell inserted into drive");
        try {
            Class.forName("dev.blocknet.compat.ae2.Ae2TestFixtures")
                .getMethod("installPlanksPattern", net.minecraft.server.level.ServerLevel.class, BlockPos.class)
                .invoke(null, h.getLevel(), h.absolutePos(base.south()));
        } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
        var bot = TestArena.spawnBot(h, "BnNetCraft", new Vec3(4.5, 1, 2.5));
        bot.getInventory().clearContent();
        bot.getInventory().setItem(0, new ItemStack(item(CABLE)));
        bot.getInventory().setItem(1, new ItemStack(item(TERMINAL)));
        bot.getInventory().setItem(2, new ItemStack(Items.OAK_LOG));
        List<String> reports = new ArrayList<>();
        int[] stage = {0};
        h.onEachTick(() -> {
            if (stage[0] < 3) { assemble(h, bot, stage, reports, false); return; }
            var pos = h.absolutePos(SPOT);
            if (stage[0] == 3) {
                stage[0] = 4;
                bot.controller().autocraft(pos, "oak_planks", 4, reports::add);
            } else if (stage[0] == 4 && reports.size() == 3) {
                h.assertTrue(reports.getLast().startsWith("failed:") && reports.getLast().contains("1 oak_log"),
                    "an empty network names what it is short of: " + reports.getLast());
                stage[0] = 5;
                bot.controller().deposit(pos, "oak_log", 1, reports::add);
            } else if (stage[0] == 5 && reports.size() == 4) {
                h.assertTrue(reports.getLast().startsWith("deposited 1 oak_log"), "log stored: " + reports.getLast());
                stage[0] = 6;
                bot.controller().autocraft(pos, "oak_planks", 4, reports::add);
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(stage[0] == 6 && reports.size() == 5, "second request finished: " + reports);
            h.assertTrue(reports.getLast().startsWith("autocraft submitted: 4 oak_planks"), "job submitted: " + reports.getLast());
            h.assertTrue(bot.containerMenu == bot.inventoryMenu, "screens closed afterwards");
            BotManager.despawn("BnNetCraft");
        });
    }

    @GameTest(template = "arena", timeoutTicks = 2400)
    public static void encodesPatternStoresItInProviderThenAutocrafts(GameTestHelper h) {
        if (!fixturePresent()) { h.succeed(); return; }
        TestArena.floor(h);
        BlockPos base = SPOT.below();
        h.setBlock(base, block(CONTROLLER));
        h.setBlock(base.east(), block(DRIVE));
        h.setBlock(base.west(), block(ENERGY));
        h.setBlock(base.south(), block("ae2:pattern_provider"));
        h.setBlock(base.north(), block("ae2:1k_crafting_storage"));
        var drive = h.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, h.absolutePos(base.east()), null);
        h.assertTrue(drive != null && drive.insertItem(0, new ItemStack(item(CELL)), false).isEmpty(), "cell inserted into drive");
        var bot = TestArena.spawnBot(h, "BnNetPattern", new Vec3(4.5, 1, 2.5));
        bot.getInventory().clearContent();
        bot.getInventory().setItem(0, new ItemStack(item(CABLE)));
        bot.getInventory().setItem(1, new ItemStack(item("ae2:pattern_encoding_terminal")));
        bot.getInventory().setItem(2, new ItemStack(item("ae2:blank_pattern"), 2));
        bot.getInventory().setItem(3, new ItemStack(Items.OAK_PLANKS, 8));
        List<String> reports = new ArrayList<>();
        int[] stage = {0};
        h.onEachTick(() -> {
            // assemble() attaches the pattern terminal, since that is the one in the inventory.
            if (stage[0] < 3) { assemble(h, bot, stage, reports, false); return; }
            var pos = h.absolutePos(SPOT);
            if (stage[0] == 3) {
                h.assertTrue(dev.blocknet.control.BlockParts.of(h.getLevel(), pos).stream().anyMatch(p -> p.contains("pattern_encoding_terminal")),
                    "the terminal shows as a part of the cable: " + dev.blocknet.control.BlockParts.of(h.getLevel(), pos));
                stage[0] = 4;
                bot.controller().use(pos, reports::add);
            } else if (stage[0] == 4 && reports.size() == 3) {
                String encoded = MenuAccess.encodePattern(bot, "crafting_table");
                h.assertTrue(encoded.startsWith("encoded a crafting pattern for 1 crafting_table"), "encoded: " + encoded);
                // Like a player, the bot leaves the rest of the blank stack in the terminal's blank slot.
                h.assertTrue(bot.getInventory().countItem(item("ae2:crafting_pattern")) == 1, "the encoded pattern is in the inventory");
                bot.closeContainer();
                stage[0] = 5;
                bot.controller().deposit(h.absolutePos(base.south()), "ae2:crafting_pattern", 1, reports::add);
            } else if (stage[0] == 5 && reports.size() == 4) {
                h.assertTrue(reports.getLast().startsWith("deposited 1 ae2:crafting_pattern"), "pattern in provider: " + reports.getLast());
                stage[0] = 6;
                bot.controller().deposit(pos, "oak_planks", 8, reports::add);
            } else if (stage[0] == 6 && reports.size() == 5) {
                h.assertTrue(reports.getLast().startsWith("deposited 8 oak_planks"), "planks in network: " + reports.getLast());
                stage[0] = 7;
                bot.controller().autocraft(pos, "crafting_table", 1, reports::add);
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(stage[0] == 7 && reports.size() == 6, "chain finished: " + reports);
            h.assertTrue(reports.getLast().startsWith("autocraft submitted: 1 crafting_table"), "network crafts the table: " + reports.getLast());
            BotManager.despawn("BnNetPattern");
        });
    }
}

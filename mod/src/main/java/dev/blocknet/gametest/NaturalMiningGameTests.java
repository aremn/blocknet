// SPDX-License-Identifier: MIT
package dev.blocknet.gametest;

import dev.blocknet.Blocknet;
import dev.blocknet.body.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.network.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.*;
import net.neoforged.neoforge.network.connection.ConnectionType;

@GameTestHolder(Blocknet.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NaturalMiningGameTests {
    private static BotPlayer miner(GameTestHelper h, String name, int offset, Item pick) {
        var bot = TestArena.spawnBot(h, name, new Vec3(offset+16.5, NaturalTerrain.SURFACE, 4.5));
        bot.getInventory().clearContent();
        bot.getInventory().setItem(0, new ItemStack(pick));
        return bot;
    }
    private static int peak(GameTestHelper h) { return h.absolutePos(new BlockPos(0, NaturalTerrain.PEAK, 0)).getY(); }
    private static Consumer<BlockEvent.BreakEvent> trace(GameTestHelper h, BotPlayer bot, int offset, List<BlockPos> result) {
        Consumer<BlockEvent.BreakEvent> listener = event -> {
            if (event.getPlayer() == bot) result.add(event.getPos().subtract(h.absolutePos(BlockPos.ZERO)).offset(-offset, 0, 0));
        };
        NeoForge.EVENT_BUS.addListener(listener);
        return listener;
    }

    @GameTest(template = "natural_column", skyAccess = true, timeoutTicks = 2400)
    public static void obtainsStonePickaxeThroughNaturalSoil(GameTestHelper h) {
        NaturalTerrain.build(h, 0, false);
        var bot = miner(h, "BnSoilPick", 0, Items.WOODEN_PICKAXE);
        bot.setPos(h.absoluteVec(new Vec3(6.5,45,4.5)));
        bot.getInventory().setItem(9, new ItemStack(Items.OAK_PLANKS, 4));
        bot.getInventory().setItem(10, new ItemStack(Items.STICK, 2));
        var reports = new ArrayList<String>(); var dug = new ArrayList<BlockPos>();
        var listener = trace(h, bot, 0, dug);
        h.runAfterDelay(30, () -> bot.controller().obtain(Items.STONE_PICKAXE, 1, reports::add));
        h.succeedWhen(() -> {
            h.assertTrue(reports.size() == 1 && reports.getFirst().contains("obtained 1 stone_pickaxe"), reports + " task=" + bot.controller().describe() + " dug=" + dug);
            h.assertTrue(bot.getInventory().countItem(Items.STONE_PICKAXE) == 1, "crafted real pickaxe");
            h.assertTrue(bot.controller().depthContext().contains("surface y=" + h.absolutePos(new BlockPos(0,45,0)).getY()), "retained the grass surface after digging/crafting: " + bot.controller().depthContext());
            h.assertTrue(dug.stream().anyMatch(p -> p.getY() == 44) && dug.stream().anyMatch(p -> p.getY() == 40)
                && dug.stream().anyMatch(p -> p.getY() == 39), "broke grass, all four dirt layers, then stone: " + dug);
            h.assertTrue(h.getTick() > 200, "survival speed");
            NeoForge.EVENT_BUS.unregister(listener); BotManager.despawn("BnSoilPick");
        });
    }

    @GameTest(template = "natural_column", skyAccess = true, timeoutTicks = 11000)
    public static void minesIronPastWaterAndCaveAtScaledPeak(GameTestHelper h) {
        NaturalTerrain.build(h, 0, true);
        var bot = miner(h, "BnNaturalIron", 0, Items.IRON_PICKAXE);
        var reports = new ArrayList<String>(); var dug = new ArrayList<BlockPos>();
        var listener = trace(h, bot, 0, dug);
        h.runAfterDelay(30, () -> bot.controller().mineOre(Items.IRON_ORE, 3, peak(h), reports::add));
        h.succeedWhen(() -> {
            h.assertTrue(reports.size() == 1 && reports.getFirst().contains("mined 3 raw_iron at y=" + peak(h)), reports + " task=" + bot.controller().describe() + " pos=" + h.relativePos(bot.blockPosition()));
            h.assertTrue(bot.getInventory().countItem(Items.RAW_IRON) >= 3 && bot.getBlockY() <= peak(h)+1, "reached peak and picked up iron");
            h.assertTrue(dug.stream().anyMatch(p -> p.getX() != 16 && p.getY() > NaturalTerrain.PEAK+2), "shifted staircase around water");
            h.assertTrue(dug.stream().anyMatch(p -> p.getY() == NaturalTerrain.PEAK && p.getX() < 10), "side branches opened");
            h.assertTrue(reports.getFirst().matches(".*branches [1-9][0-9]*.*"), "branch schedule really ran: " + reports);
            h.assertTrue(!bot.isInWater() && bot.getHealth() == bot.getMaxHealth(), "no fluid or fall damage");
            Blocknet.LOGGER.info("NATURAL_MINE_ORE_SAMPLE ticks={} {}", h.getTick(), reports.getFirst());
            NeoForge.EVENT_BUS.unregister(listener); BotManager.despawn("BnNaturalIron");
        });
    }

    @GameTest(template = "natural_pair", skyAccess = true, timeoutTicks = 7000)
    public static void hiddenIronDoesNotChangeNaturalDescent(GameTestHelper h) {
        NaturalTerrain.build(h, 0, true); NaturalTerrain.build(h, 40, false);
        var a = miner(h, "BnNaturalLeakA", 0, Items.IRON_PICKAXE);
        var b = miner(h, "BnNaturalLeakB", 40, Items.IRON_PICKAXE);
        var dugA = new ArrayList<BlockPos>(); var dugB = new ArrayList<BlockPos>();
        var listenerA = trace(h, a, 0, dugA); var listenerB = trace(h, b, 40, dugB);
        h.runAfterDelay(30, () -> {
            a.controller().mineOre(Items.RAW_IRON, 3, peak(h), ignored -> {});
            b.controller().mineOre(Items.RAW_IRON, 3, peak(h), ignored -> {});
        });
        h.succeedWhen(() -> {
            int n = 45;
            h.assertTrue(dugA.size() >= n && dugB.size() >= n, "descent traces: " + dugA.size() + "/" + dugB.size() + " " + a.controller().describe() + " / " + b.controller().describe());
            h.assertTrue(dugA.subList(0,n).equals(dugB.subList(0,n)), "identical first " + n + " real breaks: " + dugA.subList(0,n) + " / " + dugB.subList(0,n));
            h.assertTrue(dugA.subList(0,n).stream().anyMatch(p -> p.getX() != 16), "comparison includes water bypass");
            h.assertTrue(a.getInventory().countItem(Items.RAW_IRON) == 0 && b.getInventory().countItem(Items.RAW_IRON) == 0, "before disclosure");
            Blocknet.LOGGER.info("NATURAL_LEAK_SAMPLE identical first {} dug blocks including fluid bypass", n);
            NeoForge.EVENT_BUS.unregister(listenerA); NeoForge.EVENT_BUS.unregister(listenerB);
            BotManager.despawn("BnNaturalLeakA"); BotManager.despawn("BnNaturalLeakB");
        });
    }

    @GameTest(template = "natural_column", skyAccess = true, timeoutTicks = 2200)
    public static void rememberedCliffRefWalksAndReobserves(GameTestHelper h) {
        NaturalTerrain.build(h, 0, false);
        for (int y = 45; y <= 47; y++) h.setBlock(new BlockPos(25,y,10), Blocks.STONE);
        var bot = miner(h, "BnRefCliff", 0, Items.STONE_PICKAXE);
        bot.setPos(h.absoluteVec(new Vec3(22.5,45,10.5)));
        var reports = new ArrayList<String>(); long[] seenAt = {0}; String[] ref = {""};
        boolean[] reobserved = {false};
        Consumer<BlockEvent.BreakEvent> evidence = event -> {
            if (event.getPlayer() != bot) return;
            var cell = bot.worldMemory().observed().cell(bot.level().dimension().location().toString(), event.getPos());
            if (cell != null && cell.tick() > seenAt[0]) reobserved[0] = true;
        };
        NeoForge.EVENT_BUS.addListener(evidence);
        int[] faceY = {45};
        long[] gatherAt = {Long.MAX_VALUE};
        boolean[] started = {false};
        h.onEachTick(() -> {
            if (started[0] || h.getTick() < 30) return;
            if (!ref[0].isEmpty()) {
                if (h.getTick() >= gatherAt[0]) { started[0] = true; bot.controller().gather(ref[0], 1, reports::add); }
                return;
            }
            var pos = new BlockPos(25, faceY[0], 10);
            TestArena.see(h, bot, pos);
            var cell = bot.worldMemory().observed().cell(bot.level().dimension().location().toString(), h.absolutePos(pos));
            if (cell != null && cell.stateId() >= 0 && faceY[0] < 47) faceY[0]++;
            // Neighbouring test structures can be seen first on a slow machine: take this fixture's cliff.
            var face = Vec3.atCenterOf(h.absolutePos(new BlockPos(25, 46, 10)));
            var found = bot.vision().objects().stream().filter(o -> o.category().equals("cliff") && o.bounds().inflate(0.5).contains(face)).findFirst();
            if (found.isEmpty()) return;
            var cliff = found.get();
            ref[0] = cliff.ref(); seenAt[0] = cliff.lastSeenTick();
            for (int z = 8; z <= 12; z++) for (int y = 45; y <= 47; y++) h.setBlock(new BlockPos(18,y,z), Blocks.DIRT);
            bot.setPos(h.absoluteVec(new Vec3(10.5,45,10.5)));
            gatherAt[0] = h.getTick()+10;
        });
        h.succeedWhen(() -> {
            h.assertTrue(reports.size() == 1 && reports.getFirst().matches("(?s).*gathered [1-9][0-9]* cobblestone.*"), reports + " " + bot.controller().describe());
            h.assertTrue(bot.getX() > h.absolutePos(new BlockPos(20,45,10)).getX(), "walked to remembered cliff");
            h.assertTrue(reobserved[0], "new eye evidence before breaking");
            NeoForge.EVENT_BUS.unregister(evidence);
            BotManager.despawn("BnRefCliff");
        });
    }

    @GameTest(template = "natural_column", skyAccess = true, timeoutTicks = 100)
    public static void stateIncludesSurfaceAndOreDepth(GameTestHelper h) {
        NaturalTerrain.build(h, 0, false);
        var bot = miner(h, "BnDepthHint", 0, Items.IRON_PICKAXE);
        bot.controller().mineOre(Items.IRON_ORE, 3, peak(h), ignored -> {});
        String line = bot.controller().scene().gist();
        String state = bot.bodyState().snapshot().sections().get("body").text();
        for (String text : List.of(line, state)) h.assertTrue(text.contains("y=" + bot.getBlockY())
            && text.contains("surface y=" + bot.getBlockY()) && text.contains("iron best ~" + peak(h))
            && text.contains("19 below"), text);
        BotManager.despawn("BnDepthHint"); h.succeed();
    }

    @GameTest(template = "natural_column", skyAccess = true, timeoutTicks = 8000)
    public static void emptyNaturalMineReturnsFromBothFullBranches(GameTestHelper h) {
        NaturalTerrain.build(h, 0, false);
        for (int y = NaturalTerrain.PEAK; y <= NaturalTerrain.PEAK+1; y++) h.setBlock(new BlockPos(16,y,4), Blocks.AIR);
        var bot = miner(h, "BnFullBranches", 0, Items.IRON_PICKAXE);
        bot.setPos(h.absoluteVec(new Vec3(16.5,NaturalTerrain.PEAK,4.5)));
        var reports = new ArrayList<String>(); var dug = new ArrayList<BlockPos>();
        var listener = trace(h, bot, 0, dug);
        h.runAfterDelay(30, () -> bot.controller().mineOre(Items.RAW_IRON, 3, peak(h), reports::add));
        h.succeedWhen(() -> {
            h.assertTrue(dug.contains(new BlockPos(4,NaturalTerrain.PEAK,7)) && dug.contains(new BlockPos(28,NaturalTerrain.PEAK,7))
                && dug.contains(new BlockPos(16,NaturalTerrain.PEAK,10)), "two twelve-block branches then resumed main: " + bot.controller().describe() + " reports=" + reports);
            h.assertTrue(bot.controller().describe().contains("found 0/3") && reports.isEmpty(), "no false completion in empty terrain");
            NeoForge.EVENT_BUS.unregister(listener); BotManager.despawn("BnFullBranches");
        });
    }

    @GameTest(template = "natural_column", skyAccess = true, timeoutTicks = 400)
    public static void sealsVisibleWaterSourceUsingInventoryBlock(GameTestHelper h) {
        NaturalTerrain.build(h, 0, false);
        var source = new BlockPos(6,44,5);
        h.setBlock(source, Blocks.WATER);
        var bot = miner(h, "BnSealSource", 0, Items.IRON_PICKAXE);
        bot.setPos(h.absoluteVec(new Vec3(6.5,45,4.5)));
        bot.getInventory().setItem(10, new ItemStack(Items.COBBLESTONE, 1));
        boolean[] placed = {false};
        Consumer<BlockEvent.EntityPlaceEvent> listener = event -> {
            if (event.getEntity() == bot && event.getPos().equals(h.absolutePos(source))) placed[0] = true;
        };
        NeoForge.EVENT_BUS.addListener(listener);
        h.runAfterDelay(30, () -> bot.controller().mineOre(Items.RAW_IRON, 3, h.absolutePos(new BlockPos(0,43,0)).getY(), ignored -> {}));
        h.succeedWhen(() -> {
            h.assertTrue(placed[0] && h.getBlockState(source).is(Blocks.COBBLESTONE), "placed a real inventory block into the source; " + bot.controller().describe());
            h.assertTrue(bot.getInventory().countItem(Items.COBBLESTONE) == 0 && !bot.isInWater(), "consumed the block without entering water");
            NeoForge.EVENT_BUS.unregister(listener); BotManager.despawn("BnSealSource");
        });
    }

    @GameTest(template = "natural_column", skyAccess = true, timeoutTicks = 700)
    public static void miningBroadcastsSwingAndVanillaCracks(GameTestHelper h) {
        NaturalTerrain.build(h, 0, false);
        h.setBlock(new BlockPos(16,45,7), Blocks.STONE);
        var bot = miner(h, "BnMinePackets", 0, Items.WOODEN_PICKAXE);
        var observer = TestArena.spawnBot(h, "BnMineWatcher", new Vec3(19.5,45,5.5));
        var original = observer.connection;
        var packets = new ArrayList<Packet<?>>();
        var cookie = new CommonListenerCookie(observer.getGameProfile(), 0, observer.clientInformation(), false, ConnectionType.OTHER);
        observer.connection = new ServerGamePacketListenerImpl(observer.getServer(), BotConnection.create(), observer, cookie) {
            @Override public void send(Packet<?> packet) { packets.add(packet); }
            @Override public void send(Packet<?> packet, PacketSendListener listener) { packets.add(packet); }
        };
        var reports = new ArrayList<String>();
        long[] started = {0};
        h.runAfterDelay(40, () -> { started[0] = h.getTick(); bot.controller().mine(h.absolutePos(new BlockPos(16,45,7)), reports::add); });
        h.succeedWhen(() -> {
            h.assertTrue(reports.size() == 1 && reports.getFirst().contains("mined block"), reports.toString());
            long swings = packets.stream().filter(p -> p instanceof ClientboundAnimatePacket a && a.getId() == bot.getId() && a.getAction() == 0).count();
            var stages = packets.stream().filter(p -> p instanceof ClientboundBlockDestructionPacket d && d.getId() == bot.getId())
                .map(p -> ((ClientboundBlockDestructionPacket)p).getProgress()).toList();
            h.assertTrue(swings >= 3, "repeated swings broadcast to observer: " + swings);
            h.assertTrue(stages.stream().filter(i -> i >= 0 && i <= 9).distinct().count() >= 4 && stages.contains(-1), "vanilla cracks and removal: " + stages);
            h.assertTrue(h.getTick()-started[0] >= 23, "wood pick survival time");
            observer.connection = original;
            BotManager.despawn("BnMineWatcher"); BotManager.despawn("BnMinePackets");
        });
    }
}

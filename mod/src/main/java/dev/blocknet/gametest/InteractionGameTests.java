// SPDX-License-Identifier: MIT
package dev.blocknet.gametest;

import com.google.gson.JsonObject;
import dev.blocknet.Blocknet;
import dev.blocknet.body.*;
import dev.blocknet.bridge.BridgeTools;
import dev.blocknet.debug.MemorySnapshot;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder(Blocknet.MOD_ID)
@PrefixGameTestTemplate(false)
public final class InteractionGameTests {
    private static final BlockPos FROM = new BlockPos(2, 1, 5), TO = new BlockPos(6, 1, 5);
    private static BotPlayer setup(GameTestHelper h, String name) {
        TestArena.floor(h);
        h.setBlock(FROM, Blocks.CHEST); h.setBlock(TO, Blocks.CHEST);
        var b = TestArena.spawnBot(h, name, new Vec3(4.5, 1, 2.5)); b.getInventory().clearContent();
        for (var entry : b.containerMemory().entries()) b.containerMemory().forget(b,BlockPos.of(entry.location().position()));
        dev.blocknet.memory.JobMemory.of(b).clearTransfer();return b;
    }
    private static String ref(GameTestHelper h, BotPlayer b, BlockPos p) {
        TestArena.see(h, b, p);
        return b.vision().objects().stream().filter(o -> o.nearest().equals(h.absolutePos(p))).findFirst().orElseThrow().ref();
    }
    private static ChestBlockEntity chest(GameTestHelper h, BlockPos p) { return (ChestBlockEntity) h.getBlockEntity(p); }
    private static void call(BotPlayer b, String tool, JsonObject args, List<String> reports) {
        BridgeTools.run(b, BridgeTools.get(tool), args, new HashMap<>(), reports::add);
    }
    private static JsonObject pair(GameTestHelper h, BotPlayer b) {
        JsonObject args = new JsonObject(); args.addProperty("source", ref(h,b,FROM)); args.addProperty("destination", ref(h,b,TO)); return args;
    }
    @GameTest(template = "arena", timeoutTicks = 100)
    public static void missingRawOreDoesNotExpandIntoCompressionCycle(GameTestHelper h) {
        var b=setup(h,"BnRawDeficit");
        var catalog=dev.blocknet.crafting.PlannerCatalogs.get(b.serverLevel());
        var plan=new dev.blocknet.crafting.CraftingCalculation(b.serverLevel(),catalog)
            .plan(new ItemStack(Items.RAW_IRON),3,List.of(new ItemStack(Items.RAW_IRON,2)),Set.of(),List.of(),dev.blocknet.crafting.CraftPlan.Mode.OBTAIN);
        h.assertTrue(plan.missing().getOrDefault("raw_iron",0L)==1,"one missing raw iron, not a compressed block: "+plan.text());
        BotManager.despawn("BnRawDeficit");h.succeed();
    }
    @GameTest(template = "arena", skyAccess = true, timeoutTicks = 900)
    public static void transferCarriesEveryKindInOneTripAndKeepsOwnItems(GameTestHelper h) {
        var b=setup(h,"BnHaulBatch");
        b.getInventory().clearContent();
        b.getInventory().setItem(0,new ItemStack(Items.IRON_PICKAXE));b.getInventory().setItem(1,new ItemStack(Items.COAL,5));
        var from=chest(h,FROM);
        from.setItem(0,new ItemStack(Items.COAL,10));from.setItem(1,new ItemStack(Items.STICK,7));from.setItem(2,new ItemStack(Items.ARROW,3));
        from.setItem(3,new ItemStack(Items.BEEF,2));from.setItem(4,new ItemStack(Items.IRON_INGOT,64));
        List<String> reports=new ArrayList<>();
        h.runAfterDelay(30,()->call(b,"transfer",pair(h,b),reports));
        h.succeedWhen(()->{
            h.assertTrue(reports.size()==1 && reports.getFirst().startsWith("transferred ") && reports.getFirst().contains("trips 1"),"one trip: "+reports);
            var to=chest(h,TO);
            h.assertTrue(to.countItem(Items.COAL)==10 && to.countItem(Items.STICK)==7 && to.countItem(Items.ARROW)==3
                && to.countItem(Items.BEEF)==2 && to.countItem(Items.IRON_INGOT)==64,"everything delivered");
            h.assertTrue(from.isEmpty(),"source empty");
            h.assertTrue(b.getInventory().countItem(Items.COAL)==5 && b.getInventory().countItem(Items.IRON_PICKAXE)==1,"own coal and pickaxe kept");
            BotManager.despawn("BnHaulBatch");
        });
    }
    @GameTest(template = "arena", skyAccess = true, timeoutTicks = 1200)
    public static void emptiedSourceByAnotherPlayerCannotProduceTransferSuccess(GameTestHelper h) {
        var b=setup(h,"BnHaulChanged");
        for(int i=0;i<35;i++) b.getInventory().setItem(i,new ItemStack(Items.STONE,64));
        b.getInventory().setItem(35,new ItemStack(Items.IRON_INGOT,3));
        chest(h,FROM).setItem(0,new ItemStack(Items.IRON_INGOT,64));chest(h,FROM).setItem(1,new ItemStack(Items.IRON_INGOT,64));
        List<String> reports=new ArrayList<>(); boolean[] changed={false};
        h.runAfterDelay(30,()->call(b,"transfer",pair(h,b),reports));
        h.onEachTick(()->{if(!changed[0] && b.getInventory().countItem(Items.IRON_INGOT)==64){changed[0]=true;chest(h,FROM).clearContent();}});
        h.succeedWhen(()->{
            h.assertTrue(reports.size()==1 && reports.getFirst().startsWith("failed:") && reports.getFirst().contains("source changed"),"no false success: "+reports);
            h.assertTrue(chest(h,TO).countItem(Items.IRON_INGOT)==61 && b.getInventory().countItem(Items.IRON_INGOT)==3,"delivered batch and owned amount");
            BotManager.despawn("BnHaulChanged");
        });
    }
    @GameTest(template = "arena", skyAccess = true, timeoutTicks = 500)
    public static void cancellationDuringMenuPickupReturnsCursorAndReportsOnce(GameTestHelper h) {
        var b=setup(h,"BnHaulCancel");chest(h,FROM).setItem(0,new ItemStack(Items.IRON_INGOT,9));
        List<String> reports=new ArrayList<>();long[] cancelled={-1};
        h.runAfterDelay(30,()->call(b,"transfer",pair(h,b),reports));
        h.onEachTick(()->{if(cancelled[0]<0 && !b.containerMenu.getCarried().isEmpty()){cancelled[0]=h.getTick();b.controller().stop();}});
        h.succeedWhen(()->{
            h.assertTrue(cancelled[0]>=0 && h.getTick()>cancelled[0]+10,"cancelled during actual click");
            h.assertTrue(reports.size()==1 && reports.getFirst().startsWith("failed:"),"one cancellation: "+reports);
            h.assertTrue(b.containerMenu==b.inventoryMenu && b.containerMenu.getCarried().isEmpty(),"cursor and menu restored");
            h.assertTrue(chest(h,FROM).countItem(Items.IRON_INGOT)+chest(h,TO).countItem(Items.IRON_INGOT)+b.getInventory().countItem(Items.IRON_INGOT)==9,"no items lost");
            BotManager.despawn("BnHaulCancel");
        });
    }
    @GameTest(template = "arena", skyAccess = true, timeoutTicks = 900)
    public static void interruptedCargoResumesWithoutTreatingItAsOwnedItems(GameTestHelper h) {
        var b=setup(h,"BnHaulResume");chest(h,FROM).setItem(0,new ItemStack(Items.IRON_INGOT,9));
        b.getInventory().add(new ItemStack(Items.IRON_INGOT,3));
        List<String> first=new ArrayList<>(),second=new ArrayList<>();long[] stopped={-1};boolean[] restarted={false};
        h.runAfterDelay(30,()->call(b,"transfer",pair(h,b),first));
        h.onEachTick(()->{
            if(stopped[0]<0 && b.getInventory().countItem(Items.IRON_INGOT)==12 && b.containerMenu.getCarried().isEmpty()) {
                stopped[0]=h.getTick();b.controller().stop();
            }
            if(stopped[0]>=0 && !restarted[0] && h.getTick()>stopped[0]+10) {
                restarted[0]=true;call(b,"transfer",pair(h,b),second);
            }
        });
        h.succeedWhen(()->{
            h.assertTrue(first.size()==1 && first.getFirst().startsWith("failed:"),"initial cancellation");
            h.assertTrue(second.size()==1 && second.getFirst().startsWith("transferred "),"resume: "+second);
            h.assertTrue(chest(h,TO).countItem(Items.IRON_INGOT)==9 && b.getInventory().countItem(Items.IRON_INGOT)==3,"cargo delivered, owned three retained");
            h.assertTrue(dev.blocknet.memory.JobMemory.of(b).transfer().isEmpty(),"completed job cleared");
            BotManager.despawn("BnHaulResume");
        });
    }
    @GameTest(template = "arena", skyAccess = true, timeoutTicks = 900)
    public static void resumeNormalizesBothHalvesOfDoubleChest(GameTestHelper h) {
        var b=setup(h,"BnHaulDouble");BlockPos other=FROM.east();
        h.setBlock(FROM,Blocks.CHEST.defaultBlockState().setValue(net.minecraft.world.level.block.ChestBlock.FACING,net.minecraft.core.Direction.NORTH)
            .setValue(net.minecraft.world.level.block.ChestBlock.TYPE,net.minecraft.world.level.block.state.properties.ChestType.LEFT));
        h.setBlock(other,Blocks.CHEST.defaultBlockState().setValue(net.minecraft.world.level.block.ChestBlock.FACING,net.minecraft.core.Direction.NORTH)
            .setValue(net.minecraft.world.level.block.ChestBlock.TYPE,net.minecraft.world.level.block.state.properties.ChestType.RIGHT));
        chest(h,other).setItem(0,new ItemStack(Items.IRON_INGOT,9));
        List<String> first=new ArrayList<>(),second=new ArrayList<>();long[] stopped={-1};boolean[] restarted={false};
        h.runAfterDelay(30,()->b.controller().transferContainers(h.absolutePos(FROM),h.absolutePos(TO),"h1 h2",first::add));
        h.onEachTick(()->{
            if(stopped[0]<0 && b.getInventory().countItem(Items.IRON_INGOT)==9 && b.containerMenu.getCarried().isEmpty()) {
                stopped[0]=h.getTick();b.controller().stop();
            }
            if(stopped[0]>=0 && !restarted[0] && h.getTick()>stopped[0]+10) {
                restarted[0]=true;b.controller().transferContainers(h.absolutePos(other),h.absolutePos(TO),"h1 h2",second::add);
            }
        });
        h.succeedWhen(()->{
            h.assertTrue(second.size()==1 && second.getFirst().startsWith("transferred "),"resume from other half: "+second);
            h.assertTrue(chest(h,TO).countItem(Items.IRON_INGOT)==9 && b.getInventory().countItem(Items.IRON_INGOT)==0,"same cargo job");
            BotManager.despawn("BnHaulDouble");
        });
    }
    @GameTest(template = "arena", skyAccess = true, timeoutTicks = 500)
    public static void lostSavedCargoCannotCompleteFromAnEmptySource(GameTestHelper h) {
        var b=setup(h,"BnHaulLost");chest(h,FROM).setItem(0,new ItemStack(Items.IRON_INGOT,9));
        List<String> first=new ArrayList<>(),second=new ArrayList<>();boolean[] interrupted={false};
        h.runAfterDelay(30,()->call(b,"transfer",pair(h,b),first));
        h.onEachTick(()->{
            if(!interrupted[0] && b.getInventory().countItem(Items.IRON_INGOT)==9 && b.containerMenu.getCarried().isEmpty()) {
                interrupted[0]=true;b.controller().stop();b.getInventory().clearContent();
                call(b,"transfer",pair(h,b),second);
            }
        });
        h.succeedWhen(()->{
            h.assertTrue(second.size()==1 && second.getFirst().contains("cargo is missing"),"lost cargo is a blocker: "+second);
            h.assertTrue(!dev.blocknet.memory.JobMemory.of(b).transfer().isEmpty(),"unfinished obligation retained");
            h.assertTrue(chest(h,TO).isEmpty(),"nothing was delivered");BotManager.despawn("BnHaulLost");
        });
    }
    @GameTest(template = "arena", skyAccess = true, timeoutTicks = 400)
    public static void playerHintRefResolvesWithoutBecomingEyeEvidence(GameTestHelper h) {
        var b=setup(h,"BnPointHint");chest(h,FROM).setItem(0,new ItemStack(Items.IRON_INGOT,5));
        List<String> reports=new ArrayList<>();
        h.runAfterDelay(30,()->{
            String p=dev.blocknet.bridge.HintTargets.issue(b,h.absolutePos(FROM));
            String dim=b.level().dimension().location().toString();var before=b.worldMemory().observed().evidence(dim);
            JsonObject a=new JsonObject();a.addProperty("ref",p);List<String> info=new ArrayList<>();call(b,"inspect",a,info);
            h.assertTrue(before.equals(b.worldMemory().observed().evidence(dim)),"hint lookup is not an eye scan");
            h.assertTrue(info.getFirst().contains("not eye evidence"),"provenance is explicit");
            a.addProperty("filter","minecraft:iron_ingot");a.addProperty("count",5);call(b,"withdraw",a,reports);
        });
        h.succeedWhen(()->{
            h.assertTrue(reports.size()==1 && reports.getFirst().startsWith("withdrew "),"physical menu action: "+reports);
            h.assertTrue(b.getInventory().countItem(Items.IRON_INGOT)==5,"real items");BotManager.despawn("BnPointHint");
        });
    }
    @GameTest(template = "arena", skyAccess = true, timeoutTicks = 2000)
    public static void observedRefsTransferMultipleTripsAndKeepOwnedItems(GameTestHelper h) {
        var b = setup(h,"BnRefHaul");
        for (int i=0;i<35;i++) b.getInventory().setItem(i,new ItemStack(Items.STONE,64));
        b.getInventory().setItem(35,new ItemStack(Items.IRON_INGOT,3));
        chest(h,FROM).setItem(0,new ItemStack(Items.IRON_INGOT,64)); chest(h,FROM).setItem(1,new ItemStack(Items.IRON_INGOT,32));
        List<String> reports = new ArrayList<>();
        h.runAfterDelay(30,()->call(b,"transfer",pair(h,b),reports));
        h.succeedWhen(()->{
            h.assertTrue(reports.size()==1 && reports.getFirst().startsWith("transferred "), "report " + reports + " " + b.controller().describe());
            h.assertTrue(chest(h,FROM).isEmpty() && chest(h,TO).countItem(Items.IRON_INGOT)==96,"exact cargo delivered");
            h.assertTrue(b.getInventory().countItem(Items.IRON_INGOT)==3 && b.getInventory().countItem(Items.STONE)==35*64,"owned inventory preserved");
            h.assertTrue(reports.getFirst().contains("trips 2"),"two trips: " + reports);
            BotManager.despawn("BnRefHaul");
        });
    }
    @GameTest(template = "arena", skyAccess = true, timeoutTicks = 400)
    public static void fullDestinationDoesNotWithdrawCargo(GameTestHelper h) {
        var b=setup(h,"BnHaulFull"); chest(h,FROM).setItem(0,new ItemStack(Items.IRON_INGOT,8));
        for(int i=0;i<27;i++) chest(h,TO).setItem(i,new ItemStack(Items.STONE,64));
        List<String> reports=new ArrayList<>();h.runAfterDelay(30,()->call(b,"transfer",pair(h,b),reports));
        h.succeedWhen(()->{
            h.assertTrue(reports.size()==1 && reports.getFirst().startsWith("failed:"),"failed once: "+reports);
            h.assertTrue(chest(h,FROM).countItem(Items.IRON_INGOT)==8 && b.getInventory().countItem(Items.IRON_INGOT)==0,"cargo stays at source");
            BotManager.despawn("BnHaulFull");
        });
    }
    @GameTest(template = "arena", skyAccess = true, timeoutTicks = 400)
    public static void lookInRefReportsOnlySelectedContainer(GameTestHelper h) {
        var b=setup(h,"BnRefInspect");chest(h,FROM).setItem(0,new ItemStack(Items.IRON_INGOT,8));chest(h,TO).setItem(0,new ItemStack(Items.DIAMOND,7));
        List<String> reports=new ArrayList<>();
        h.runAfterDelay(30,()->b.controller().lookIn(h.absolutePos(TO), ignored->{JsonObject a=new JsonObject();a.addProperty("ref",ref(h,b,FROM));call(b,"look-in",a,reports);}));
        h.succeedWhen(()->{
            h.assertTrue(reports.size()==1 && reports.getFirst().contains("8 iron_ingot") && !reports.getFirst().contains("diamond"),"selected contents: "+reports);
            BotManager.despawn("BnRefInspect");
        });
    }
    @GameTest(template = "arena", skyAccess = true, timeoutTicks = 200)
    public static void memorySnapshotDoesNotRevealOrScanHiddenBlocks(GameTestHelper h) {
        var b=setup(h,"BnMemSnapshot");
        h.runAfterDelay(30,()->{
            TestArena.see(h,b,FROM);
            String dim=b.level().dimension().location().toString();
            var before=b.worldMemory().observed().evidence(dim);
            JsonObject args=new JsonObject();args.addProperty("y",h.absolutePos(FROM).getY());args.addProperty("mode","slice");
            var first=MemorySnapshot.capture(b,args);
            h.setBlock(new BlockPos(2,1,6),Blocks.DIAMOND_BLOCK);
            var second=MemorySnapshot.capture(b,args);
            try { java.nio.file.Files.writeString(java.nio.file.Path.of("../../build/memory-view-sample.json"), second.toString()); }
            catch (java.io.IOException e) { throw new RuntimeException(e); }
            h.assertTrue(first.equals(second),"changing a hidden block does not affect snapshot");
            JsonObject voxelArgs=new JsonObject();voxelArgs.addProperty("mode","voxels");voxelArgs.addProperty("radius",16);
            var voxels=MemorySnapshot.capture(b,voxelArgs);
            h.setBlock(new BlockPos(2,1,6),Blocks.EMERALD_BLOCK);
            var voxelsAfter=MemorySnapshot.capture(b,voxelArgs);
            h.assertTrue(voxels.get("voxels").getAsJsonArray().size()>0,"voxels list remembered surface blocks");
            h.assertTrue(voxels.get("voxels").equals(voxelsAfter.get("voxels")) && voxels.get("palette").equals(voxelsAfter.get("palette")),"voxels ignore hidden blocks");
            h.assertTrue(!voxels.get("palette").toString().contains("diamond_block"),"voxels never name an unseen block");
            h.assertTrue(before.equals(b.worldMemory().observed().evidence(dim)),"snapshot does not change observations");
            BotManager.despawn("BnMemSnapshot");h.succeed();
        });
    }

    @GameTest(template = "arena", skyAccess = true, timeoutTicks = 500)
    public static void genericMenuClicksUseRealSlotsAndRejectStaleMenus(GameTestHelper h) {
        var b=setup(h,"BnMenuSlots");chest(h,FROM).setItem(0,new ItemStack(Items.IRON_INGOT,5));
        h.runAfterDelay(30,()->b.controller().use(h.absolutePos(FROM), ignored->{
            int id=b.containerMenu.containerId;
            h.assertTrue(dev.blocknet.control.MenuAccess.snapshot(b).contains("5 iron_ingot"),"open menu reports slots");
            String stale=dev.blocknet.control.MenuAccess.click(b,id+1,0,0,"quick_move");
            h.assertTrue(stale.startsWith("failed:"),"stale id rejected");
            String clicked=dev.blocknet.control.MenuAccess.click(b,id,0,0,"quick_move");
            h.assertTrue(clicked.startsWith("clicked;") && b.getInventory().countItem(Items.IRON_INGOT)==5,"real quick move");
            h.assertTrue(dev.blocknet.control.MenuAccess.click(b,id,0,0,"pickup").startsWith("blocked:"),"tick spacing");
            b.closeContainer(); BotManager.despawn("BnMenuSlots");h.succeed();
        }));
    }
    @GameTest(template = "arena", skyAccess = true, timeoutTicks = 400)
    public static void genericMachineMenuExposesSynchronizedCookingProgress(GameTestHelper h) {
        var b=setup(h,"BnMenuProgress");h.setBlock(FROM,Blocks.FURNACE);
        var furnace=(net.minecraft.world.level.block.entity.FurnaceBlockEntity)h.getBlockEntity(FROM);
        furnace.setItem(0,new ItemStack(Items.RAW_IRON));furnace.setItem(1,new ItemStack(Items.COAL));
        List<String> reports=new ArrayList<>();
        h.runAfterDelay(40,()->b.controller().use(h.absolutePos(FROM),r->reports.add(dev.blocknet.control.MenuAccess.snapshot(b))));
        h.succeedWhen(()->{
            h.assertTrue(reports.size()==1 && reports.getFirst().matches(".*data 2 = [1-9][0-9]*.*"),"real menu progress: "+reports);
            b.closeContainer();BotManager.despawn("BnMenuProgress");
        });
    }

    @GameTest(template = "arena", skyAccess = true, timeoutTicks = 2000)
    public static void observedOreSmeltsCraftsAndDeliversThroughPlayerActions(GameTestHelper h) {
        var b=setup(h,"BnWorkCycle");
        h.setBlock(new BlockPos(4,1,5),Blocks.IRON_ORE);
        h.setBlock(new BlockPos(5,1,2),Blocks.CRAFTING_TABLE);
        b.getInventory().add(new ItemStack(Items.STONE_PICKAXE));b.getInventory().add(new ItemStack(Items.COAL,1));
        b.getInventory().add(new ItemStack(Items.COBBLESTONE,8));b.getInventory().add(new ItemStack(Items.STICK,2));
        b.getInventory().add(new ItemStack(Items.RAW_IRON,2));
        List<String> reports=new ArrayList<>();boolean[] started={false};
        h.onEachTick(()->{
            if(started[0] || h.getTick()<30)return;
            TestArena.see(h,b,new BlockPos(4,1,5));TestArena.see(h,b,new BlockPos(5,1,2));
            var seenTable=b.worldMemory().observed().cell(b.level().dimension().location().toString(),h.absolutePos(new BlockPos(5,1,2)));
            var seenOre=b.worldMemory().observed().cell(b.level().dimension().location().toString(),h.absolutePos(new BlockPos(4,1,5)));
            if(seenTable==null || seenOre==null || seenTable.stateId()<0 || seenOre.stateId()<0)return;
            started[0]=true;
            b.controller().obtain(Items.IRON_PICKAXE,1,result->{
                if (!result.startsWith("obtained ")) { reports.add(result); return; }
                b.controller().deposit(h.absolutePos(TO),"minecraft:iron_pickaxe",1,reports::add);
            });
        });
        h.succeedWhen(()->{
            h.assertTrue(reports.size()==1 && reports.getFirst().startsWith("deposited "),"delivery: "+reports);
            h.assertTrue(chest(h,TO).countItem(Items.IRON_PICKAXE)==1,"crafted output delivered");
            h.assertTrue(b.getInventory().countItem(Items.COAL)==0 && b.getInventory().countItem(Items.RAW_IRON)==0 && h.getTick()>=600,"real smelting and input consumption");
            BotManager.despawn("BnWorkCycle");
        });
    }
}

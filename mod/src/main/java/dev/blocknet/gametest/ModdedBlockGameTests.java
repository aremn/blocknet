package dev.blocknet.gametest;

import dev.blocknet.Blocknet;
import dev.blocknet.body.BotManager;
import dev.blocknet.body.BotPlayer;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The generic body against blocks from another mod: nothing here is special-cased in the bot, the
 * tests only check that the player code paths work for modded content too.
 *
 * <p>Fixture blocks come from a test fixture mod on the dev runtime classpath (see
 * {@code mod/build.gradle}); any mod with a pickaxe block and a machine with a menu would do.
 * Without the fixture mod these tests pass trivially.
 */
@GameTestHolder(Blocknet.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ModdedBlockGameTests {
    /** A plain modded block that needs a pickaxe. */
    private static final String FIXTURE_BLOCK = "ae2:quartz_block";
    /** A modded machine that opens its own menu on right-click. */
    private static final String FIXTURE_MACHINE = "ae2:inscriber";

    private ModdedBlockGameTests() {}

    @GameTest(template = "arena", timeoutTicks = 200)
    public static void minesModdedBlock(GameTestHelper helper) {
        Optional<Block> block = TestArena.modBlock(FIXTURE_BLOCK);
        if (block.isEmpty()) {
            helper.succeed();
            return;
        }
        TestArena.floor(helper);
        BlockPos pos = new BlockPos(4, 1, 4);
        helper.setBlock(pos, block.get());
        BotPlayer bot = TestArena.spawnBot(helper, "BnModBlock", new Vec3(1.5, 1, 4.5));
        bot.getInventory().setItem(3, new ItemStack(Items.IRON_PICKAXE));
        bot.controller().mine(helper.absolutePos(pos), message -> {});
        helper.succeedWhen(() -> {
            helper.assertBlockNotPresent(block.get(), pos);
            helper.assertTrue(bot.getInventory().countItem(block.get().asItem()) >= 1, "bot picked up " + FIXTURE_BLOCK);
            BotManager.despawn("BnModBlock");
        });
    }

    /** Right-clicking a modded machine makes the server open the mod's menu for the bot, like for a player. */
    @GameTest(template = "arena", timeoutTicks = 200)
    public static void opensModdedMachineMenu(GameTestHelper helper) {
        Optional<Block> machine = TestArena.modBlock(FIXTURE_MACHINE);
        if (machine.isEmpty()) {
            helper.succeed();
            return;
        }
        TestArena.floor(helper);
        BlockPos pos = new BlockPos(4, 1, 4);
        helper.setBlock(pos, machine.get());
        BotPlayer bot = TestArena.spawnBot(helper, "BnModMenu", new Vec3(1.5, 1, 4.5));
        bot.controller().use(helper.absolutePos(pos), message -> {});
        String fixtureMod = ResourceLocation.parse(FIXTURE_MACHINE).getNamespace();
        helper.succeedWhen(() -> {
            helper.assertTrue(bot.containerMenu != bot.inventoryMenu, "bot has a menu open");
            ResourceLocation menu = BuiltInRegistries.MENU.getKey(bot.containerMenu.getType());
            helper.assertTrue(menu != null && menu.getNamespace().equals(fixtureMod), "open menu is from " + fixtureMod + ", got " + menu);
            bot.closeContainer();
            BotManager.despawn("BnModMenu");
        });
    }
}

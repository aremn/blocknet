package dev.blocknet.gametest;

import dev.blocknet.Blocknet;
import dev.blocknet.body.BotManager;
import dev.blocknet.body.BotPlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** {@code /blocknet} commands as a player (or the radial menu) sends them. */
@GameTestHolder(Blocknet.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CommandGameTests {
    private CommandGameTests() {}

    /** Without a name, a command goes to the nearest bot: coordinates must not be read as a bot name. */
    @GameTest(template = "arena", timeoutTicks = 200)
    public static void namelessMineGoesToNearestBot(GameTestHelper helper) {
        TestArena.floor(helper);
        BlockPos dirt = new BlockPos(4, 1, 4);
        helper.setBlock(dirt, Blocks.DIRT);
        BotPlayer bot = TestArena.spawnBot(helper, "BnNearest", new Vec3(1.5, 1, 4.5));
        BlockPos abs = helper.absolutePos(dirt);
        CommandSourceStack source = helper.getLevel().getServer().createCommandSourceStack()
            .withLevel(helper.getLevel())
            .withPosition(helper.absoluteVec(new Vec3(7.5, 1, 4.5)))
            .withSuppressedOutput();
        helper.getLevel().getServer().getCommands().performPrefixedCommand(source, "blocknet mine " + abs.getX() + " " + abs.getY() + " " + abs.getZ());
        helper.succeedWhen(() -> {
            helper.assertBlockPresent(Blocks.AIR, dirt);
            helper.assertTrue(bot.getInventory().countItem(Blocks.DIRT.asItem()) >= 1, "nearest bot mined the dirt");
            BotManager.despawn("BnNearest");
        });
    }
}

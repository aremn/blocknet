package dev.blocknet.gametest;

import dev.blocknet.Blocknet;
import dev.blocknet.body.BotManager;
import dev.blocknet.body.BotPlayer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** {@code /blocknet} commands as a player (or the radial menu) sends them. */
@GameTestHolder(Blocknet.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CommandGameTests {
    private CommandGameTests() {}

    @GameTest(template = "arena", timeoutTicks = 100)
    public static void followUsesCommandEntityAndStopReportsOnce(GameTestHelper helper) {
        TestArena.floor(helper);
        BotPlayer leader = TestArena.spawnBot(helper, "BnCmdLead", new Vec3(7.5, 1, 4.5));
        BotPlayer follower = TestArena.spawnBot(helper, "BnCmdFollow", new Vec3(1.5, 1, 4.5));
        List<String> reports = new ArrayList<>();
        CommandSourceStack source = commandSource(helper, reports).withEntity(leader);
        var commands = helper.getLevel().getServer().getCommands();
        commands.performPrefixedCommand(source, "blocknet follow BnCmdFollow");
        helper.startSequence()
            .thenExecuteAfter(10, () -> {
                helper.assertTrue(follower.controller().describe().equals("following BnCmdLead"), "command follows its entity");
                helper.assertTrue(reports.isEmpty(), "no terminal report at start");
                commands.performPrefixedCommand(source, "blocknet stop BnCmdFollow");
                commands.performPrefixedCommand(source, "blocknet stop BnCmdFollow");
            })
            .thenExecuteAfter(10, () -> {
                helper.assertTrue(reports.equals(List.of("[BnCmdFollow] stopped")), "one command report: " + reports);
                BotManager.despawn("BnCmdFollow");
                BotManager.despawn("BnCmdLead");
            })
            .thenSucceed();
    }

    @GameTest(template = "arena", timeoutTicks = 40)
    public static void followWithoutEntityFailsClearly(GameTestHelper helper) {
        TestArena.floor(helper);
        BotPlayer follower = TestArena.spawnBot(helper, "BnCmdNoEntity", new Vec3(1.5, 1, 4.5));
        List<String> reports = new ArrayList<>();
        helper.getLevel().getServer().getCommands().performPrefixedCommand(commandSource(helper, reports), "blocknet follow BnCmdNoEntity");
        helper.assertTrue(reports.size() == 1 && reports.getFirst().contains("Follow requires a command source with an entity"),
            "clear command error: " + reports);
        helper.assertTrue(follower.controller().describe().startsWith("idle"), "no task started");
        BotManager.despawn("BnCmdNoEntity");
        helper.succeed();
    }

    static CommandSourceStack commandSource(GameTestHelper helper, List<String> reports) {
        CommandSource output = new CommandSource() {
            @Override public void sendSystemMessage(Component message) { reports.add(message.getString()); }
            @Override public boolean acceptsSuccess() { return true; }
            @Override public boolean acceptsFailure() { return true; }
            @Override public boolean shouldInformAdmins() { return false; }
        };
        return helper.getLevel().getServer().createCommandSourceStack()
            .withSource(output).withLevel(helper.getLevel()).withPosition(helper.absoluteVec(new Vec3(7.5, 1, 4.5)));
    }

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

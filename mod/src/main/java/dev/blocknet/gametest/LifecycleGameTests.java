package dev.blocknet.gametest;

import dev.blocknet.Blocknet;
import dev.blocknet.body.BotManager;
import dev.blocknet.body.BotPlayer;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Bots moving at player speed and automatically respawning through vanilla player lifecycle. */
@GameTestHolder(Blocknet.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LifecycleGameTests {
    private LifecycleGameTests() {}

    /** Walking 5.5 blocks at player speed (~4.3 blocks/s) takes about 26 ticks; slow motion took ~90. */
    @GameTest(template = "arena", timeoutTicks = 200)
    public static void walksAtPlayerSpeed(GameTestHelper helper) {
        TestArena.floor(helper);
        BotPlayer bot = TestArena.spawnBot(helper, "BnSprinter", new Vec3(1.5, 1, 4.5));
        BlockPos goal = helper.absolutePos(new BlockPos(7, 1, 4));
        bot.controller().goTo(goal, message -> {});
        helper.succeedWhen(() -> {
            helper.assertTrue(bot.blockPosition().equals(goal), "bot reached the goal, is at " + bot.blockPosition().toShortString());
            helper.assertTrue(helper.getTick() <= 50, "walking 5.5 blocks took " + helper.getTick() + " ticks");
            BotManager.despawn("BnSprinter");
        });
    }

    /** Damage kills the old body; the tracked replacement respawns alive and takes orders. */
    @GameTest(template = "arena", timeoutTicks = 300)
    public static void respawnsAliveAfterDeath(GameTestHelper helper) {
        TestArena.floor(helper);
        BotPlayer first = TestArena.spawnBot(helper, "BnPhoenix", new Vec3(1.5, 1, 4.5));
        first.getInventory().clearContent();
        first.getInventory().add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND));
        first.setRespawnPosition(helper.getLevel().dimension(), first.blockPosition(), 0, true, false);
        first.hurt(first.damageSources().genericKill(), Float.MAX_VALUE);
        AtomicReference<BotPlayer> second = new AtomicReference<>();
        BlockPos goal = helper.absolutePos(new BlockPos(5, 1, 4));
        helper.runAfterDelay(25, () -> {
            BotPlayer bot = BotManager.get("BnPhoenix");
            helper.assertTrue(bot != null && bot != first && bot.isAlive(), "automatically respawned and tracked");
            helper.assertTrue(bot.getInventory().countItem(net.minecraft.world.item.Items.DIAMOND) == 0, "death drops inventory");
            bot.controller().goTo(goal, message -> {});
            second.set(bot);
        });
        helper.succeedWhen(() -> {
            BotPlayer bot = second.get();
            helper.assertTrue(bot != null && bot.isAlive() && bot.getHealth() > 0, "respawned bot is alive");
            helper.assertTrue(bot.blockPosition().equals(goal), "respawned bot walks, is at " + bot.blockPosition().toShortString());
            BotManager.despawn("BnPhoenix");
        });
    }

    /** After one bot dies, a nameless command goes to the living bot, not to the dead one. */
    @GameTest(template = "arena", timeoutTicks = 300)
    public static void namelessCommandSkipsDeadBot(GameTestHelper helper) {
        TestArena.floor(helper);
        BotPlayer dead = TestArena.spawnBot(helper, "BnFallen", new Vec3(6.5, 1, 6.5));
        BotPlayer alive = TestArena.spawnBot(helper, "BnSurvivor", new Vec3(1.5, 1, 1.5));
        dead.hurt(dead.damageSources().genericKill(), Float.MAX_VALUE);
        BlockPos goal = helper.absolutePos(new BlockPos(6, 1, 2));
        helper.runAfterDelay(2, () -> {
            CommandSourceStack source = helper.getLevel().getServer().createCommandSourceStack()
                .withLevel(helper.getLevel())
                .withPosition(helper.absoluteVec(new Vec3(7.5, 1, 7.5)))
                .withSuppressedOutput();
            helper.getLevel().getServer().getCommands().performPrefixedCommand(source, "blocknet goto " + goal.getX() + " " + goal.getY() + " " + goal.getZ());
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(alive.blockPosition().equals(goal), "the living bot took the order, is at " + alive.blockPosition().toShortString());
            BotManager.despawn("BnSurvivor");
            BotManager.despawn("BnFallen");
        });
    }
}

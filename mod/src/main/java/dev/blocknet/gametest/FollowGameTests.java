package dev.blocknet.gametest;

import dev.blocknet.Blocknet;
import dev.blocknet.body.BotManager;
import dev.blocknet.body.BotPlayer;
import dev.blocknet.pathing.api.IBaritone;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Long-running follow tasks, using a second real bot as the moving player. */
@GameTestHolder(Blocknet.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FollowGameTests {
    private FollowGameTests() {}

    @GameTest(template = "arena", timeoutTicks = 400)
    public static void followsWalkingLeaderAndWaitsWithoutJitter(GameTestHelper helper) {
        TestArena.floor(helper);
        BotPlayer leader = spawn(helper, "BnFollowLead", new Vec3(1.5, 1, 3.5));
        BotPlayer follower = spawn(helper, "BnFollowWalk", new Vec3(1.5, 1, 5.5));
        List<String> reports = new ArrayList<>();
        List<String> leaderReports = new ArrayList<>();
        AtomicReference<Vec3> resting = new AtomicReference<>();
        follower.controller().follow(leader, reports::add);
        leader.controller().goTo(helper.absolutePos(new BlockPos(7, 1, 3)), leaderReports::add);
        helper.startSequence()
            .thenWaitUntil(() -> {
                helper.assertTrue(leaderReports.size() == 1 && leaderReports.getFirst().startsWith("arrived"), "leader walked six blocks");
                assertNearAndWaiting(helper, follower, leader, reports);
            })
            .thenIdle(15)
            .thenExecute(() -> resting.set(follower.position()))
            .thenExecuteAfter(25, () -> {
                assertNearAndWaiting(helper, follower, leader, reports);
                helper.assertTrue(follower.position().distanceToSqr(resting.get()) < 0.001, "no jitter after leader stops");
                helper.assertTrue(follower.getViewVector(1).dot(leader.getEyePosition().subtract(follower.getEyePosition()).normalize()) > 0.95,
                    "looks at the leader while waiting");
                cleanup(leader, follower);
            })
            .thenSucceed();
    }

    @GameTest(template = "arena", timeoutTicks = 400)
    public static void stopCancelsMovingFollowAndLeaderMovesOn(GameTestHelper helper) {
        TestArena.floor(helper);
        BotPlayer leader = spawn(helper, "BnStopLead", new Vec3(5.5, 1, 4.5));
        BotPlayer follower = spawn(helper, "BnFollowStop", new Vec3(1.5, 1, 4.5));
        Vec3 start = follower.position();
        List<String> reports = new ArrayList<>();
        List<String> leaderReports = new ArrayList<>();
        AtomicReference<Vec3> resting = new AtomicReference<>();
        follower.controller().follow(leader, reports::add);
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(follower.position().distanceToSqr(start) > 0.1, "follower started walking"))
            .thenExecute(() -> {
                follower.controller().stop();
                follower.controller().stop();
                helper.assertTrue(reports.equals(List.of("stopped")), "one cancellation report: " + reports);
            })
            .thenExecuteAfter(15, () -> {
                resting.set(follower.position());
                leader.controller().goTo(helper.absolutePos(new BlockPos(7, 1, 7)), leaderReports::add);
            })
            .thenWaitUntil(() -> helper.assertTrue(!leaderReports.isEmpty(), "leader moved on"))
            .thenExecuteAfter(25, () -> {
                helper.assertTrue(follower.position().distanceToSqr(resting.get()) < 0.001, "stopped follower stays put");
                helper.assertTrue(follower.controller().describe().startsWith("idle"), "follow ended");
                helper.assertTrue(reports.equals(List.of("stopped")), "still one cancellation report");
                assertSettingsRestored(helper, follower);
                cleanup(leader, follower);
            })
            .thenSucceed();
    }

    @GameTest(template = "arena", timeoutTicks = 120)
    public static void despawnedLeaderReportsGoneExactlyOnce(GameTestHelper helper) {
        TestArena.floor(helper);
        BotPlayer leader = spawn(helper, "BnGoneLead", new Vec3(7.5, 1, 4.5));
        BotPlayer follower = spawn(helper, "BnFollowGone", new Vec3(1.5, 1, 4.5));
        List<String> reports = new ArrayList<>();
        follower.controller().follow(leader, reports::add);
        helper.startSequence()
            .thenExecuteAfter(10, () -> BotManager.despawn("BnGoneLead"))
            .thenExecuteAfter(20, () -> {
                helper.assertTrue(reports.equals(List.of("failed: BnGoneLead is gone")), "exactly one gone report: " + reports);
                helper.assertTrue(follower.controller().describe().startsWith("idle"), "follow ended");
                assertSettingsRestored(helper, follower);
                BotManager.despawn("BnFollowGone");
                helper.assertTrue(reports.size() == 1, "cleanup does not report twice");
            })
            .thenSucceed();
    }

    @GameTest(template = "arena_tall", timeoutTicks = 800)
    public static void getsNearLeaderOnTwoHighPillar(GameTestHelper helper) {
        TestArena.floor(helper);
        helper.setBlock(new BlockPos(7, 1, 4), Blocks.BEDROCK);
        helper.setBlock(new BlockPos(7, 2, 4), Blocks.BEDROCK);
        BotPlayer leader = spawn(helper, "BnPillarLead", new Vec3(7.5, 3, 4.5));
        BotPlayer follower = spawn(helper, "BnFollowPillar", new Vec3(1.5, 1, 4.5));
        List<String> reports = new ArrayList<>();
        // Let the leader land so come's on-ground rule uses the top of the pillar.
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(leader.onGround(), "leader standing on pillar"))
            .thenExecute(() -> follower.controller().follow(leader, reports::add))
            .thenWaitUntil(() -> assertNearAndWaiting(helper, follower, leader, reports))
            .thenExecuteAfter(25, () -> {
                assertNearAndWaiting(helper, follower, leader, reports);
                helper.assertTrue(follower.blockPosition().getY() == helper.absolutePos(new BlockPos(0, 1, 0)).getY(),
                    "follower waits on the ground beside the pillar");
                helper.assertBlockPresent(Blocks.BEDROCK, new BlockPos(7, 2, 4));
                cleanup(leader, follower);
            })
            .thenSucceed();
    }

    @GameTest(template = "arena", timeoutTicks = 150)
    public static void replacementTaskStopsFollowingExactlyOnce(GameTestHelper helper) {
        TestArena.floor(helper);
        BotPlayer leader = spawn(helper, "BnReplaceLead", new Vec3(7.5, 1, 4.5));
        BotPlayer follower = spawn(helper, "BnFollowReplace", new Vec3(1.5, 1, 4.5));
        List<String> reports = new ArrayList<>();
        List<String> replacementReports = new ArrayList<>();
        follower.controller().follow(leader, reports::add);
        helper.startSequence()
            .thenExecuteAfter(10, () -> follower.controller().goTo(helper.absolutePos(new BlockPos(1, 1, 7)), replacementReports::add))
            .thenWaitUntil(() -> helper.assertTrue(replacementReports.size() == 1, "replacement task finished"))
            .thenExecute(() -> {
                helper.assertTrue(reports.equals(List.of("stopped")), "one follow report after replacement: " + reports);
                helper.assertTrue(replacementReports.getFirst().startsWith("arrived"), "replacement route succeeds");
                assertSettingsRestored(helper, follower);
                cleanup(leader, follower);
            })
            .thenSucceed();
    }

    @GameTest(template = "arena", timeoutTicks = 100)
    public static void followerDeathStopsFollowingExactlyOnce(GameTestHelper helper) {
        TestArena.floor(helper);
        BotPlayer leader = spawn(helper, "BnDeathLead", new Vec3(7.5, 1, 4.5));
        BotPlayer follower = spawn(helper, "BnFollowDeath", new Vec3(1.5, 1, 4.5));
        List<String> reports = new ArrayList<>();
        follower.controller().follow(leader, reports::add);
        helper.startSequence()
            .thenExecuteAfter(10, () -> follower.hurt(follower.damageSources().genericKill(), Float.MAX_VALUE))
            .thenExecuteAfter(20, () -> {
                helper.assertTrue(BotManager.get("BnFollowDeath") == null, "follower died");
                helper.assertTrue(reports.equals(List.of("stopped")), "one follow report on death: " + reports);
                BotManager.despawn("BnDeathLead");
            })
            .thenSucceed();
    }

    @GameTest(template = "arena", timeoutTicks = 100)
    public static void leaderInAnotherDimensionReportsExactlyOnce(GameTestHelper helper) {
        TestArena.floor(helper);
        BotPlayer leader = spawn(helper, "BnDimLead", new Vec3(7.5, 1, 4.5));
        BotPlayer follower = spawn(helper, "BnFollowDim", new Vec3(1.5, 1, 4.5));
        List<String> reports = new ArrayList<>();
        follower.controller().follow(leader, reports::add);
        helper.startSequence()
            .thenExecuteAfter(10, () -> {
                var other = helper.getLevel().getServer().getLevel(Level.NETHER);
                leader.teleportTo(other, 0.5, 120, 0.5, 0, 0);
            })
            .thenExecuteAfter(10, () -> {
                helper.assertTrue(reports.equals(List.of("failed: BnDimLead is in another dimension")), "one dimension report: " + reports);
                assertSettingsRestored(helper, follower);
                cleanup(leader, follower);
            })
            .thenSucceed();
    }

    private static BotPlayer spawn(GameTestHelper helper, String name, Vec3 pos) {
        BotPlayer bot = TestArena.spawnBot(helper, name, pos);
        bot.getInventory().clearContent();
        bot.setHealth(bot.getMaxHealth());
        return bot;
    }

    private static void assertNearAndWaiting(GameTestHelper helper, BotPlayer follower, BotPlayer leader, List<String> reports) {
        helper.assertTrue(follower.position().distanceToSqr(leader.position()) <= 9, "within three blocks: " + follower.position());
        helper.assertTrue(follower.onGround() && follower.zza == 0 && follower.xxa == 0
            && !IBaritone.KEY.get(follower).getPathingBehavior().isPathing(), "waiting on ground without movement inputs");
        helper.assertTrue(follower.controller().describe().startsWith("following "), "follow remains active");
        helper.assertTrue(reports.isEmpty(), "no intermediate or arrival reports: " + reports);
    }

    private static void assertSettingsRestored(GameTestHelper helper, BotPlayer bot) {
        var settings = IBaritone.KEY.get(bot).settings();
        helper.assertTrue(!settings.allowPlace.get() && settings.maxFallHeightNoWater.get() == 3 && settings.allowSprint.get(),
            "navigation and sprint settings restored");
    }

    private static void cleanup(BotPlayer leader, BotPlayer follower) {
        BotManager.despawn(follower.getGameProfile().getName());
        BotManager.despawn(leader.getGameProfile().getName());
    }
}

// SPDX-License-Identifier: MIT
package dev.blocknet.debug;

import dev.blocknet.body.BotPlayer;
import dev.blocknet.control.BotController;
import dev.blocknet.pathing.api.IBaritone;
import dev.blocknet.pathing.api.pathing.calc.IPath;
import dev.blocknet.pathing.api.pathing.calc.IPathFinder;
import dev.blocknet.pathing.api.pathing.goals.Goal;
import dev.blocknet.pathing.api.pathing.goals.GoalBlock;
import dev.blocknet.pathing.api.pathing.goals.GoalComposite;
import dev.blocknet.pathing.api.pathing.goals.GoalGetToBlock;
import dev.blocknet.pathing.api.pathing.goals.GoalNear;
import dev.blocknet.pathing.api.pathing.path.IPathExecutor;
import dev.blocknet.pathing.api.utils.interfaces.IGoalRenderPos;
import dev.blocknet.pathing.pathing.path.PathExecutor;
import dev.blocknet.vision.ObjectGraph;
import dev.blocknet.vision.ObservedMap;
import dev.blocknet.vision.Semantics;
import dev.blocknet.vision.VisionSensor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

/** Builds one overlay frame of a bot on the server thread. Reads only the bot's own state and memory. */
final class DebugFrames {
    private static final int MAX_PATH = 400;
    private static final int MAX_SEEN = 1500;
    private static final int MAX_OBJECTS = 48;
    private static final int MAX_TRACE = 16;

    private DebugFrames() {}

    /** Objects and frontiers scan the whole observed map; viewers reuse them for a second. */
    record Scene(List<DebugPayload.Thing> objects, List<DebugPayload.Spot> frontiers, long tick) {}

    static Scene scene(BotPlayer bot) {
        VisionSensor vision = bot.vision();
        Vec3 position = bot.position();
        List<DebugPayload.Thing> objects = vision.objects().stream()
            .sorted(Comparator.comparingDouble(object -> position.distanceToSqr(object.center())))
            .limit(MAX_OBJECTS)
            .map(object -> new DebugPayload.Thing(cut(object.ref() + " " + object.kind() + " x" + object.knownMembers(), 120),
                DebugPayload.category(object.category()), object.provenance() == ObjectGraph.Provenance.REMEMBERED,
                object.bounds().minX, object.bounds().minY, object.bounds().minZ,
                object.bounds().maxX, object.bounds().maxY, object.bounds().maxZ))
            .toList();
        List<DebugPayload.Spot> frontiers = vision.frontiers().stream().limit(12)
            .map(frontier -> new DebugPayload.Spot(frontier.unknownStart(), cut(frontier.direction() + " "
                + Math.round(frontier.knownDistance()) + "m " + (frontier.caveMouth() ? "cave mouth"
                    : frontier.blocked() ? "blocked" : "unexplored"), 120)))
            .toList();
        return new Scene(objects, frontiers, bot.serverLevel().getGameTime());
    }

    static DebugPayload frame(BotPlayer bot, Scene scene) {
        BotController.DebugState state = bot.controller().debugState();
        VisionSensor vision = bot.vision();
        List<String> status = new ArrayList<>();
        status.add(String.format(Locale.ROOT, "%s  %d %d %d  hp %.0f/%.0f  food %d  holding %s", bot.getGameProfile().getName(),
            bot.getBlockX(), bot.getBlockY(), bot.getBlockZ(), bot.getHealth(), bot.getMaxHealth(),
            bot.getFoodData().getFoodLevel(), bot.getMainHandItem().isEmpty() ? "nothing"
                : BuiltInRegistries.ITEM.getKey(bot.getMainHandItem().getItem()).getPath()));
        status.add("doing: " + bot.controller().describe() + "  [" + state.task() + "]");
        status.add("route: " + state.route());
        if (!state.danger().isEmpty()) status.add("danger: " + state.danger());
        status.add("eyes: " + vision.mode() + ", " + Math.round(vision.range()) + "m; " + scene.objects().size()
            + " objects; " + bot.worldMemory().observed().cellCount() + " cells remembered");
        status.add("cost: " + bot.trace().costText());
        String brain = bot.trace().brain();
        if (!brain.isEmpty()) status.add("brain: " + brain);

        List<DebugPayload.Goal> goals = new ArrayList<>();
        goals(state.goal(), goals, 0);

        List<BlockPos> path = List.of(), toBreak = List.of(), toPlace = List.of(), planning = List.of();
        int pathIndex = 0;
        IBaritone pathing = IBaritone.KEY.getNullable(bot);
        if (pathing != null) {
            IPathExecutor current = pathing.getPathingBehavior().getCurrent();
            if (current != null) {
                path = positions(current.getPath());
                pathIndex = current.getPosition();
                if (current instanceof PathExecutor executor) {
                    toBreak = limit(executor.toBreak());
                    toPlace = limit(executor.toPlace());
                }
            }
            Optional<IPath> best = pathing.getPathingBehavior().getInProgress().flatMap(IPathFinder::bestPathSoFar);
            if (best.isPresent()) planning = positions(best.get());
        }

        List<DebugPayload.Seen> seen = new ArrayList<>();
        vision.recentHits(MAX_SEEN).forEach((pos, stateId) -> {
            String category = stateId == ObservedMap.DARK_STATE ? "dark"
                : Semantics.state(Block.stateById(stateId)).category();
            seen.add(new DebugPayload.Seen(BlockPos.of(pos), DebugPayload.category(category)));
        });

        List<DebugPayload.Mob> entities = vision.entities().stream().limit(24)
            .map(entity -> new DebugPayload.Mob(entity.position().x, entity.position().y, entity.position().z,
                cut((entity.name().isEmpty() ? entity.type() : entity.name()) + " "
                    + Math.round(bot.position().distanceTo(entity.position())) + "m", 120),
                DebugPayload.category(entity.category())))
            .toList();

        long tick = bot.getServer().getTickCount();
        List<DebugPayload.Line> trace = bot.trace().last(MAX_TRACE).stream()
            .map(entry -> new DebugPayload.Line(cut(entry.source(), 32), cut(entry.text(), 1000), (int) Math.max(0, tick - entry.tick()), entry.repeats()))
            .toList();

        status.replaceAll(line -> cut(line, 1000));
        return new DebugPayload(bot.getId(), bot.getGameProfile().getName(), status, state.target(), state.clearing(),
            goals, path, pathIndex, toBreak, toPlace, planning, state.dangerId(), vision.halfYaw(), vision.halfPitch(),
            (float) vision.range(), seen, scene.objects(), entities, scene.frontiers(), trace);
    }

    private static void goals(Goal goal, List<DebugPayload.Goal> out, int depth) {
        if (goal == null || depth > 3 || out.size() > 64) return;
        if (goal instanceof GoalComposite composite) {
            for (Goal child : composite.goals()) goals(child, out, depth + 1);
        } else if (goal instanceof GoalNear near) {
            out.add(new DebugPayload.Goal(near.getGoalPos(), Math.max(1, (int) Math.round(Math.sqrt(near.rangeSq())))));
        } else if (goal instanceof GoalGetToBlock reach) {
            out.add(new DebugPayload.Goal(reach.getGoalPos(), -1));
        } else if (goal instanceof GoalBlock block) {
            out.add(new DebugPayload.Goal(block.getGoalPos(), 0));
        } else if (goal instanceof IGoalRenderPos render) {
            out.add(new DebugPayload.Goal(render.getGoalPos(), 0));
        }
    }

    /** The codec rejects strings over its limits; long goals and notes are cut here instead. */
    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 3) + "...";
    }

    private static List<BlockPos> positions(IPath path) {
        List<BlockPos> result = new ArrayList<>();
        for (BlockPos pos : path.positions()) {
            if (result.size() >= MAX_PATH) break;
            result.add(pos.immutable());
        }
        return result;
    }

    private static List<BlockPos> limit(Collection<BlockPos> positions) {
        return positions.stream().limit(MAX_PATH).map(BlockPos::immutable).toList();
    }
}

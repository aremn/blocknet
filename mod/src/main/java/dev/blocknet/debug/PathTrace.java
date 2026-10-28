// SPDX-License-Identifier: MIT
package dev.blocknet.debug;

import dev.blocknet.body.BotPlayer;
import dev.blocknet.pathing.Pathing;
import dev.blocknet.pathing.api.IBaritone;
import dev.blocknet.pathing.api.event.events.PathEvent;
import dev.blocknet.pathing.api.event.listener.AbstractGameEventListener;
import dev.blocknet.pathing.api.pathing.calc.IPath;
import dev.blocknet.pathing.api.pathing.goals.Goal;
import dev.blocknet.pathing.api.pathing.path.IPathExecutor;
import java.util.Locale;

/** Puts the pathfinder's explanations and route events into the bot's trace. */
public final class PathTrace {
    private PathTrace() {}

    /** Once per game launch: the pathfinder's own debug and chat lines. */
    public static void install() {
        Pathing.setTrace((entity, message) -> {
            if (entity instanceof BotPlayer bot && !message.isBlank()) bot.trace().add("path", message);
        });
    }

    /** Once per pathfinder instance (spawn and respawn create one). */
    public static void attach(BotPlayer bot) {
        IBaritone pathing = IBaritone.KEY.get(bot);
        pathing.getGameEventHandler().registerEventListener(new AbstractGameEventListener() {
            @Override
            public void onPathEvent(PathEvent event) {
                bot.trace().add("path", describe(pathing, event));
            }
        });
    }

    private static String describe(IBaritone pathing, PathEvent event) {
        Goal goal = pathing.getPathingBehavior().getGoal();
        IPathExecutor current = pathing.getPathingBehavior().getCurrent();
        return switch (event) {
            case CALC_STARTED -> "planning a route to " + goal;
            case CALC_FINISHED_NOW_EXECUTING -> current == null ? "route found" : "route found: " + route(current.getPath(), goal);
            case CALC_FAILED -> "no route found to " + goal + " (the pathfinder gives up on this goal)";
            case NEXT_SEGMENT_CALC_STARTED -> "planning the next route segment while walking";
            case NEXT_SEGMENT_CALC_FINISHED -> "next segment ready";
            case CONTINUING_ONTO_PLANNED_NEXT -> "continuing onto the planned next segment";
            case SPLICING_ONTO_NEXT_EARLY -> "switched to the next segment early";
            case AT_GOAL -> "reached the goal " + goal;
            case PATH_FINISHED_NEXT_STILL_CALCULATING -> "segment finished, waiting for the next one (standing still)";
            case NEXT_CALC_FAILED -> "could not plan the next segment";
            case DISCARD_NEXT -> "dropped the next segment: it does not start where the bot is";
            case CANCELED -> "route cancelled";
        };
    }

    private static String route(IPath path, Goal goal) {
        boolean complete = goal != null && goal.isInGoal(path.getDest());
        return String.format(Locale.ROOT, "%d steps to %s, ~%.1f s, %s, %d nodes considered",
            path.movements().size(), path.getDest().toShortString(), path.ticksRemainingFrom(0) / 20.0,
            complete ? "reaches the goal" : "partial (goes as far as it could plan)", path.getNumNodesConsidered());
    }
}

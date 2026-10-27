package dev.blocknet.control;

import dev.blocknet.pathing.api.Settings;

/**
 * How bots move, on top of the pathfinder's own defaults. The goal is to move like a player would,
 * not like the cautious defaults Automatone ships for mobs.
 */
public final class PathingDefaults {
    private PathingDefaults() {}

    public static void apply(Settings settings) {
        // Jump gaps (sprint jumps up to 4 blocks) instead of walking around them.
        settings.allowParkour.set(true);
        settings.allowParkourAscend.set(true);
    }
}

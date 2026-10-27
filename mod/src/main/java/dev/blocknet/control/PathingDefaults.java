package dev.blocknet.control;

import dev.blocknet.pathing.api.Settings;

/**
 * How bots move, on top of the pathfinder's own defaults. The goal is to move like a player would,
 * not like the cautious defaults Automatone ships for mobs.
 */
public final class PathingDefaults {
    private PathingDefaults() {}

    public static void apply(Settings settings) {
        // Navigation enables placing immediately when inventory blocks are available.
        // Damaging drops remain a fallback; attempt settings are restored afterwards.
        settings.allowPlace.set(false);
        settings.maxFallHeightNoWater.set(3);
        // Movement costs already include walking/jumping. A click has a 4-tick cooldown
        // (5 ticks between clicks); add 3 ticks of preference against littering.
        settings.blockPlacementPenalty.set(8.0);
        // Added to real survival mining time: 5 ticks to retarget + 20 ticks against
        // wrecking terrain. Walk around rather than break a wall to save one second.
        // Protection of player-placed blocks is a separate future feature.
        settings.blockBreakAdditionalPenalty.set(25.0);
        // Jump gaps (sprint jumps up to 4 blocks) instead of walking around them.
        settings.allowParkour.set(true);
        settings.allowParkourAscend.set(true);
    }
}

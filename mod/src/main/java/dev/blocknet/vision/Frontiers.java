// SPDX-License-Identifier: MIT
package dev.blocknet.vision;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;

/** Ground-level evidence boundaries. No terrain, heightmap or loaded-world queries. */
public final class Frontiers {
    public record Frontier(String direction, double knownDistance, BlockPos unknownStart, boolean blocked, boolean caveMouth) {}
    private static final String[] DIRECTIONS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
    private Frontiers() {}
    public static String direction(Vec3 delta) {
        int sector = Math.floorMod((int) Math.round(Math.atan2(delta.x, -delta.z) / (Math.PI / 4)), 8);
        return DIRECTIONS[sector];
    }

    public static List<Frontier> find(ObservedMap map, String dimension, Vec3 feet, double range) {
        List<Frontier> result = new ArrayList<>();
        for (int sector = 0; sector < 8; sector++) {
            double angle = sector * Math.PI / 4;
            double dx = Math.sin(angle), dz = -Math.cos(angle);
            double known = 0;
            BlockPos unknown = BlockPos.containing(feet);
            boolean blocked = false;
            // Multiple nearby ground rays can cover a sector despite pixel jitter.
            for (int distance = 1; distance <= range; distance++) {
                BlockPos point = BlockPos.containing(feet.x + dx * distance, feet.y + .5, feet.z + dz * distance);
                boolean observed = false;
                for (int dy = 0; dy <= 1; dy++) {
                    BlockPos pos = point.above(dy);
                    var cell = map.cell(dimension, pos);
                    if (cell != null && (cell.stateId() < 0 || OpacityTable.classify(Block.stateById(cell.stateId()),
                            EmptyBlockGetter.INSTANCE, BlockPos.ZERO) == OpacityTable.OPAQUE)) blocked = true;
                    observed |= map.known(dimension, pos);
                }
                if (blocked) { known = distance; unknown = point; break; }
                if (!observed) { unknown = point; break; }
                known = distance;
                unknown = BlockPos.containing(feet.x + dx * (distance + 1), feet.y + .5, feet.z + dz * (distance + 1));
            }
            result.add(new Frontier(DIRECTIONS[sector], known, unknown, blocked, false));
        }
        Map<String, Frontier> caves = new LinkedHashMap<>();
        map.forEach(dimension, (pos, cell) -> {
            if (!map.caveMouth(dimension, pos)) return;
            String direction = direction(Vec3.atCenterOf(pos).subtract(feet));
            Frontier frontier = new Frontier(direction, feet.distanceTo(Vec3.atCenterOf(pos)), pos, false, true);
            caves.merge(direction, frontier, (a, b) -> a.knownDistance() < b.knownDistance() ? a : b);
        });
        result.addAll(caves.values());
        return result;
    }
}

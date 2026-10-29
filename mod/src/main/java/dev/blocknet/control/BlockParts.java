// SPDX-License-Identifier: MIT
package dev.blocknet.control;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Attachments a player sees on one block: a cable carrying a terminal and a bus is one block with
 * several parts. Mod adapters fill this in ({@link dev.blocknet.compat.Compat}); vanilla blocks have none.
 * Callers only ask about blocks the bot already sees or targets.
 */
public final class BlockParts {
    public interface Adapter {
        /** "side: item id" for each part on the block, or an empty list. */
        List<String> parts(Level level, BlockPos pos);
    }

    private static final List<Adapter> ADAPTERS = new CopyOnWriteArrayList<>();

    private BlockParts() {}

    public static void register(Adapter adapter) { ADAPTERS.add(adapter); }

    public static List<String> of(Level level, BlockPos pos) {
        // Never load or generate a chunk just to describe a remembered block.
        if (ADAPTERS.isEmpty() || !level.isLoaded(pos)) return List.of();
        for (var adapter : ADAPTERS) {
            var parts = adapter.parts(level, pos);
            if (!parts.isEmpty()) return parts;
        }
        return List.of();
    }

    /** " [parts: ...]" for a scene line, or empty. */
    public static String label(Level level, BlockPos pos) {
        var parts = of(level, pos);
        return parts.isEmpty() ? "" : " [parts: " + String.join(", ", parts) + "]";
    }
}

// SPDX-License-Identifier: MIT
package dev.blocknet.bridge;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Bounded references supplied by a player's pointing gesture, kept separate from eye memory. */
public final class HintTargets {
    public record Target(String player, String dimension, BlockPos position) {}
    private static final Map<MinecraftServer, LinkedHashMap<String, Target>> HINTS = new WeakHashMap<>();
    private static final Map<MinecraftServer, Long> SEQUENCES = new WeakHashMap<>();
    private HintTargets() {}
    public static String issue(ServerPlayer player, BlockPos pos) {
        var server=player.getServer();var hints=HINTS.computeIfAbsent(server,s->new LinkedHashMap<>());
        String ref="p"+SEQUENCES.merge(server,1L,Long::sum);
        hints.put(ref,new Target(player.getScoreboardName(),player.level().dimension().location().toString(),pos.immutable()));
        if(hints.size()>128)hints.remove(hints.keySet().iterator().next());return ref;
    }
    public static Target find(MinecraftServer server,String ref) {
        var hints=HINTS.get(server);return hints==null?null:hints.get(ref);
    }
}

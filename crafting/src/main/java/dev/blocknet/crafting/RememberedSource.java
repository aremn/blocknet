/* SPDX-License-Identifier: LGPL-3.0-or-later */
package dev.blocknet.crafting;

import java.util.Map;

/** Caller-sorted snapshots, nearest first. No world access or dependency on the body. */
public record RememberedSource(String dimension, long position, String blockId, Map<ItemKey, Long> contents) {
    public RememberedSource {
        contents = CraftPlan.immutable(contents);
    }

    public String description() {
        long x = position >> 38;
        long y = position << 52 >> 52;
        long z = position << 26 >> 38;
        return (blockId.startsWith("minecraft:") ? blockId.substring(10) : blockId) + " " + x + " " + y + " " + z;
    }
}

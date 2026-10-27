package dev.blocknet.control;

import dev.blocknet.body.BotPlayer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.BlastFurnaceBlock;
import net.minecraft.world.level.block.SmokerBlock;
import net.minecraft.world.level.block.SmithingTableBlock;
import net.minecraft.world.level.block.StonecutterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.Tags;

/** Station classification and remembered candidates; querying never looks around. */
public final class CraftStations {
    public static final Set<String> EXECUTABLE = Set.of("inventory", "crafting_table", "furnace", "blast_furnace", "smoker");

    private CraftStations() {}

    public static String kind(BlockState state) {
        if (state.is(Tags.Blocks.PLAYER_WORKSTATIONS_CRAFTING_TABLES)) {
            return "crafting_table";
        }
        if (state.getBlock() instanceof BlastFurnaceBlock) {
            return "blast_furnace";
        }
        if (state.getBlock() instanceof SmokerBlock) {
            return "smoker";
        }
        if (state.is(Tags.Blocks.PLAYER_WORKSTATIONS_FURNACES)) {
            return "furnace";
        }
        if (state.getBlock() instanceof StonecutterBlock) {
            return "stonecutter";
        }
        if (state.getBlock() instanceof SmithingTableBlock) {
            return "smithing_table";
        }
        return null;
    }

    public static Map<String, BlockPos> remembered(BotPlayer bot) {
        Map<String, BlockPos> stations = new LinkedHashMap<>();
        for (var entry : bot.worldMemory().nearby(bot, 24)) {
            if (!entry.kind().equals("container")) {
                stations.putIfAbsent(entry.kind(), entry.pos());
            }
        }
        return stations;
    }

    static Item item(String station) {
        return BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(station));
    }
}

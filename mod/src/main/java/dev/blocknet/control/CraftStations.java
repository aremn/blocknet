package dev.blocknet.control;

import dev.blocknet.body.BotPlayer;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** A bounded view query: only the first block hit by an eye ray can supply a station fact. */
public final class CraftStations {
    private static final int VIEW_DISTANCE = 6;
    private static final String[] STATIONS = {"crafting_table", "furnace", "blast_furnace", "smoker", "stonecutter", "smithing_table"};

    private CraftStations() {}

    public static Map<String, BlockPos> visible(BotPlayer bot) {
        Map<String, BlockPos> stations = new LinkedHashMap<>();
        Vec3 eye = bot.getEyePosition();
        for (BlockPos point : BlockPos.betweenClosed(bot.blockPosition().offset(-VIEW_DISTANCE, -2, -VIEW_DISTANCE),
                bot.blockPosition().offset(VIEW_DISTANCE, 3, VIEW_DISTANCE))) {
            Vec3 end = Vec3.atCenterOf(point);
            Vec3 delta = end.subtract(eye);
            if (delta.lengthSqr() > VIEW_DISTANCE * VIEW_DISTANCE
                || delta.normalize().dot(bot.getViewVector(1)) < 0.5) {
                continue;
            }
            var hit = bot.level().clip(new ClipContext(eye, end, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, bot));
            if (hit.getType() != HitResult.Type.BLOCK) {
                continue;
            }
            BlockPos pos = hit.getBlockPos();
            if (eye.distanceToSqr(Vec3.atCenterOf(pos)) > 16 && bot.level().getMaxLocalRawBrightness(pos.above()) < 4) {
                continue;
            }
            String id = BuiltInRegistries.BLOCK.getKey(bot.level().getBlockState(pos).getBlock()).toString();
            for (String station : STATIONS) {
                if (id.equals("minecraft:" + station)) {
                    BlockPos previous = stations.get(station);
                    if (previous == null || pos.distSqr(bot.blockPosition()) < previous.distSqr(bot.blockPosition())) {
                        stations.put(station, pos.immutable());
                    }
                }
            }
        }
        return stations;
    }

    static Item item(String station) {
        return BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(station));
    }
}

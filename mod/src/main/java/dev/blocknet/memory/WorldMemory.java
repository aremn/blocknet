package dev.blocknet.memory;

import dev.blocknet.body.BotPlayer;
import dev.blocknet.control.CraftStations;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.Container;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;

/** Eye observations only. Contents remain separate, menu-observed ContainerMemory snapshots. */
public final class WorldMemory extends SavedData {
    private static final long CONTENTS_STALE_TICKS = 20 * 60 * 5;

    public record Location(String dimension, long position) {}
    public record Entry(Location location, String blockId, String kind, boolean container, long seenTick) {
        public BlockPos pos() {
            return BlockPos.of(location.position());
        }

        public String description() {
            BlockPos pos = pos();
            return blockId.replace("minecraft:", "") + " " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
        }
    }

    private final Map<Location, Entry> entries = new LinkedHashMap<>();

    public static WorldMemory forBot(BotPlayer bot) {
        return bot.getServer().overworld().getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(WorldMemory::new, WorldMemory::load), "blocknet_world_" + bot.getUUID());
    }

    public List<Entry> nearby(BotPlayer bot, double radius) {
        String dimension = bot.level().dimension().location().toString();
        return entries.values().stream().filter(entry -> entry.location().dimension().equals(dimension)
            && bot.position().distanceToSqr(Vec3.atCenterOf(entry.pos())) <= radius * radius)
            .sorted(Comparator.comparingDouble(entry -> bot.position().distanceToSqr(Vec3.atCenterOf(entry.pos()))))
            .toList();
    }

    /** Reuse an action ray for perception, with VISUAL geometry regardless of its mechanics. */
    public static BlockHitResult clip(BotPlayer bot, Vec3 end, ClipContext.Block shape) {
        Vec3 eye = bot.getEyePosition();
        BlockHitResult hit = bot.level().clip(new ClipContext(eye, end, shape, ClipContext.Fluid.NONE, bot));
        Vec3 delta = end.subtract(eye);
        if (delta.normalize().dot(bot.getViewVector(1)) >= 0.5) {
            Vec3 visualEnd = delta.lengthSqr() > 16 * 16 ? eye.add(delta.normalize().scale(16)) : end;
            BlockHitResult visual = shape == ClipContext.Block.VISUAL && delta.lengthSqr() <= 16 * 16 ? hit
                : bot.level().clip(new ClipContext(eye, visualEnd, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, bot));
            bot.worldMemory().observeRay(bot, visualEnd, visual);
        }
        return hit;
    }

    private static boolean lit(BotPlayer bot, BlockPos pos) {
        return bot.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= 16
            || bot.level().getMaxLocalRawBrightness(pos.above()) >= 4;
    }

    private void observeRay(BotPlayer bot, Vec3 end, BlockHitResult hit) {
        Vec3 eye = bot.getEyePosition();
        Vec3 reached = hit.getType() == HitResult.Type.BLOCK ? hit.getLocation() : end;
        String dimension = bot.level().dimension().location().toString();
        // A ray through a remembered voxel proves its old block is gone. Occluded voxels stay remembered.
        boolean removed = entries.values().removeIf(entry -> {
            if (!entry.location().dimension().equals(dimension) || !lit(bot, entry.pos())
                || new AABB(entry.pos()).clip(eye, reached).isEmpty()) {
                return false;
            }
            String current = BuiltInRegistries.BLOCK.getKey(bot.level().getBlockState(entry.pos()).getBlock()).toString();
            if (!current.equals(entry.blockId())) {
                bot.containerMemory().forget(bot, entry.pos());
                return true;
            }
            return false;
        });
        if (removed) {
            setDirty();
        }
        if (hit.getType() != HitResult.Type.BLOCK || !lit(bot, hit.getBlockPos())) {
            return;
        }
        BlockPos pos = hit.getBlockPos();
        var state = bot.level().getBlockState(pos);
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        Location location = new Location(dimension, pos.asLong());
        Entry old = entries.get(location);
        if (old != null && !old.blockId().equals(blockId)) {
            bot.containerMemory().forget(bot, pos);
        }
        String kind = CraftStations.kind(state);
        boolean container = isContainer(bot, pos);
        if (kind == null && container) {
            kind = "container";
        }
        if (kind == null) {
            if (entries.remove(location) != null) {
                setDirty();
            }
        } else {
            entries.put(location, new Entry(location, blockId, kind, container, bot.getServer().overworld().getGameTime()));
            setDirty();
        }
    }

    private static boolean isContainer(BotPlayer bot, BlockPos pos) {
        var blockEntity = bot.level().getBlockEntity(pos);
        if (blockEntity == null) {
            return false;
        }
        if (blockEntity instanceof Container) {
            return true;
        }
        if (bot.level().getCapability(Capabilities.ItemHandler.BLOCK, pos, null) != null) {
            return true;
        }
        for (Direction side : Direction.values()) {
            if (bot.level().getCapability(Capabilities.ItemHandler.BLOCK, pos, side) != null) {
                return true;
            }
        }
        return false;
    }

    private String contents(BotPlayer bot, Entry entry) {
        return bot.containerMemory().entries().stream().filter(snapshot ->
            snapshot.location().dimension().equals(entry.location().dimension())
                && snapshot.positions().contains(entry.location().position()) && snapshot.blockId().equals(entry.blockId()))
            .findFirst().map(snapshot -> bot.getServer().overworld().getGameTime() - snapshot.seenTick() > CONTENTS_STALE_TICKS
                ? "stale" : "known").orElse("unknown");
    }

    public String looked(BotPlayer bot, long since) {
        String result = nearby(bot, 17).stream().filter(entry -> entry.seenTick() >= since)
            .map(entry -> entry.description() + " [" + entry.kind()
                + (entry.container() ? ", contents " + contents(bot, entry) : "") + "]")
            .collect(Collectors.joining("; "));
        return result.isEmpty() ? "looked: no stations or containers seen" : "looked: " + result;
    }

    public String uncheckedHint(BotPlayer bot) {
        String result = nearby(bot, 24).stream().filter(entry -> entry.container()
            && !contents(bot, entry).equals("known")).map(Entry::description).collect(Collectors.joining("; "));
        return result.isEmpty() ? "" : "; unchecked containers nearby: " + result;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Entry entry : entries.values()) {
            CompoundTag value = new CompoundTag();
            value.putString("dimension", entry.location().dimension());
            value.putLong("position", entry.location().position());
            value.putString("block", entry.blockId());
            value.putString("kind", entry.kind());
            value.putBoolean("container", entry.container());
            value.putLong("seen", entry.seenTick());
            list.add(value);
        }
        tag.put("world", list);
        return tag;
    }

    public static WorldMemory load(CompoundTag tag, HolderLookup.Provider registries) {
        WorldMemory memory = new WorldMemory();
        for (Tag raw : tag.getList("world", Tag.TAG_COMPOUND)) {
            CompoundTag value = (CompoundTag) raw;
            Location location = new Location(value.getString("dimension"), value.getLong("position"));
            memory.entries.put(location, new Entry(location, value.getString("block"), value.getString("kind"),
                value.getBoolean("container"), value.getLong("seen")));
        }
        return memory;
    }
}

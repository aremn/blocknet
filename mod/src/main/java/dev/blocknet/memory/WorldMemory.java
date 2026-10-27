package dev.blocknet.memory;

import dev.blocknet.body.BotPlayer;
import dev.blocknet.vision.ObservedMap;
import dev.blocknet.vision.Semantics;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

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

    private final ObservedMap observed = new ObservedMap(this::setDirty);

    public ObservedMap observed() {
        return observed;
    }

    public static WorldMemory forBot(BotPlayer bot) {
        return bot.getServer().overworld().getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(WorldMemory::new, WorldMemory::load), "blocknet_world_" + bot.getUUID());
    }

    public List<Entry> nearby(BotPlayer bot, double radius) {
        String dimension = bot.level().dimension().location().toString();
        ArrayList<Entry> result = new ArrayList<>();
        observed.forEach(dimension, (pos, cell) -> {
            if (cell.stateId() < 0 || bot.position().distanceToSqr(Vec3.atCenterOf(pos)) > radius * radius) return;
            var state = Block.stateById(cell.stateId());
            String category = cell.semantic().category();
            String kind = category.equals("station") ? cell.semantic().kind() : cell.semantic().container() ? "container" : "block";
            result.add(new Entry(new Location(dimension, pos.asLong()), BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),
                kind, cell.semantic().container(), cell.tick()));
        });
        result.sort(Comparator.comparingDouble(entry -> bot.position().distanceToSqr(Vec3.atCenterOf(entry.pos()))));
        return List.copyOf(result);
    }

    /** Existing close-range action glance, using exactly the same visual sensor. */
    public void glance(BotPlayer bot, int radius) {
        Vec3 eye = bot.getEyePosition();
        BlockPos center = BlockPos.containing(eye);
        for (BlockPos point : BlockPos.withinManhattan(center, radius, radius, radius)) {
            if (!bot.vision().canObserve()) break;
            Vec3 end = Vec3.atCenterOf(point);
            if (eye.distanceToSqr(end) <= radius * radius) bot.vision().observe(end);
        }
    }

    /** The bot's own placement is direct knowledge, even if its visual shape is empty. */
    public void remember(BotPlayer bot, BlockPos pos) {
        var state = bot.level().getBlockState(pos);
        observed.begin(bot.level().dimension().location().toString(), bot.serverLevel().getGameTime());
        if (state.isAir()) observed.empty(pos.getX(), pos.getY(), pos.getZ());
        else observed.seen(pos.getX(), pos.getY(), pos.getZ(), Block.getId(state),
            Semantics.observed(bot, pos, state), false);
    }

    /** Recheck from the bot's eyes: callers cannot erase a remote remembered cell by consulting the world. */
    public void forget(BotPlayer bot, BlockPos pos) {
        bot.vision().observe(Vec3.atCenterOf(pos));
    }

    /** A completed own break is direct knowledge and does not need another eye ray. */
    public void forgetOwn(BotPlayer bot, BlockPos pos) {
        observed.begin(bot.level().dimension().location().toString(), bot.serverLevel().getGameTime());
        observed.empty(pos.getX(), pos.getY(), pos.getZ());
    }

    /** Mechanics retain their requested shape; perception always uses the visual DDA. */
    public static BlockHitResult clip(BotPlayer bot, Vec3 end, ClipContext.Block shape) {
        Vec3 eye = bot.getEyePosition();
        BlockHitResult hit = bot.level().clip(new ClipContext(eye, end, shape, ClipContext.Fluid.NONE, bot));
        if (end.subtract(eye).normalize().dot(bot.getViewVector(1)) >= 0.5) bot.vision().observe(end);
        return hit;
    }

    private String contents(BotPlayer bot, Entry entry) {
        return bot.containerMemory().entries().stream().filter(snapshot ->
            snapshot.location().dimension().equals(entry.location().dimension())
                && snapshot.positions().contains(entry.location().position()) && snapshot.blockId().equals(entry.blockId()))
            .findFirst().map(snapshot -> bot.getServer().overworld().getGameTime() - snapshot.seenTick() > CONTENTS_STALE_TICKS
                ? "stale" : "known").orElse("unknown");
    }

    public String looked(BotPlayer bot, long since) {
        String result = nearby(bot, 17).stream().filter(entry -> entry.seenTick() >= since && !entry.kind().equals("block"))
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
        observed.save(tag);
        return tag;
    }

    public static WorldMemory load(CompoundTag tag, HolderLookup.Provider registries) {
        WorldMemory memory = new WorldMemory();
        memory.observed.load(tag, registries);
        // Migrate the old station/container-only SavedData without reading any world cells.
        for (Tag raw : tag.getList("world", Tag.TAG_COMPOUND)) {
            CompoundTag value = (CompoundTag) raw;
            BlockPos pos = BlockPos.of(value.getLong("position"));
            var block = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(value.getString("block")));
            if (block.isEmpty()) continue;
            memory.observed.begin(value.getString("dimension"), value.getLong("seen"));
            String kind = value.getString("kind");
            boolean container = value.getBoolean("container");
            String category = kind.equals("block") ? "other" : kind.equals("container") ? "container" : "station";
            memory.observed.seen(pos.getX(), pos.getY(), pos.getZ(), Block.getId(block.get().defaultBlockState()),
                new Semantics.Kind(category, kind, container), false);
        }
        return memory;
    }
}

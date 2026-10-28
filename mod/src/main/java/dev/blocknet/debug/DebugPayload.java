// SPDX-License-Identifier: MIT
package dev.blocknet.debug;

import dev.blocknet.Blocknet;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * One debug frame of a bot for a watching player's overlay: what it sees, where it is going and
 * why, and the tail of its reasoning trace. Sent only to players with the client mod who asked for
 * it with {@code /blocknet debug}.
 */
public record DebugPayload(
    int entityId,
    String name,
    List<String> status,
    @Nullable BlockPos target,
    @Nullable BlockPos clearing,
    List<Goal> goals,
    List<BlockPos> path,
    int pathIndex,
    List<BlockPos> toBreak,
    List<BlockPos> toPlace,
    List<BlockPos> planning,
    int dangerId,
    float viewYawHalf,
    float viewPitchHalf,
    float viewRange,
    List<Seen> seen,
    List<Thing> objects,
    List<Mob> entities,
    List<Spot> frontiers,
    List<Line> trace
) implements CustomPacketPayload {
    /** {@code radius}: 0 exact feet block, -1 next to the block (reach goal), above 0 a sphere. */
    public record Goal(BlockPos pos, int radius) {}
    /** A block one of the bot's eye rays reached recently; {@code category} indexes {@link #CATEGORIES}. */
    public record Seen(BlockPos pos, int category) {}
    /** An object of the scene, as the brain knows it by its ref (t3, o1, ...). */
    public record Thing(String label, int category, boolean remembered,
        double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {}
    public record Mob(double x, double y, double z, String label, int category) {}
    public record Spot(BlockPos pos, String label) {}
    public record Line(String source, String text, int ageTicks, int repeats) {}

    /** Shared by server and client so a seen block's kind costs one byte. */
    public static final List<String> CATEGORIES = List.of("other", "log", "leaves", "ore", "station", "stone", "dirt",
        "sand", "gravel", "crop", "built", "water", "lava", "dark", "container", "machine", "tree", "cliff",
        "cave_mouth", "hostile", "passive", "player", "item");

    public static int category(String name) {
        int index = CATEGORIES.indexOf(name);
        return index < 0 ? 0 : index;
    }

    public static final Type<DebugPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(Blocknet.MOD_ID, "debug"));

    public static final StreamCodec<FriendlyByteBuf, DebugPayload> STREAM_CODEC =
        StreamCodec.of((buffer, payload) -> payload.write(buffer), DebugPayload::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(FriendlyByteBuf buffer) {
        buffer.writeVarInt(entityId);
        buffer.writeUtf(name, 64);
        writeList(buffer, status, (b, line) -> b.writeUtf(line, 1024));
        writeOptional(buffer, target);
        writeOptional(buffer, clearing);
        writeList(buffer, goals, (b, goal) -> {
            b.writeBlockPos(goal.pos());
            b.writeVarInt(goal.radius() + 1);
        });
        writeList(buffer, path, FriendlyByteBuf::writeBlockPos);
        buffer.writeVarInt(pathIndex);
        writeList(buffer, toBreak, FriendlyByteBuf::writeBlockPos);
        writeList(buffer, toPlace, FriendlyByteBuf::writeBlockPos);
        writeList(buffer, planning, FriendlyByteBuf::writeBlockPos);
        buffer.writeVarInt(dangerId + 1);
        buffer.writeFloat(viewYawHalf);
        buffer.writeFloat(viewPitchHalf);
        buffer.writeFloat(viewRange);
        writeList(buffer, seen, (b, cell) -> {
            b.writeBlockPos(cell.pos());
            b.writeByte(cell.category());
        });
        writeList(buffer, objects, (b, thing) -> {
            b.writeUtf(thing.label(), 128);
            b.writeByte(thing.category());
            b.writeBoolean(thing.remembered());
            b.writeDouble(thing.minX());
            b.writeDouble(thing.minY());
            b.writeDouble(thing.minZ());
            b.writeDouble(thing.maxX());
            b.writeDouble(thing.maxY());
            b.writeDouble(thing.maxZ());
        });
        writeList(buffer, entities, (b, mob) -> {
            b.writeDouble(mob.x());
            b.writeDouble(mob.y());
            b.writeDouble(mob.z());
            b.writeUtf(mob.label(), 128);
            b.writeByte(mob.category());
        });
        writeList(buffer, frontiers, (b, spot) -> {
            b.writeBlockPos(spot.pos());
            b.writeUtf(spot.label(), 128);
        });
        writeList(buffer, trace, (b, line) -> {
            b.writeUtf(line.source(), 32);
            b.writeUtf(line.text(), 1024);
            b.writeVarInt(line.ageTicks());
            b.writeVarInt(line.repeats());
        });
    }

    private static DebugPayload read(FriendlyByteBuf buffer) {
        int entityId = buffer.readVarInt();
        String name = buffer.readUtf(64);
        List<String> status = readList(buffer, b -> b.readUtf(1024));
        BlockPos target = readOptional(buffer);
        BlockPos clearing = readOptional(buffer);
        List<Goal> goals = readList(buffer, b -> new Goal(b.readBlockPos(), b.readVarInt() - 1));
        List<BlockPos> path = readList(buffer, FriendlyByteBuf::readBlockPos);
        int pathIndex = buffer.readVarInt();
        List<BlockPos> toBreak = readList(buffer, FriendlyByteBuf::readBlockPos);
        List<BlockPos> toPlace = readList(buffer, FriendlyByteBuf::readBlockPos);
        List<BlockPos> planning = readList(buffer, FriendlyByteBuf::readBlockPos);
        int dangerId = buffer.readVarInt() - 1;
        float yawHalf = buffer.readFloat();
        float pitchHalf = buffer.readFloat();
        float range = buffer.readFloat();
        List<Seen> seen = readList(buffer, b -> new Seen(b.readBlockPos(), b.readByte()));
        List<Thing> objects = readList(buffer, b -> new Thing(b.readUtf(128), b.readByte(), b.readBoolean(),
            b.readDouble(), b.readDouble(), b.readDouble(), b.readDouble(), b.readDouble(), b.readDouble()));
        List<Mob> entities = readList(buffer, b -> new Mob(b.readDouble(), b.readDouble(), b.readDouble(), b.readUtf(128), b.readByte()));
        List<Spot> frontiers = readList(buffer, b -> new Spot(b.readBlockPos(), b.readUtf(128)));
        List<Line> trace = readList(buffer, b -> new Line(b.readUtf(32), b.readUtf(1024), b.readVarInt(), b.readVarInt()));
        return new DebugPayload(entityId, name, status, target, clearing, goals, path, pathIndex, toBreak, toPlace,
            planning, dangerId, yawHalf, pitchHalf, range, seen, objects, entities, frontiers, trace);
    }

    private static <T> void writeList(FriendlyByteBuf buffer, List<T> values, BiConsumer<FriendlyByteBuf, T> writer) {
        buffer.writeVarInt(values.size());
        for (T value : values) writer.accept(buffer, value);
    }

    private static <T> List<T> readList(FriendlyByteBuf buffer, Function<FriendlyByteBuf, T> reader) {
        int size = buffer.readVarInt();
        if (size < 0 || size > 65536) throw new IllegalArgumentException("Debug frame list too long: " + size);
        List<T> values = new ArrayList<>(size);
        for (int i = 0; i < size; i++) values.add(reader.apply(buffer));
        return values;
    }

    private static void writeOptional(FriendlyByteBuf buffer, @Nullable BlockPos pos) {
        buffer.writeBoolean(pos != null);
        if (pos != null) buffer.writeBlockPos(pos);
    }

    @Nullable
    private static BlockPos readOptional(FriendlyByteBuf buffer) {
        return buffer.readBoolean() ? buffer.readBlockPos() : null;
    }
}

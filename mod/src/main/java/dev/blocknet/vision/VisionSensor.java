// SPDX-License-Identifier: MIT
package dev.blocknet.vision;

import dev.blocknet.body.BotPlayer;
import java.util.HashSet;
import java.util.ArrayDeque;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** Server-thread, time-sliced jittered camera. The head owns the view, including during look. */
public final class VisionSensor {
    public static final long BUDGET_NANOS = Math.max(100_000, Long.getLong("blocknet.visionBudgetNanos", 1_500_000L));
    public record EntitySeen(UUID uuid, String type, String category, Vec3 position, double distance, long lastSeen, String name) {}
    private final BotPlayer bot;
    private final VoxelRay ray;
    private final VisionTiming timing = new VisionTiming();
    private final Map<UUID, EntitySeen> entities = new LinkedHashMap<>();
    private int cursor, frameWidth = 64, frameHeight = 36, frameNumber;
    private long frameNanos, lastTick = Long.MIN_VALUE, workTick = Long.MIN_VALUE, tickNanos;
    private boolean panorama, frontal;
    private final ArrayDeque<Vec3> exposed = new ArrayDeque<>();
    private int completedFrames;
    private long lookSince;
    private int entityCursor;

    public VisionSensor(BotPlayer bot) { this.bot = bot; ray = new VoxelRay(bot); }
    public VisionTiming timing() { return timing; }
    public double range() { return Math.min(64, bot.getServer().getPlayerList().getViewDistance() * 16.0); }
    public int completedFrames() { return completedFrames; }
    public void startFrame() {
        frontal = true;
        cursor = 0; frameNanos = 0; frameWidth = 128; frameHeight = 72;
    }

    /** Sample only eye-facing faces of the six newly exposed cells, with ordinary occluding rays. */
    public void afterBreak(BlockPos removed) {
        Vec3 eye = bot.getEyePosition();
        for (Direction neighbour : Direction.values()) {
            BlockPos cell = removed.relative(neighbour);
            for (Direction face : Direction.values()) {
                Vec3 normal = Vec3.atLowerCornerOf(face.getNormal());
                Vec3 center = Vec3.atCenterOf(cell).add(normal.scale(.5));
                if (eye.subtract(center).dot(normal) <= 0) continue;
                for (double a : new double[] {-.3, 0, .3}) for (double b : new double[] {-.3, 0, .3}) {
                    Vec3 offset = switch (face.getAxis()) {
                        case X -> new Vec3(0, a, b);
                        case Y -> new Vec3(a, 0, b);
                        case Z -> new Vec3(a, b, 0);
                    };
                    exposed.add(center.add(offset).subtract(normal.scale(.001)));
                }
            }
        }
    }

    public void startPanorama() {
        panorama = true; frontal = false;
        lookSince = bot.serverLevel().getGameTime();
        cursor = 0; frameNanos = 0; frameWidth = 128; frameHeight = 72;
    }
    public void endPanorama() {
        panorama = false; frontal = false;
        // Complete a partially sampled high-resolution frame before returning to background resolution.
        if (cursor == 0) { frameWidth = 64; frameHeight = 36; }
    }
    public long lookSince() { return lookSince; }
    private ObservedMap prepare() {
        var map = bot.worldMemory().observed();
        map.begin(bot.level().dimension().location().toString(), bot.serverLevel().getGameTime());
        return map;
    }

    private void beginTick() {
        long tick = bot.serverLevel().getGameTime();
        if (workTick != tick) {
            if (workTick != Long.MIN_VALUE && tickNanos > 0) timing.tick(tickNanos);
            workTick = tick;
            tickNanos = 0;
        }
    }

    public boolean canObserve() {
        beginTick();
        return tickNanos < BUDGET_NANOS - 150_000;
    }

    public VoxelRay.Result observe(Vec3 end) {
        Vec3 eye = bot.getEyePosition(), delta = end.subtract(eye);
        if (!canObserve()) return new VoxelRay.Result(VoxelRay.End.UNKNOWN, null, null, 0, null, false);
        long start = System.nanoTime();
        VoxelRay.Result result = ray.cast(eye, delta.normalize(), Math.min(range(), delta.length()), prepare());
        tickNanos += System.nanoTime() - start;
        return result;
    }

    public void tick() {
        long tick = bot.serverLevel().getGameTime();
        if (lastTick == tick || !bot.isAlive()) return;
        lastTick = tick;
        beginTick();
        while (!exposed.isEmpty() && canObserve()) observe(exposed.removeFirst());
        if (!panorama && !frontal && cursor == 0 && tick % 4 != 0) return;
        if (!canObserve()) return;
        long start = System.nanoTime(), deadline = start + BUDGET_NANOS - tickNanos;
        ObservedMap map = prepare();
        Vec3 eye = bot.getEyePosition();
        float yaw = bot.getYRot(), pitch = bot.getXRot();
        // Entity work is sliced too; one candidate per tick cannot monopolize the frame budget.
        observeEntity(eye, yaw, pitch);
        int total = frameWidth * frameHeight;
        Vec3 forward = Vec3.directionFromRotation(pitch, yaw);
        Vec3 right = Vec3.directionFromRotation(0, yaw + 90);
        Vec3 up = right.cross(forward);
        // A coprime permutation distributes each partial frame over the whole FOV.
        while (System.nanoTime() < deadline - 100_000) {
            int pixel = Math.floorMod(cursor * 1543, total);
            double jitterX = jitter(pixel, frameNumber, 0), jitterY = jitter(pixel, frameNumber, 1);
            double u = ((pixel % frameWidth + jitterX) / frameWidth * 2 - 1) * Math.tan(Math.toRadians(panorama || frontal ? 45 : 80));
            double v = ((pixel / frameWidth + jitterY) / frameHeight * 2 - 1) * Math.tan(Math.toRadians(panorama || frontal ? 29.5 : 45));
            Vec3 direction = forward.add(right.scale(u)).add(up.scale(-v)).normalize();
            ray.cast(eye, direction, range(), map);
            cursor++;
            if (cursor == total) {
                long now = System.nanoTime();
                frameNanos += now - start;
                timing.frame(frameNanos, frameWidth == 128);
                cursor = 0; frameNanos = 0; frameNumber++; completedFrames++; frontal = false;
                if (!panorama) { frameWidth = 64; frameHeight = 36; }
                tickNanos += now - start;
                return;
            }
        }
        long elapsed = System.nanoTime() - start;
        frameNanos += elapsed;
        tickNanos += elapsed;
    }

    private static double jitter(int pixel, int frame, int axis) {
        int value = pixel * 0x45d9f3b + frame * 0x119de1f3 + axis * 0x27d4eb2d;
        value ^= value >>> 16; value *= 0x45d9f3b; value ^= value >>> 16;
        return (value & 0xffff) / 65536.0;
    }

    private void observeEntity(Vec3 eye, float yaw, float pitch) {
        double range = range();
        List<Entity> candidates = bot.serverLevel().getEntities(bot, bot.getBoundingBox().inflate(range),
            entity -> entity.isAlive() && !entity.isSpectator());
        if (!candidates.isEmpty()) {
            Entity entity = candidates.get(Math.floorMod(entityCursor++, candidates.size()));
            Vec3 target = entity.getEyePosition();
            if (inView(target.subtract(eye), yaw, pitch, range)) {
                VoxelRay.Result hit = ray.cast(eye, target.subtract(eye).normalize(), eye.distanceTo(target), null);
                if (hit.end() == VoxelRay.End.RANGE) {
                    String category = entity instanceof Player ? "player" : entity instanceof ItemEntity ? "item"
                        : entity.getType().getCategory() == MobCategory.MONSTER ? "hostile" : "passive";
                    entities.put(entity.getUUID(), new EntitySeen(entity.getUUID(),
                        BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath(), category,
                        entity.position(), eye.distanceTo(entity.position()), bot.serverLevel().getGameTime(), entity instanceof Player ? entity.getName().getString() : ""));
                }
            }
        }
        entities.values().removeIf(entity -> bot.serverLevel().getGameTime() - entity.lastSeen() > 100);
    }

    private boolean inView(Vec3 delta, float yaw, float pitch, double range) {
        if (delta.lengthSqr() > range * range) return false;
        Vec3 forward = Vec3.directionFromRotation(pitch, yaw), right = Vec3.directionFromRotation(0, yaw + 90);
        Vec3 up = right.cross(forward);
        double depth = delta.dot(forward);
        return depth > 0 && Math.abs(delta.dot(right)) <= depth * Math.tan(Math.toRadians(panorama || frontal ? 45 : 80)) && Math.abs(delta.dot(up)) <= depth * Math.tan(Math.toRadians(panorama || frontal ? 29.5 : 45));
    }

    public ObjectGraph.ObjectView resolve(String ref) {
        return bot.worldMemory().observed().graph(bot.level().dimension().location().toString())
            .resolve(ref, bot.getEyePosition(), bot.serverLevel().getGameTime() - 4);
    }

    public List<ObjectGraph.ObjectView> objects() {
        String dimension = bot.level().dimension().location().toString();
        ObservedMap map = bot.worldMemory().observed();
        Set<Long> caves = new HashSet<>();
        map.forEach(dimension, (pos, cell) -> { if (map.caveMouth(dimension, pos)) caves.add(pos.asLong()); });
        return map.graph(dimension).objects(bot.getEyePosition(), panorama ? lookSince : bot.serverLevel().getGameTime() - 4)
            .stream().map(object -> {
                boolean cave = object.category().equals("dark") && caves.stream().map(net.minecraft.core.BlockPos::of)
                    .anyMatch(pos -> object.bounds().contains(Vec3.atCenterOf(pos)));
                return cave ? new ObjectGraph.ObjectView(object.ref(), "cave_mouth", "cave_mouth", object.seenMembers(),
                    object.knownMembers(), object.center(), object.nearest(), object.bounds(), object.lastSeenTick(), object.provenance()) : object;
            }).toList();
    }
    public List<Frontiers.Frontier> frontiers() {
        return Frontiers.find(bot.worldMemory().observed(), bot.level().dimension().location().toString(), bot.position(), range());
    }
    public List<EntitySeen> entities() { return List.copyOf(entities.values()); }
    public String report() {
        String objects = objects().stream().map(object -> String.format(Locale.ROOT, "%s %s %s %s %.1fm seen=%d known=%d %s",
            object.category(), object.kind(), object.ref(), Frontiers.direction(object.center().subtract(bot.position())),
            bot.position().distanceTo(Vec3.atCenterOf(object.nearest())), object.seenMembers(), object.knownMembers(), object.provenance()))
            .collect(Collectors.joining("; "));
        String mobs = entities().stream().map(entity -> String.format(Locale.ROOT, "%s %s %s %.1fm last=%d", entity.category(),
            entity.type(), Frontiers.direction(entity.position().subtract(bot.position())), bot.position().distanceTo(entity.position()),
            entity.lastSeen())).collect(Collectors.joining("; "));
        String frontiers = frontiers().stream().map(frontier -> frontier.direction() + " "
            + Math.round(frontier.knownDistance()) + "m " + (frontier.caveMouth() ? "cave mouth" : frontier.blocked() ? "blocked" : "unexplored"))
            .collect(Collectors.joining("; "));
        ObservedMap map = bot.worldMemory().observed();
        return "objects: " + objects + " | entities: " + mobs + " | frontiers: " + frontiers + " | " + timing.report()
            + " | map: " + map.sectionCount() + " sections, " + map.cellCount() + " seen, " + map.emptyCount()
            + " empty, ~" + map.estimatedBytes() + " bytes";
    }
}

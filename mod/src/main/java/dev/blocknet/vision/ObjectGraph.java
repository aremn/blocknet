// SPDX-License-Identifier: MIT
package dev.blocknet.vision;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Incremental union-find; removal rebuilds only the affected component, allowing splits. */
public final class ObjectGraph {
    public enum Provenance { VISIBLE_NOW, REMEMBERED }
    public record ObjectView(String ref, String category, String kind, int seenMembers, int knownMembers,
            Vec3 center, BlockPos nearest, AABB bounds, long lastSeenTick, Provenance provenance) {}
    private static final class Node {
        final long position;
        ObservedMap.Cell cell;
        Node parent = this;
        int size = 1;
        int live = 1;
        Node(long position, ObservedMap.Cell cell) { this.position = position; this.cell = cell; }
    }
    private final Long2ObjectOpenHashMap<Node> nodes = new Long2ObjectOpenHashMap<>();
    private final Map<Long, String> refs = new HashMap<>();
    private final Map<String, Integer> counters = new HashMap<>();
    // Recently issued refs survive component merges/splits long enough to revisit their evidence.
    private final Map<String, ObjectView> issued = new LinkedHashMap<>();

    public ObjectView resolve(String ref, Vec3 eye, long since) {
        var current = objects(eye, since).stream().filter(o -> o.ref().equals(ref)).findFirst();
        return current.orElse(issued.get(ref));
    }
    private final Set<Node> invalid = Collections.newSetFromMap(new IdentityHashMap<>());

    private static String group(ObservedMap.Cell cell) {
        String category = cell.semantic().category();
        return switch (category) {
            case "log", "leaves" -> "tree:" + cell.semantic().kind();
            case "ore" -> "ore:" + cell.semantic().kind();
            case "stone", "dirt" -> cell.side() ? "cliff" : "";
            case "water", "lava", "built", "dark" -> category;
            case "crop", "station", "container", "machine" -> category + ":" + cell.semantic().kind();
            default -> "";
        };
    }

    private Node root(Node node) {
        Node result = node;
        while (result.parent != result) result = result.parent;
        while (node.parent != node) {
            Node next = node.parent;
            node.parent = result;
            node = next;
        }
        return result;
    }

    public void put(long position, ObservedMap.Cell old, ObservedMap.Cell cell) {
        if (old != null && (old.semantic().equals(cell.semantic()) && old.side() == cell.side()
                || group(old).equals(group(cell)))) {
            Node node = nodes.get(position);
            if (node != null) node.cell = cell;
            return;
        }
        remove(position);
        if (group(cell).isEmpty()) return;
        Node node = new Node(position, cell);
        nodes.put(position, node);
        connect(node);
    }

    private void connect(Node node) {
        BlockPos pos = BlockPos.of(node.position);
        String group = group(node.cell);
        int gap = group.startsWith("tree:") || group.startsWith("ore:") ? 2 : 1;
        for (int dx = -gap; dx <= gap; dx++) for (int dy = -gap; dy <= gap; dy++) for (int dz = -gap; dz <= gap; dz++) {
            if (dx == 0 && dy == 0 && dz == 0) continue;
            Node other = nodes.get(BlockPos.asLong(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz));
            if (other == null || !group.equals(group(other.cell))) continue;
            Node a = root(node), b = root(other);
            if (a == b) continue;
            if (a.size < b.size) { Node swap = a; a = b; b = swap; }
            if (invalid.remove(b)) invalid.add(a);
            b.parent = a;
            a.size += b.size;
            a.live += b.live;
        }
    }

    public void remove(long position) {
        Node node = nodes.remove(position);
        if (node != null) {
            Node root = root(node);
            if (--root.live == 0) invalid.remove(root);
            else invalid.add(root);
        }
        refs.remove(position);
    }

    private void repair() {
        if (invalid.isEmpty()) return;
        List<Node> affected = nodes.values().stream().filter(node -> invalid.contains(root(node))).toList();
        for (Node node : affected) { node.parent = node; node.size = 1; node.live = 1; }
        invalid.clear();
        for (Node node : affected) connect(node);
    }

    public List<ObjectView> objects(Vec3 eye, long since) {
        repair();
        Map<Node, List<Node>> groups = new IdentityHashMap<>();
        for (Node node : nodes.values()) groups.computeIfAbsent(root(node), ignored -> new ArrayList<>()).add(node);
        List<ObjectView> result = new ArrayList<>();
        Set<String> usedRefs = new HashSet<>();
        List<List<Node>> ordered = groups.values().stream().sorted(Comparator.comparingLong(members ->
            members.stream().mapToLong(node -> node.position).min().orElse(0))).toList();
        for (List<Node> members : ordered) {
            String group = group(members.getFirst().cell);
            boolean logs = members.stream().anyMatch(node -> node.cell.semantic().category().equals("log"));
            int minX = Integer.MAX_VALUE, minY = minX, minZ = minX;
            int maxX = Integer.MIN_VALUE, maxY = maxX, maxZ = maxX;
            double x = 0, y = 0, z = 0, distance = Double.MAX_VALUE;
            BlockPos nearest = null;
            long lastSeen = 0;
            int visible = 0;
            for (Node node : members) {
                BlockPos pos = BlockPos.of(node.position);
                minX = Math.min(minX, pos.getX()); minY = Math.min(minY, pos.getY()); minZ = Math.min(minZ, pos.getZ());
                maxX = Math.max(maxX, pos.getX()); maxY = Math.max(maxY, pos.getY()); maxZ = Math.max(maxZ, pos.getZ());
                x += pos.getX() + 0.5; y += pos.getY() + 0.5; z += pos.getZ() + 0.5;
                double d = eye.distanceToSqr(Vec3.atCenterOf(pos));
                if (d < distance) { distance = d; nearest = pos; }
                lastSeen = Math.max(lastSeen, node.cell.tick());
                if (node.cell.tick() >= since) visible++;
            }
            if (group.startsWith("tree:") && !logs || group.equals("built") && members.size() < 4
                    || group.equals("cliff") && maxY - minY < 2) continue;
            String category = group.split(":")[0];
            String kind = group.contains(":") ? group.substring(group.indexOf(':') + 1) : switch (group) {
                case "water" -> "water_body";
                case "lava" -> "lava_pool";
                case "built" -> "structure";
                default -> group;
            };
            String prefix = switch (category) {
                case "tree" -> "t";
                case "ore" -> "c";
                case "water" -> "w";
                case "lava" -> "l";
                case "cliff" -> "s";
                case "built" -> "b";
                case "station" -> "a";
                case "container" -> "h";
                case "machine" -> "m";
                default -> "d";
            };
            String ref = members.stream().map(node -> refs.get(node.position)).filter(Objects::nonNull)
                .filter(value -> !usedRefs.contains(value)).sorted().findFirst().orElseGet(() ->
                    prefix + counters.merge(prefix, 1, Integer::sum));
            usedRefs.add(ref);
            for (Node node : members) refs.put(node.position, ref);
            result.add(new ObjectView(ref, category, kind, visible, members.size(),
                new Vec3(x / members.size(), y / members.size(), z / members.size()), nearest,
                new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1), lastSeen,
                visible > 0 ? Provenance.VISIBLE_NOW : Provenance.REMEMBERED));
        }
        for (var view : result) issued.put(view.ref(), view);
        while (issued.size() > 512) issued.remove(issued.keySet().iterator().next());
        return result.stream().sorted(Comparator.comparing(ObjectView::ref)).toList();
    }

    void saveRefs(CompoundTag tag) {
        Map<String, Integer> palette = new LinkedHashMap<>();
        long[] positions = new long[refs.size()];
        int[] ids = new int[refs.size()];
        int offset = 0;
        for (var entry : refs.entrySet()) {
            positions[offset] = entry.getKey();
            ids[offset++] = palette.computeIfAbsent(entry.getValue(), ignored -> palette.size());
        }
        ListTag names = new ListTag();
        palette.keySet().forEach(name -> names.add(StringTag.valueOf(name)));
        tag.putLongArray("positions", positions);
        tag.putIntArray("ids", ids);
        tag.put("names", names);
        CompoundTag counts = new CompoundTag();
        counters.forEach(counts::putInt);
        tag.put("counters", counts);
    }

    void loadRefs(CompoundTag tag) {
        long[] positions = tag.getLongArray("positions");
        int[] ids = tag.getIntArray("ids");
        ListTag names = tag.getList("names", Tag.TAG_STRING);
        for (int i = 0; i < positions.length && i < ids.length; i++) {
            if (nodes.containsKey(positions[i]) && ids[i] >= 0 && ids[i] < names.size()) refs.put(positions[i], names.getString(ids[i]));
        }
        CompoundTag counts = tag.getCompound("counters");
        counts.getAllKeys().forEach(key -> counters.put(key, counts.getInt(key)));
    }
}

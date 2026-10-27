package dev.blocknet.state;

import java.util.List;
import java.util.TreeSet;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Vanilla probes describe capabilities, never an item's class or a mod-specific tier. */
public final class ToolFacts {
    private ToolFacts() {}

    public static List<String> kinds(ItemStack stack) {
        var kinds = new TreeSet<String>();
        stack.getTags().forEach(tag -> {
            var id = tag.location();
            String path = id.getPath();
            if (id.getNamespace().equals("minecraft") && List.of("pickaxes", "axes", "shovels", "hoes", "swords").contains(path)
                || id.getNamespace().equals("c") && path.startsWith("tools/")) {
                String kind = path.substring(path.lastIndexOf('/') + 1);
                kinds.add(kind.endsWith("s") ? kind.substring(0, kind.length() - 1) : kind);
            }
        });
        return List.copyOf(kinds);
    }

    public static int miningLevel(ItemStack stack) {
        var probes = List.of(Blocks.STONE, Blocks.IRON_ORE, Blocks.DIAMOND_ORE, Blocks.OBSIDIAN);
        int level = -1;
        for (int i = 0; i < probes.size(); i++) {
            if (stack.isCorrectToolForDrops(probes.get(i).defaultBlockState())) level = i;
        }
        return level;
    }

    public static String miningText(int level) {
        return switch (level) {
            case 0 -> "mines up to stone, not iron_ore";
            case 1 -> "mines up to iron_ore, not diamond_ore";
            case 2 -> "mines up to diamond_ore, not obsidian";
            case 3 -> "mines up to obsidian";
            default -> "cannot harvest stone";
        };
    }

    public static BlockState probe(String kind) {
        return switch (kind) {
            case "axe" -> Blocks.OAK_LOG.defaultBlockState();
            case "shovel" -> Blocks.DIRT.defaultBlockState();
            case "hoe" -> Blocks.HAY_BLOCK.defaultBlockState();
            case "sword" -> Blocks.COBWEB.defaultBlockState();
            default -> Blocks.STONE.defaultBlockState();
        };
    }

    private static Item[][] probes() {
        Item[][] probes = {
            {Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE},
            {Items.WOODEN_AXE, Items.STONE_AXE, Items.IRON_AXE, Items.DIAMOND_AXE},
            {Items.WOODEN_SHOVEL, Items.STONE_SHOVEL, Items.IRON_SHOVEL, Items.DIAMOND_SHOVEL},
            {Items.WOODEN_HOE, Items.STONE_HOE, Items.IRON_HOE, Items.DIAMOND_HOE}
        };
        return probes;
    }

    public static java.util.Optional<Item> minimumTool(BlockState state) {
        if (!state.requiresCorrectToolForDrops()) return java.util.Optional.empty();
        for (Item[] kind : probes()) for (Item tool : kind)
            if (new ItemStack(tool).isCorrectToolForDrops(state)) return java.util.Optional.of(tool);
        return java.util.Optional.empty();
    }

    public static String requirement(BlockState state) {
        Item[][] probes = probes();
        String[] tiers = {"wooden tier", "stone tier", "iron tier", "diamond tier"};
        for (int kind = 0; kind < probes.length; kind++) {
            Item[] tools = probes[kind];
            for (int i = 0; i < tools.length; i++) {
                ItemStack stack = new ItemStack(tools[i]);
                if (stack.isCorrectToolForDrops(state)) {
                    return List.of("pickaxe", "axe", "shovel", "hoe").get(kind) + ", at least " + tiers[i];
                }
            }
        }
        return "a correct tool";
    }
}

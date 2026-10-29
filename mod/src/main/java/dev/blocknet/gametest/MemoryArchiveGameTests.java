// SPDX-License-Identifier: MIT
package dev.blocknet.gametest;

import dev.blocknet.Blocknet;
import dev.blocknet.body.BotManager;
import dev.blocknet.memory.WorldMemory;
import dev.blocknet.vision.ObservedMap;
import dev.blocknet.vision.Semantics;
import java.nio.file.Files;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Unlimited eye memory: RAM working set, disk archive, warm-up and the notable index. */
@GameTestHolder(Blocknet.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MemoryArchiveGameTests {
    private MemoryArchiveGameTests() {}

    @GameTest(template = "arena", timeoutTicks = 100)
    public static void evictedSectionsGoToDiskAndComeBack(GameTestHelper helper) {
        TestArena.floor(helper);
        var bot = TestArena.spawnBot(helper, "BnMemArchive", new Vec3(1.5, 1, 1.5));
        String dimension = bot.level().dimension().location().toString();
        ObservedMap memory = bot.worldMemory().observed();
        helper.assertTrue(memory.archiving(), "the archive is attached");
        // Far from any test fixture: a remembered chest, then far more sections than RAM keeps.
        BlockPos chest = new BlockPos(5_000_000, 70, 5_000_000);
        memory.begin(dimension, 1);
        memory.seen(chest.getX(), chest.getY(), chest.getZ(), Block.getId(Blocks.CHEST.defaultBlockState()),
            new Semantics.Kind("container", "chest", true), true);
        int stone = Block.getId(Blocks.STONE.defaultBlockState());
        for (int i = 0; i < ObservedMap.MAX_SECTIONS + 200; i++) {
            memory.seen(5_100_000 + (i % 100) * 16, 64, 5_100_000 + (i / 100) * 16, stone, new Semantics.Kind("stone", "stone", false), false);
        }
        helper.assertTrue(memory.archivedCount() > 0 && memory.sectionCount() <= ObservedMap.MAX_SECTIONS, "old sections archived, RAM bounded");
        helper.assertTrue(memory.cell(dimension, chest) == null, "the chest's section left RAM");
        helper.assertTrue(memory.remembered(dimension, BlockPos.ZERO, key -> key.equals("container:chest"), 4).stream()
            .anyMatch(r -> r.pos().equals(chest)), "the index still knows the archived chest");

        // Saving writes the archive to disk; a reloaded memory finds the chest again when warmed around it.
        CompoundTag saved = bot.worldMemory().save(new CompoundTag(), helper.getLevel().registryAccess());
        var root = bot.getServer().getWorldPath(LevelResource.ROOT).resolve("data").resolve("blocknet_memory").resolve(bot.getUUID().toString());
        try (var files = Files.walk(root)) {
            helper.assertTrue(files.anyMatch(p -> p.getFileName().toString().endsWith(".dat")), "region files written under " + root);
        } catch (java.io.IOException exception) {
            throw new RuntimeException(exception);
        }
        WorldMemory reloaded = WorldMemory.load(saved, helper.getLevel().registryAccess());
        reloaded.observed().attach(root, helper.getLevel().registryAccess(), false);
        helper.assertTrue(reloaded.observed().archivedCount() == memory.archivedCount(), "archived keys survive the reload");
        reloaded.observed().warm(dimension, chest, 1);
        var cell = reloaded.observed().cell(dimension, chest);
        helper.assertTrue(cell != null && cell.stateId() == Block.getId(Blocks.CHEST.defaultBlockState()), "the chest came back from disk");

        // The live memory warms back as well (the oldest RAM section takes its place in the archive).
        memory.warm(dimension, chest, 0);
        helper.assertTrue(memory.cell(dimension, chest) != null && memory.sectionCount() <= ObservedMap.MAX_SECTIONS, "warm brings the section back to RAM");
        BotManager.despawn("BnMemArchive");
        helper.succeed();
    }
}

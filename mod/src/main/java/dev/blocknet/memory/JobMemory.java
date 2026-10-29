// SPDX-License-Identifier: MIT
package dev.blocknet.memory;

import dev.blocknet.body.BotPlayer;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;

/** Durable unfinished cargo job. Item stacks still live only in the real player inventory. */
public final class JobMemory extends SavedData {
    private CompoundTag transfer = new CompoundTag();
    public static JobMemory of(BotPlayer bot) {
        return bot.getServer().overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(JobMemory::new, JobMemory::load), "blocknet_jobs_"+bot.getUUID());
    }
    public CompoundTag transfer() { return transfer.copy(); }
    public void transfer(CompoundTag value) { transfer = value.copy(); setDirty(); }
    public void clearTransfer() { transfer = new CompoundTag(); setDirty(); }
    @Override public CompoundTag save(CompoundTag root, HolderLookup.Provider registries) { root.put("transfer",transfer.copy()); return root; }
    private static JobMemory load(CompoundTag root, HolderLookup.Provider registries) {
        JobMemory memory=new JobMemory();memory.transfer=root.getCompound("transfer").copy();return memory;
    }
}

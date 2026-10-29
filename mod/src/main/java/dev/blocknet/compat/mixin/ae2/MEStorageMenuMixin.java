// SPDX-License-Identifier: MIT
package dev.blocknet.compat.mixin.ae2;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.menu.me.common.IncrementalUpdateHelper;
import appeng.menu.me.common.MEStorageMenu;
import dev.blocknet.compat.ae2.Ae2MenuView;
import java.util.Set;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Exposes the snapshot the terminal already sends its player; reads only, never changes it. */
@Mixin(MEStorageMenu.class)
public abstract class MEStorageMenuMixin implements Ae2MenuView {
    @Shadow private KeyCounter previousAvailableStacks;
    @Shadow private Set<AEKey> previousCraftables;
    @Shadow @org.spongepowered.asm.mixin.Final private IncrementalUpdateHelper updateHelper;
    @Unique private int blocknet$broadcasts;

    @Inject(method = "broadcastChanges", at = @At("TAIL"))
    private void blocknet$countBroadcast(CallbackInfo ci) {
        blocknet$broadcasts++;
    }

    @Override public int blocknet$broadcasts() { return blocknet$broadcasts; }
    @Override public KeyCounter blocknet$available() { return previousAvailableStacks; }
    @Override public Set<AEKey> blocknet$craftables() { return previousCraftables; }
    @Override public Long blocknet$serial(AEKey key) { return updateHelper.getSerial(key); }
}

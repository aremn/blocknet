// SPDX-License-Identifier: MIT
package dev.blocknet.compat;

import java.util.List;
import java.util.Set;
import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Applies {@code dev.blocknet.compat.mixin.<modid>.*} only when that mod is being loaded. */
public final class CompatMixinPlugin implements IMixinConfigPlugin {
    private static final String PREFIX = "dev.blocknet.compat.mixin.";

    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!mixinClassName.startsWith(PREFIX)) return false;
        String rest = mixinClassName.substring(PREFIX.length());
        int dot = rest.indexOf('.');
        return dot > 0 && LoadingModList.get().getModFileById(rest.substring(0, dot)) != null;
    }

    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}

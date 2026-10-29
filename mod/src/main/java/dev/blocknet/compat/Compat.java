// SPDX-License-Identifier: MIT
package dev.blocknet.compat;

import dev.blocknet.Blocknet;
import dev.blocknet.control.BlockParts;
import dev.blocknet.control.NetworkMenus;
import java.util.Map;
import net.neoforged.fml.ModList;

/**
 * The only place that maps a mod id to its optional adapter (ADR-0005). Adapter classes are loaded
 * by name and only when their mod is present, so the body runs unchanged without them.
 */
public final class Compat {
    private static final Map<String, String> ADAPTERS = Map.of(
        "ae2", "dev.blocknet.compat.ae2.Ae2TerminalAdapter");

    private Compat() {}

    public static void init() {
        ADAPTERS.forEach((mod, adapter) -> {
            if (!ModList.get().isLoaded(mod)) return;
            try {
                // One adapter object may serve several generic interfaces.
                Object instance = Class.forName(adapter).getDeclaredConstructor().newInstance();
                if (instance instanceof NetworkMenus.Adapter menus) NetworkMenus.register(menus);
                if (instance instanceof BlockParts.Adapter parts) BlockParts.register(parts);
                Blocknet.LOGGER.info("Adapter enabled for {}", mod);
            } catch (ReflectiveOperationException | LinkageError e) {
                Blocknet.LOGGER.warn("Network menu adapter for {} unavailable: {}", mod, e.toString());
            }
        });
    }
}

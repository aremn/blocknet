// SPDX-License-Identifier: MIT
package dev.blocknet.compat.ae2;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import java.util.Set;

/** What the terminal menu last prepared for its player, added to MEStorageMenu by a mixin. */
public interface Ae2MenuView {
    /** Server-side menu ticks since it opened; zero means nothing was prepared yet. */
    int blocknet$broadcasts();
    KeyCounter blocknet$available();
    Set<AEKey> blocknet$craftables();
    /** The serial this player's terminal screen knows the key by, or null when it was never shown. */
    Long blocknet$serial(AEKey key);
}

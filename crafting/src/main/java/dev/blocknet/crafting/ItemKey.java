/* SPDX-License-Identifier: LGPL-3.0-or-later */
package dev.blocknet.crafting;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/** An immutable item AND data-component identity. Never exposes its stored stack. */
public final class ItemKey implements Comparable<ItemKey> {
    private final ItemStack template;
    private final String itemId;
    private final int hash;

    private String componentOrder;

    public ItemKey(ItemStack stack) {
        if (stack.isEmpty()) {
            throw new IllegalArgumentException("empty item key");
        }
        template = stack.copyWithCount(1);
        itemId = BuiltInRegistries.ITEM.getKey(template.getItem()).toString();
        hash = ItemStack.hashItemAndComponents(template);
    }

    public ItemStack stack() {
        return template.copy();
    }

    public String id() {
        return itemId;
    }

    public String text() {
        String id = id();
        return (id.startsWith("minecraft:") ? id.substring(10) : id)
            + (template.getComponentsPatch().isEmpty() ? "" : template.getComponentsPatch().toString());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ItemKey key && ItemStack.isSameItemSameComponents(template, key.template);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public int compareTo(ItemKey key) {
        int comparison = id().compareTo(key.id());
        if (comparison != 0 || equals(key)) {
            return comparison;
        }
        return componentOrder().compareTo(key.componentOrder());
    }

    private String componentOrder() {
        if (componentOrder == null) {
            componentOrder = template.getComponents().toString();
        }
        return componentOrder;
    }

    @Override
    public String toString() {
        return text();
    }
}

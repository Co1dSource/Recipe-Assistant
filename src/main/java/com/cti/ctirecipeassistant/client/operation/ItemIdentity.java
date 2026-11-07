package com.cti.ctirecipeassistant.client.operation;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Exact stock identity, independent of JEI recipe normalization and optional mods. */
public record ItemIdentity(Item item, CompoundTag tag, CompoundTag capabilities) {
    public static ItemIdentity of(ItemStack stack) {
        var saved = stack.save(new CompoundTag());
        var capabilities = saved.getCompound("ForgeCaps");
        return new ItemIdentity(stack.getItem(), stack.hasTag() ? stack.getTag().copy() : null,
                capabilities.isEmpty() ? null : capabilities);
    }
}

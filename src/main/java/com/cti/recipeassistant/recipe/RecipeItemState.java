package com.cti.recipeassistant.recipe;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

/** Recipe identity normalization for confirmed cosmetic / operating state only. */
public final class RecipeItemState {
    private RecipeItemState() {}

    public static CompoundTag tagForRecipe(ResourceLocation item, CompoundTag tag) {
        if (tag == null || !(item.toString().equals("botania:twig_wand")
                || item.toString().equals("botania:dreamwood_wand"))) return tag;
        CompoundTag result = tag.copy();
        // Botania 1.19.2-440: operating mode, temporary binding position and colors.
        result.remove("bindMode");
        result.remove("boundTileX");
        result.remove("boundTileY");
        result.remove("boundTileZ");
        result.remove("color1");
        result.remove("color2");
        return result.isEmpty() ? null : result;
    }
}

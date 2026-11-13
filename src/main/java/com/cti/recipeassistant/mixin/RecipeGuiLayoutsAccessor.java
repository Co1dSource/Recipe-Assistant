package com.cti.recipeassistant.mixin;

import mezz.jei.gui.recipes.RecipeGuiLayouts;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(value = RecipeGuiLayouts.class, remap = false)
public interface RecipeGuiLayoutsAccessor {
    @Accessor("recipeLayoutsWithButtons")
    List<?> cti$getVisibleLayouts();
}

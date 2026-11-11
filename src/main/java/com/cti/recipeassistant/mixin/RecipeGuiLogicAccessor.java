package com.cti.recipeassistant.mixin;

import mezz.jei.gui.recipes.RecipeGuiLogic;
import mezz.jei.gui.ingredients.IngredientLookupState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = RecipeGuiLogic.class, remap = false)
public interface RecipeGuiLogicAccessor {
    @Accessor("state")
    IngredientLookupState cti$getState();

    /** Restore through JEI's own state-change notification, not direct field writes. */
    @Invoker("setState")
    void cti$restoreState(IngredientLookupState state, boolean saveHistory);
}

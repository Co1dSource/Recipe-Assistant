package com.cti.recipeassistant.mixin;

import mezz.jei.gui.recipes.IRecipeGuiLogic;
import mezz.jei.gui.recipes.RecipeGuiLayouts;
import mezz.jei.gui.recipes.RecipesGui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = RecipesGui.class, remap = false)
public interface RecipesGuiAccessor {
    @Accessor("layouts")
    RecipeGuiLayouts cti$getLayouts();

    @Accessor("logic")
    IRecipeGuiLogic cti$getLogic();

    @Invoker("open")
    void cti$open();

    @Accessor("parentScreen")
    Screen cti$getParentScreen();
}

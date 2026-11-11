package com.cti.recipeassistant.mixin;

import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.gui.recipes.RecipesGui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import mezz.jei.gui.recipes.IRecipeGuiLogic;
import java.util.List;

@Mixin(value = RecipesGui.class, remap = false)
public interface RecipesGuiAccessor {
    @Accessor("recipeLayouts")
    List<IRecipeLayoutDrawable<?>> cti$getRecipeLayouts();

    @Accessor("logic")
    IRecipeGuiLogic cti$getLogic();

    @Invoker("open")
    void cti$open();

    @Accessor("parentScreen")
    Screen cti$getParentScreen();
}

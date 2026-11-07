package com.cti.ctirecipeassistant.mixin;

import com.cti.ctirecipeassistant.client.jei.RecipeInteractions;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.gui.recipes.RecipesGui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.List;

/** Read the public JEI query focus; do not change its navigation or ingredient choice. */
@Mixin(value = RecipesGui.class, remap = false)
public abstract class RecipesGuiFocusMixin {
    @Inject(method = "show(Ljava/util/List;)V", at = @At("HEAD"))
    private void cti$focus(List<IFocus<?>> focuses, CallbackInfo callback) { RecipeInteractions.recordFocus(focuses); }
    @Inject(method = "showTypes(Ljava/util/List;)V", at = @At("HEAD"))
    private void cti$types(List<?> types, CallbackInfo callback) { RecipeInteractions.clearFocus(); }
}

package com.cti.recipeassistant.integration.ae2;

import com.cti.recipeassistant.client.operation.*;
import com.cti.recipeassistant.RecipeAssistant;
import com.cti.recipeassistant.recipe.RecipeNode;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraftforge.common.crafting.StrictNBTIngredient;

/** Transient JEI transfer metadata. Never registered as a recipe or executed on the server. */
final class AeCraftingSelection {
    static ShapedRecipe template(RecipeNode node) {
        var inputs = NonNullList.withSize(9, Ingredient.EMPTY);
        for (int i = 0; i < node.selected.inputs().size(); i++) if (!node.selected.inputs().get(i).isEmpty())
            inputs.set(i, StrictNBTIngredient.of(((ItemStack) node.selectedInput(i).typed().getIngredient()).copy()));
        var output = ((ItemStack) node.selected.output(node.target.key()).typed().getIngredient()).copy();
        // AE2's own CraftingHelper detects this absent id and uses its existing template-item packet path.
        return new ShapedRecipe(new ResourceLocation(RecipeAssistant.MOD_ID, "selection/" + node.selected.id()),
                "", CraftingBookCategory.MISC, 3, 3, inputs, output);
    }
}

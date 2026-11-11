package com.cti.recipeassistant.integration;

import com.cti.recipeassistant.recipe.TreeIngredient;
import com.cti.recipeassistant.recipe.RecipeCandidate;
import com.cti.recipeassistant.recipe.RecipeNode;

/** Navigation contract; core screens never resolve an AE2 screen or menu type. */
public interface PatternConfirmation {
    TreeIngredient ingredientAt(double x, double y);
    void locateProblem();
    boolean canChooseEncodingRecipe(RecipeNode node, RecipeCandidate recipe);
    void chooseEncodingRecipe(RecipeNode node, RecipeCandidate recipe);
}

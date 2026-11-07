package com.cti.ctirecipeassistant.integration;

import com.cti.ctirecipeassistant.client.jei.RecipeInteractions;
import com.cti.ctirecipeassistant.client.operation.ClientRecipeOperation;
import com.cti.ctirecipeassistant.recipe.TreeIngredient;

/** Navigation contract; core screens never resolve an AE2 screen or menu type. */
public interface PatternConfirmation {
    void locateProblem();
    RecipeInteractions.JeiContext jeiContext();
    Object sourceRecipe();
    TreeIngredient target();
    boolean matches(ClientRecipeOperation<?> operation);
    void startFromJei();
}

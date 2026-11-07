package com.cti.ctirecipeassistant.integration;

import com.cti.ctirecipeassistant.recipe.TreeIngredient;

/** Navigation contract; core screens never resolve an AE2 screen or menu type. */
public interface PatternConfirmation {
    TreeIngredient ingredientAt(double x, double y);
    void locateProblem();
}

package com.cti.ctirecipeassistant.client.operation;

import com.cti.ctirecipeassistant.recipe.RecipeNode;
import com.cti.ctirecipeassistant.recipe.TreeIngredient;
import com.mojang.blaze3d.vertex.PoseStack;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.RecipeIngredientRole;
import java.util.*;
import java.util.stream.Stream;

/** JEI still performs the match. Only its alternatives are restricted to the tree selection. */
public final class SelectedRecipeSlots implements IRecipeSlotsView {
    private final List<IRecipeSlotView> slots;
    public SelectedRecipeSlots(IRecipeSlotsView original, RecipeNode node) {
        var inputs = original.getSlotViews(RecipeIngredientRole.INPUT);
        slots = original.getSlotViews().stream().map(slot -> {
            int index = inputs.indexOf(slot);
            return index < 0 || node.selected.inputs().get(index).isEmpty() ? slot
                    : new SelectedSlot(slot, node.selectedInput(index));
        }).toList();
    }
    public List<IRecipeSlotView> getSlotViews() { return slots; }
    public List<IRecipeSlotView> getSlotViews(RecipeIngredientRole role) {
        return slots.stream().filter(slot -> slot.getRole() == role).toList();
    }
    public Optional<IRecipeSlotView> findSlotByName(String name) {
        return slots.stream().filter(slot -> slot.getSlotName().filter(name::equals).isPresent()).findFirst();
    }
    private record SelectedSlot(IRecipeSlotView original, TreeIngredient chosen) implements IRecipeSlotView {
        public <T> Stream<T> getIngredients(IIngredientType<T> type) {
            return type.equals(chosen.typed().getType())
                    ? Stream.of(type.getIngredientClass().cast(chosen.typed().getIngredient())) : Stream.empty();
        }
        public Stream<ITypedIngredient<?>> getAllIngredients() { return Stream.of(chosen.typed()); }
        public boolean isEmpty() { return false; }
        public <T> Optional<T> getDisplayedIngredient(IIngredientType<T> type) { return getIngredients(type).findFirst(); }
        public Optional<ITypedIngredient<?>> getDisplayedIngredient() { return Optional.of(chosen.typed()); }
        public Optional<String> getSlotName() { return original.getSlotName(); }
        public RecipeIngredientRole getRole() { return original.getRole(); }
        public void drawHighlight(PoseStack pose, int color) { original.drawHighlight(pose, color); }
    }
}

package com.cti.recipeassistant.integration.ae2;

import com.cti.recipeassistant.client.operation.*;
import com.cti.recipeassistant.recipe.*;
import com.mojang.blaze3d.vertex.PoseStack;
import mezz.jei.api.gui.ingredient.*;
import mezz.jei.api.ingredients.*;
import mezz.jei.api.recipe.RecipeIngredientRole;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import java.util.*;
import java.util.stream.Stream;

/** Private JEI view: one selected input per slot and exactly the requested output. */
public final class PatternEncodingSlots implements IRecipeSlotsView {
    private final List<IRecipeSlotView> slots;
    public PatternEncodingSlots(IRecipeSlotsView original, RecipeNode node, Map<Integer, Long> amounts) {
        var result = new ArrayList<IRecipeSlotView>();
        int input = 0;
        for (var slot : original.getSlotViews(RecipeIngredientRole.INPUT)) {
            int i = input++;
            result.add(node.selected.inputs().get(i).isEmpty() ? slot
                    : new Slot(slot, quantity(node.selectedInput(i), amounts.getOrDefault(i, node.selectedInput(i).amount()))));
        }
        var target = node.selected.output(node.target.key());
        var output = original.getSlotViews(RecipeIngredientRole.OUTPUT).stream()
                .filter(s -> s.getAllIngredients().anyMatch(t -> sameTarget(t, target))).findFirst().orElseThrow();
        result.add(new Slot(output, target.typed()));
        slots = List.copyOf(result);
    }
    private static boolean sameTarget(ITypedIngredient<?> ingredient, TreeIngredient target) {
        Object a = ingredient.getIngredient(), b = target.typed().getIngredient();
        if (a instanceof ItemStack first && b instanceof ItemStack second) return ItemStack.isSameItemSameTags(first, second);
        if (a instanceof FluidStack first && b instanceof FluidStack second) return first.isFluidEqual(second);
        return ingredient.getType().equals(target.typed().getType()) && a.equals(b);
    }
    private static ITypedIngredient<?> quantity(TreeIngredient ingredient, long amount) {
        var value = ingredient.typed().getIngredient();
        if (value instanceof ItemStack item) { var copy = item.copy(); copy.setCount(Math.toIntExact(amount)); return typed(ingredient, copy); }
        if (value instanceof FluidStack fluid) { var copy = fluid.copy(); copy.setAmount(Math.toIntExact(amount)); return typed(ingredient, copy); }
        return ingredient.typed();
    }
    @SuppressWarnings("unchecked")
    private static <T> ITypedIngredient<T> typed(TreeIngredient source, Object value) {
        IIngredientType<T> type = (IIngredientType<T>) source.typed().getType();
        return new ITypedIngredient<>() {
            public IIngredientType<T> getType() { return type; }
            public T getIngredient() { return type.getIngredientClass().cast(value); }
        };
    }
    public List<IRecipeSlotView> getSlotViews() { return slots; }
    public List<IRecipeSlotView> getSlotViews(RecipeIngredientRole role) { return slots.stream().filter(s -> s.getRole() == role).toList(); }
    public Optional<IRecipeSlotView> findSlotByName(String name) { return slots.stream().filter(s -> s.getSlotName().filter(name::equals).isPresent()).findFirst(); }

    private record Slot(IRecipeSlotView original, ITypedIngredient<?> value) implements IRecipeSlotView {
        public <T> Stream<T> getIngredients(IIngredientType<T> type) { return type.equals(value.getType()) ? Stream.of(type.getIngredientClass().cast(value.getIngredient())) : Stream.empty(); }
        public Stream<ITypedIngredient<?>> getAllIngredients() { return Stream.of(value); }
        public boolean isEmpty() { return false; }
        public <T> Optional<T> getDisplayedIngredient(IIngredientType<T> type) { return getIngredients(type).findFirst(); }
        public Optional<ITypedIngredient<?>> getDisplayedIngredient() { return Optional.of(value); }
        public Optional<String> getSlotName() { return original.getSlotName(); }
        public RecipeIngredientRole getRole() { return original.getRole(); }
        public void drawHighlight(PoseStack pose, int color) { original.drawHighlight(pose, color); }
    }
}

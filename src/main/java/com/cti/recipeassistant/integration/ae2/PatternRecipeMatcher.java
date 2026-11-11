package com.cti.recipeassistant.integration.ae2;

import appeng.api.stacks.*;
import appeng.integration.modules.jei.GenericEntryStackHelper;
import com.cti.recipeassistant.client.jei.TreeJeiPlugin;
import com.cti.recipeassistant.recipe.JeiRecipeIndex;
import com.cti.recipeassistant.recipe.TreeIngredient;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeIngredientRole;
import java.util.*;

/** Output-focused JEI lookup, then exact native input quantities/NBT and all encoded outputs. */
public final class PatternRecipeMatcher {
    private PatternRecipeMatcher() {}
    public static List<PatternDistribution.Route> find(PatternContents contents, JeiRecipeIndex index) {
        if (contents.kind() != PatternContents.Kind.PROCESSING || TreeJeiPlugin.runtime == null) return List.of();
        Object output = GenericEntryStackHelper.stackToIngredient(contents.outputs().get(0));
        if (output == null) return List.of();
        var manager = TreeJeiPlugin.runtime.getIngredientManager();
        var type = manager.getIngredientTypeChecked(output);
        if (type.isEmpty()) return List.of();
        var typed = manager.createTypedIngredient(type.get(), output);
        if (typed.isEmpty()) return List.of();
        var target = TreeIngredient.of(typed.get(), manager);
        var routes = new LinkedHashMap<String, PatternDistribution.Route>();
        for (var candidate : index.find(target)) {
            var layout = index.layout(candidate.id());
            if (matches(contents, layout.getRecipeSlotsView())) {
                var route = new PatternDistribution.Route(candidate.category(), candidate.machine());
                routes.putIfAbsent(route.category(), route);
            }
        }
        return List.copyOf(routes.values());
    }
    static boolean matches(PatternContents contents, IRecipeSlotsView slots) {
        var inputs = alternatives(slots, RecipeIngredientRole.INPUT);
        var outputs = alternatives(slots, RecipeIngredientRole.OUTPUT);
        if (inputs.stream().anyMatch(List::isEmpty) || outputs.isEmpty()) return false;
        var stock = new HashMap<AEKey, Long>();
        for (var input : contents.inputs()) stock.merge(input.what(), input.amount(), Long::sum);
        var products = new HashMap<AEKey, Long>();
        for (var output : contents.outputs()) products.merge(output.what(), output.amount(), Long::sum);
        return inputs(inputs, 0, stock) && outputs(outputs, 0, products);
    }
    private static List<List<GenericStack>> alternatives(IRecipeSlotsView slots, RecipeIngredientRole role) {
        return slots.getSlotViews(role).stream().filter(slot -> !slot.isEmpty()).map(slot -> slot.getAllIngredients()
                .map(GenericEntryStackHelper::ingredientToStack).filter(Objects::nonNull).distinct().toList()).toList();
    }
    private static boolean inputs(List<List<GenericStack>> slots, int position, Map<AEKey, Long> remaining) {
        if (position == slots.size()) return remaining.values().stream().allMatch(amount -> amount == 0);
        for (var option : slots.get(position)) {
            long amount = remaining.getOrDefault(option.what(), 0L);
            if (option.amount() <= 0 || amount < option.amount()) continue;
            remaining.put(option.what(), amount - option.amount());
            boolean matches = inputs(slots, position + 1, remaining);
            remaining.put(option.what(), amount);
            if (matches) return true;
        }
        return false;
    }
    private static boolean outputs(List<List<GenericStack>> slots, int position, Map<AEKey, Long> remaining) {
        if (remaining.values().stream().allMatch(amount -> amount == 0)) return true;
        if (position == slots.size()) return false;
        for (var option : slots.get(position)) {
            long amount = remaining.getOrDefault(option.what(), 0L);
            if (option.amount() <= 0 || amount < option.amount()) continue;
            remaining.put(option.what(), amount - option.amount());
            boolean matches = outputs(slots, position + 1, remaining);
            remaining.put(option.what(), amount);
            if (matches) return true;
        }
        // JEI byproducts deliberately omitted from the encoded pattern are not added back.
        return outputs(slots, position + 1, remaining);
    }
}

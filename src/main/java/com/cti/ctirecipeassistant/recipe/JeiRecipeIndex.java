package com.cti.ctirecipeassistant.recipe;

import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.constants.RecipeTypes;
import net.minecraft.world.item.crafting.CraftingRecipe;
import mezz.jei.api.recipe.*;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class JeiRecipeIndex {
    private final IJeiRuntime runtime;
    private final boolean craftingOnly;
    private final Map<String, List<RecipeCandidate>> cache = new HashMap<>();
    private final Map<String, IRecipeLayoutDrawable<?>> layouts = new HashMap<>();
    public JeiRecipeIndex(IJeiRuntime runtime) { this(runtime, false); }
    public JeiRecipeIndex(IJeiRuntime runtime, boolean craftingOnly) {
        this.runtime = runtime;
        this.craftingOnly = craftingOnly;
    }
    public boolean craftingOnly() { return craftingOnly; }
    public IRecipeLayoutDrawable<?> layout(String id) { return layouts.get(id); }

    public List<RecipeCandidate> find(TreeIngredient target) {
        return cache.computeIfAbsent(target.key(), key -> {
            var factory = runtime.getJeiHelpers().getFocusFactory();
            var focus = factory.createFocus(RecipeIngredientRole.OUTPUT, target.typed());
            var focuses = factory.createFocusGroup(List.of(focus));
            List<RecipeCandidate> result = new ArrayList<>();
            runtime.getRecipeManager().createRecipeCategoryLookup().limitFocus(focuses.getAllFocuses()).get()
                    .filter(category -> !craftingOnly || RecipeTypes.CRAFTING.equals(category.getRecipeType()))
                    .forEach(category -> readCategory(category, focuses, target, result));
            result.sort(Comparator.comparing(RecipeCandidate::category)
                    .thenComparingLong(c -> c.outputAmount(key)).thenComparing(RecipeCandidate::id));
            return List.copyOf(result);
        });
    }
    private <R> void readCategory(IRecipeCategory<R> category, IFocusGroup focuses, TreeIngredient target,
                                  List<RecipeCandidate> result) {
        var manager = runtime.getRecipeManager();
        var machines = manager.createRecipeCatalystLookup(category.getRecipeType()).get()
                .map(i -> TreeIngredient.of(i, runtime.getIngredientManager())).toList();
        manager.createRecipeLookup(category.getRecipeType()).limitFocus(focuses.getAllFocuses()).get().forEach(recipe -> {
            if (craftingOnly && (!(recipe instanceof CraftingRecipe crafting) || !crafting.canCraftInDimensions(3, 3))) return;
            // Empty focus prevents plugins from narrowing alternative input/output ingredients.
            manager.createRecipeLayoutDrawable(category, recipe, runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup())
                    .ifPresent(layout -> {
                        var candidate = readLayout(layout, machines);
                        if (candidate.outputs().stream().flatMap(List::stream).anyMatch(i -> i.key().equals(target.key())))
                            result.add(candidate);
                    });
        });
    }
    public <R> RecipeCandidate readLayout(IRecipeLayoutDrawable<R> layout, TreeIngredient target) {
        var manager = runtime.getRecipeManager();
        var category = layout.getRecipeCategory();
        var full = manager.createRecipeLayoutDrawable(category, layout.getRecipe(),
                        runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup())
                .filter(candidate -> slots(candidate.getRecipeSlotsView(), RecipeIngredientRole.OUTPUT).stream()
                        .flatMap(List::stream).anyMatch(i -> i.key().equals(target.key()))).orElse(layout);
        var machines = manager.createRecipeCatalystLookup(category.getRecipeType()).get()
                .map(i -> TreeIngredient.of(i, runtime.getIngredientManager())).toList();
        return readLayout(full, machines);
    }
    private <R> RecipeCandidate readLayout(IRecipeLayoutDrawable<R> layout, List<TreeIngredient> machines) {
        var category = layout.getRecipeCategory();
        var slots = layout.getRecipeSlotsView();
        var inputs = slots(slots, RecipeIngredientRole.INPUT);
        var outputs = slots(slots, RecipeIngredientRole.OUTPUT);
        var catalysts = slots(slots, RecipeIngredientRole.CATALYST);
        var signatureCatalysts = new ArrayList<>(catalysts);
        if (!machines.isEmpty()) signatureCatalysts.add(machines);
        String categoryId = category.getRecipeType().getUid().toString();
        String signature = categoryId + "|" + category.getRegistryName(layout.getRecipe()) + "|"
                + signature(inputs) + "|" + signature(outputs) + "|" + signature(signatureCatalysts);
        String id = UUID.nameUUIDFromBytes(signature.getBytes(StandardCharsets.UTF_8)).toString();
        layouts.put(id, layout);
        return new RecipeCandidate(id, categoryId, category.getTitle().getString(), inputs, outputs, catalysts);
    }
    private List<List<TreeIngredient>> slots(IRecipeSlotsView slots, RecipeIngredientRole role) {
        var ingredients = runtime.getIngredientManager();
        return slots.getSlotViews(role).stream().map(slot -> slot.getAllIngredients()
                .map(i -> role == RecipeIngredientRole.INPUT ? TreeIngredient.ofInput(i, ingredients)
                        : TreeIngredient.of(i, ingredients)).toList()).toList();
    }
    private String signature(List<List<TreeIngredient>> slots) {
        return slots.stream().map(slot -> slot.stream().map(i -> i.key() + "=" + i.quantity()).sorted().toList().toString()).toList().toString();
    }
}

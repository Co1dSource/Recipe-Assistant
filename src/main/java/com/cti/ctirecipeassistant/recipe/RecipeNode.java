package com.cti.ctirecipeassistant.recipe;

import java.util.*;

public final class RecipeNode {
    public final TreeIngredient target;
    public final RecipeNode parent;
    public final List<RecipeNode> children = new ArrayList<>();
    public List<RecipeCandidate> candidates = List.of();
    public RecipeCandidate selected;
    public boolean loaded;
    public boolean expanded;
    public boolean externalSupply;
    public boolean cycle;
    public final int depth;
    private final JeiRecipeIndex index;
    private final RecipePreferences preferences;

    public RecipeNode(TreeIngredient target, RecipeNode parent, JeiRecipeIndex index, RecipePreferences preferences) {
        this.target = target;
        this.parent = parent;
        this.index = index;
        this.preferences = preferences;
        depth = parent == null ? 0 : parent.depth + 1;
        boolean repeats = false;
        for (RecipeNode p = parent; p != null; p = p.parent) {
            if (target.key().equals(p.target.key())) { repeats = true; break; }
        }
        cycle = repeats;
    }
    public void load() {
        if (loaded || depth >= 64) return;
        loaded = true;
        candidates = index.find(target);
        // Each machine retains its own latest recipe; switching machines does not erase it.
        candidates = candidates.stream().sorted(Comparator.comparing(RecipeCandidate::category)
                .thenComparing(c -> !c.id().equals(preferences.get("machine|" + target.key() + "|" + c.category())))
                .thenComparingLong(c -> c.outputAmount(target.key()))).toList();
        String saved = preferences.get("recipe|" + target.key());
        selected = candidates.stream().filter(c -> c.id().equals(saved)).findFirst().orElse(null);
        if (selected == null && index.craftingOnly()) {
            String crafting = preferences.get("machine|" + target.key() + "|minecraft:crafting");
            selected = candidates.stream().filter(c -> c.id().equals(crafting)).findFirst().orElse(null);
        }
        boolean automatic = selected == null && candidates.size() == 1;
        if (automatic) selected = candidates.get(0);
        cycle = false;
        externalSupply = selected == null;
        if (selected != null) {
            updateCycle();
            if (!cycle) buildChildren();
            // Do not silently turn a missing crafting ingredient into its automatic reverse conversion.
            if (automatic && index.craftingOnly() && children.stream().anyMatch(child -> child.cycle)) {
                selected = null; children.clear(); externalSupply = true;
            }
        }
    }
    /** Cut only this occurrence, without changing saved preferences. */
    public void removeProduction() {
        loaded = true; selected = null; children.clear(); externalSupply = true; expanded = false; cycle = false;
        RecipePreferences.treeChanged();
    }
    public void select(RecipeCandidate candidate) {
        boolean same = selected != null && selected.id().equals(candidate.id());
        preferences.put("recipe|" + target.key(), candidate.id());
        preferences.put("machine|" + target.key() + "|" + candidate.category(), candidate.id());
        if (same) selected = candidate;
        else useRecipe(candidate);
        expanded = true;
    }
    /** Bind a JEI button's exact root without changing the saved tree preference. */
    public void useRecipe(RecipeCandidate candidate) {
        loaded = true;
        externalSupply = false;
        selected = candidate;
        updateCycle();
        if (cycle) children.clear(); else buildChildren();
    }
    private void updateCycle() {
        cycle = false;
        for (RecipeNode ancestor = parent; ancestor != null; ancestor = ancestor.parent)
            if (ancestor.selected != null && target.key().equals(ancestor.target.key())
                    && selected.id().equals(ancestor.selected.id())
                    && selected.category().equals(ancestor.selected.category())) { cycle = true; break; }
    }
    private void buildChildren() {
        children.clear();
        for (int slot = 0; slot < selected.inputs().size(); slot++) {
            var alternatives = selected.inputs().get(slot);
            if (alternatives.isEmpty()) continue;
            var chosen = selectedInput(slot);
            children.add(new RecipeNode(chosen, this, index, preferences));
        }
    }
    public TreeIngredient selectedInput(int slot) {
        var alternatives = selected.inputs().get(slot);
        if (alternatives.size() == 1) return alternatives.get(0);
        String saved = preferences.get(variantKey(slot));
        return alternatives.stream().filter(i -> i.key().equals(saved)).findFirst().orElse(alternatives.get(0));
    }
    public String variantKey(int slot) { return "input|" + target.key() + "|" + selected.category() + "|" + selected.id() + "|" + slot; }
    public void chooseInput(int slot, TreeIngredient ingredient) {
        preferences.put(variantKey(slot), ingredient.key());
        buildChildren();
    }
    public String status() {
        if (externalSupply || selected == null) return "外部供应";
        if (cycle && loaded) return "循环，停止展开";
        if (depth >= 64) return "已达 64 层";
        return selected.machine();
    }
}

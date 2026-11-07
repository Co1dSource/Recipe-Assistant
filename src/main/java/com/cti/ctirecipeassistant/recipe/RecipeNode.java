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
    private int inputSlot = -1;

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
        if (productionCut()) { externalSupply = true; cycle = false; return; }
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
    public void rememberCut() {
        preferences.put(cutKey(), "cut");
        removeProduction();
    }
    public boolean productionCut() { return preferences.get(cutKey()).equals("cut"); }
    public boolean hasConfiguredProduction() {
        return selected != null && (selected.id().equals(preferences.get("recipe|" + target.key()))
                || selected.id().equals(preferences.get("machine|" + target.key() + "|" + selected.category())));
    }
    private String cutKey() {
        if (parent == null) return "external|" + target.key();
        return parent.cutKey() + "|" + parent.selected.id() + "|" + inputSlot + "|" + target.key();
    }
    public void select(RecipeCandidate candidate) {
        boolean same = selected != null && selected.id().equals(candidate.id());
        if (productionCut()) preferences.put(cutKey(), "");
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
    public RecipeNode previewProduction(RecipeCandidate candidate) {
        var preview = new RecipeNode(target, parent, index, preferences);
        preview.inputSlot = inputSlot;
        preview.useRecipe(candidate);
        return preview;
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
            var child = new RecipeNode(chosen, this, index, preferences);
            child.inputSlot = slot;
            children.add(child);
        }
    }
    public String amountKey(int slot) {
        return "pattern-amount|" + selected.id() + "|" + target.key() + "|" + slot;
    }
    public long requiredAmount() {
        return inputSlot < 0 ? target.amount() : parent.inputAmount(inputSlot);
    }
    public long inputAmount(int slot) {
        long original = selectedInput(slot).amount();
        if (selected.category().equals("minecraft:crafting")) return original;
        String saved = preferences.get(amountKey(slot));
        if (saved.matches("[1-9][0-9]{0,9}")) {
            long amount = Long.parseLong(saved);
            if (amount <= Integer.MAX_VALUE) return amount;
        }
        return original;
    }
    public TreeIngredient selectedInput(int slot) {
        var alternatives = selected.inputs().get(slot);
        if (alternatives.size() == 1) return alternatives.get(0);
        String scoped = preferences.get(scopedVariantKey(slot));
        String saved = scoped.isEmpty() ? preferences.get(variantKey(slot)) : scoped;
        if (saved.isEmpty()) return alternatives.get(0);
        return alternatives.stream().filter(i -> i.key().equals(saved) || i.variantId().equals(saved))
                .findFirst().orElse(alternatives.get(0));
    }
    public int inputSlot() { return inputSlot; }
    public List<TreeIngredient> inputVariants() {
        return inputSlot < 0 ? List.of() : parent.selected.inputs().get(inputSlot);
    }
    public String variantKey(int slot) { return "input|" + target.key() + "|" + selected.category() + "|" + selected.id() + "|" + slot; }
    private String scopedVariantKey(int slot) {
        RecipeNode tree = this;
        while (tree.parent != null) tree = tree.parent;
        return variantKey(slot) + "|tree|" + tree.target.key() + "|depth|" + depth;
    }
    public void chooseInputsAtDepth(RecipeNode source, TreeIngredient ingredient) {
        if (source.target.variantId().equals(ingredient.variantId())) return;
        replaceInputsAtDepth(source, ingredient, MaterialRequirements.identity(source.target), MaterialRequirements.identity(ingredient));
    }
    private void replaceInputsAtDepth(RecipeNode source, TreeIngredient ingredient, Object original, Object replacement) {
        if (depth < source.parent.depth) {
            for (var child : children) child.replaceInputsAtDepth(source, ingredient, original, replacement);
            return;
        }
        for (var child : children) {
            if (!MaterialRequirements.identity(child.target).equals(original)) continue;
            var variant = child == source ? ingredient : child.inputVariants().stream()
                    .filter(option -> MaterialRequirements.identity(option).equals(replacement)).findFirst().orElse(null);
            if (variant != null) chooseInput(child.inputSlot, variant);
        }
    }
    public RecipeNode chooseInput(int slot, TreeIngredient ingredient) {
        String identity = ingredient.variantId();
        var chosen = selected.inputs().get(slot).stream().filter(i -> i.variantId().equals(identity)).findFirst();
        if (chosen.isEmpty()) return null;
        for (int i = 0; i < children.size(); i++) {
            var previous = children.get(i);
            if (previous.inputSlot != slot) continue;
            if (previous.target.variantId().equals(identity)) return previous;
            preferences.put(scopedVariantKey(slot), identity);
            var replacement = new RecipeNode(chosen.get(), this, index, preferences);
            replacement.inputSlot = slot;
            replacement.expanded = previous.expanded;
            children.set(i, replacement);
            return replacement;
        }
        return null;
    }
    public String status() {
        if (externalSupply || selected == null) return "外部供应";
        if (cycle && loaded) return "循环，停止展开";
        if (depth >= 64) return "已达 64 层";
        return selected.machine();
    }
}

package com.cti.ctirecipeassistant.recipe;

import com.cti.ctirecipeassistant.client.operation.ItemIdentity;
import net.minecraft.core.Registry;

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
    private final String preferenceIdentity;
    private int inputSlot = -1;

    public RecipeNode(TreeIngredient target, RecipeNode parent, JeiRecipeIndex index, RecipePreferences preferences) {
        this.target = target;
        this.parent = parent;
        this.index = index;
        this.preferences = preferences;
        var identity = MaterialRequirements.identity(target);
        preferenceIdentity = identity instanceof ItemIdentity item
                ? Registry.ITEM.getKey(item.item()) + "|" + item.tag() + "|" + item.capabilities()
                : target.key();
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
                .thenComparing(c -> !c.id().equals(machinePreference(c.category())))
                .thenComparingLong(c -> c.outputAmount(target.key()))).toList();
        if (productionCut()) { externalSupply = true; cycle = false; return; }
        String saved = recipePreference();
        selected = candidates.stream().filter(c -> c.id().equals(saved)).findFirst().orElse(null);
        if (selected == null && index.craftingOnly()) {
            String crafting = machinePreference("minecraft:crafting");
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
        preferences.put(materialCutKey(), "cut");
        treeRoot().applyProduction(MaterialRequirements.identity(target), null);
    }
    public boolean productionCut() {
        String saved = preferences.get(materialCutKey());
        return saved.isEmpty() ? preferences.get(cutKey()).equals("cut") : saved.equals("cut");
    }
    public boolean hasConfiguredProduction() {
        return selected != null && (selected.id().equals(recipePreference())
                || selected.id().equals(machinePreference(selected.category())));
    }
    private String recipePreference() {
        String saved = preferences.get("recipe-item|" + preferenceIdentity);
        return saved.isEmpty() ? preferences.get("recipe|" + target.key()) : saved;
    }
    private String machinePreference(String category) {
        String saved = preferences.get("machine-item|" + category + "|" + preferenceIdentity);
        return saved.isEmpty() ? preferences.get("machine|" + target.key() + "|" + category) : saved;
    }
    private RecipeNode treeRoot() {
        RecipeNode tree = this;
        while (tree.parent != null) tree = tree.parent;
        return tree;
    }
    private String materialCutKey() {
        String rootIdentity = treeRoot().preferenceIdentity;
        return "external-item|" + rootIdentity.length() + "|" + rootIdentity + "|" + preferenceIdentity;
    }
    private String cutKey() {
        if (parent == null) return "external|" + target.key();
        return parent.cutKey() + "|" + parent.selected.id() + "|" + inputSlot + "|" + target.key();
    }
    public void select(RecipeCandidate candidate) {
        preferences.putAll(Map.of(materialCutKey(), "selected",
                "recipe-item|" + preferenceIdentity, candidate.id(),
                "machine-item|" + candidate.category() + "|" + preferenceIdentity, candidate.id()));
        treeRoot().applyProduction(MaterialRequirements.identity(target), candidate);
        expanded = true;
    }
    private void applyProduction(Object identity, RecipeCandidate candidate) {
        if (MaterialRequirements.identity(target).equals(identity)) {
            if (candidate == null) {
                if (candidates.isEmpty() && selected != null) candidates = List.of(selected);
                removeProduction();
                return;
            }
            if (depth >= 64) return;
            if (selected == null || !selected.id().equals(candidate.id())) {
                useRecipe(candidate);
                return;
            }
            selected = candidate;
        }
        for (var child : children) child.applyProduction(identity, candidate);
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
        String scoped = preferences.get(exactVariantKey(slot));
        if (scoped.isEmpty()) scoped = preferences.get(scopedVariantKey(slot));
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
        return variantKey(slot) + "|tree|" + treeRoot().target.key() + "|depth|" + depth;
    }
    private String exactVariantKey(int slot) {
        String rootIdentity = treeRoot().preferenceIdentity;
        return "input-item|" + selected.id() + "|" + slot + "|" + depth + "|"
                + rootIdentity.length() + "|" + rootIdentity + "|" + preferenceIdentity;
    }
    /** Synchronize compatible input slots across the whole tree. */
    public void chooseInputsAtDepth(RecipeNode source, TreeIngredient ingredient) {
        if (source.target.variantId().equals(ingredient.variantId())) return;
        Map<String, String> choices = new HashMap<>();
        replaceInputs(source, ingredient, MaterialRequirements.identity(source.target),
                MaterialRequirements.identity(ingredient), choices);
        preferences.putAll(choices);
    }
    private void replaceInputs(RecipeNode source, TreeIngredient ingredient, Object original, Object replacement,
                               Map<String, String> choices) {
        for (int i = 0; i < children.size(); i++) {
            var child = children.get(i);
            child.replaceInputs(source, ingredient, original, replacement, choices);
            if (!MaterialRequirements.identity(child.target).equals(original)) continue;
            var variant = child == source ? ingredient : child.inputVariants().stream()
                    .filter(option -> MaterialRequirements.identity(option).equals(replacement)).findFirst().orElse(null);
            if (variant != null) {
                choices.put(exactVariantKey(child.inputSlot), variant.variantId());
                replaceInput(i, variant);
            }
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
            preferences.put(exactVariantKey(slot), identity);
            return replaceInput(i, chosen.get());
        }
        return null;
    }
    private RecipeNode replaceInput(int childIndex, TreeIngredient ingredient) {
        var previous = children.get(childIndex);
        var replacement = new RecipeNode(ingredient, this, index, preferences);
        replacement.inputSlot = previous.inputSlot;
        replacement.expanded = previous.expanded;
        children.set(childIndex, replacement);
        return replacement;
    }
    /** Update a slot in the private encoding preview without saving a tree preference. */
    public void editPreviewInput(int slot, TreeIngredient ingredient) {
        int i = 0;
        while (i < children.size() && children.get(i).inputSlot < slot) i++;
        if (i < children.size() && children.get(i).inputSlot == slot) {
            if (ingredient == null) children.remove(i);
            else if (!MaterialRequirements.identity(children.get(i).target).equals(MaterialRequirements.identity(ingredient)))
                replaceInput(i, ingredient);
        } else if (ingredient != null) {
            var child = new RecipeNode(ingredient, this, index, preferences);
            child.inputSlot = slot;
            children.add(i, child);
        }
    }
    public String status() {
        if (externalSupply || selected == null) return "外部供应";
        if (cycle && loaded) return "循环，停止展开";
        if (depth >= 64) return "已达 64 层";
        return selected.machine();
    }
}

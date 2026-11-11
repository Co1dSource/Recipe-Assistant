package com.cti.recipeassistant.recipe;

import com.cti.recipeassistant.client.operation.ItemIdentity;
import net.minecraft.world.item.ItemStack;

import java.util.*;

/** Demand for the selected route, before deducting any real inventory. */
public record MaterialRequirements(List<TreeIngredient> materials, List<TreeIngredient> catalysts, String problem) {
    public static MaterialRequirements calculate(RecipeNode root, long quantity) {
        var ledger = new Ledger();
        try {
            ledger.require(root, quantity);
            return new MaterialRequirements(List.copyOf(ledger.materials.values()),
                    List.copyOf(ledger.catalysts.values()), null);
        } catch (ArithmeticException e) {
            return new MaterialRequirements(List.of(), List.of(), "材料数量超出可计算范围");
        } catch (RouteLimit e) {
            return new MaterialRequirements(List.of(), List.of(), "配方树超过 4096 个计算节点");
        }
    }

    public static long batches(long quantity, long output) { return (quantity - 1) / output + 1; }

    public static long rootOutput(RecipeNode root) {
        long amount = root.selected == null ? 1 : root.selected.outputAmount(root.target.key());
        return amount > 0 && amount != Long.MAX_VALUE ? amount : 1;
    }

    public static Object identity(TreeIngredient ingredient) {
        return ingredient.typed().getIngredient() instanceof ItemStack stack ? ItemIdentity.of(stack) : ingredient.key();
    }

    public static List<TreeIngredient> inputsForBatches(List<TreeIngredient> inputs, long batches) {
        var totals = new LinkedHashMap<Object, TreeIngredient>();
        for (var input : inputs) {
            var scaled = counted(input, Math.multiplyExact(input.amount(), batches));
            totals.merge(identity(input), scaled, (a, b) -> counted(a, Math.addExact(a.amount(), b.amount())));
        }
        return List.copyOf(totals.values());
    }

    private static TreeIngredient counted(TreeIngredient ingredient, long amount) {
        return new TreeIngredient(ingredient.typed(), ingredient.key(), ingredient.name(), amount, ingredient.unit());
    }

    private static final class RouteLimit extends RuntimeException {}

    private static final class Ledger {
        private final Map<Object, TreeIngredient> materials = new LinkedHashMap<>();
        private final Map<Object, TreeIngredient> catalysts = new LinkedHashMap<>();
        private final Map<Object, Long> surplus = new HashMap<>();
        private int visited;

        private void require(RecipeNode node, long amount) {
            if (amount <= 0) return;
            if (++visited > 4096) throw new RouteLimit();
            node.load();
            var target = node.target;
            var key = identity(target);
            var recipe = node.selected;
            long output = recipe == null ? 0 : recipe.outputAmount(target.key());
            if (node.depth >= 64 || node.externalSupply || node.cycle || output <= 0 || output == Long.MAX_VALUE) {
                materials.merge(key, counted(target, amount), (a, b) -> counted(a, Math.addExact(a.amount(), b.amount())));
                return;
            }

            long left = surplus.getOrDefault(key, 0L);
            long used = Math.min(left, amount);
            surplus.put(key, left - used);
            amount -= used;
            if (amount == 0) return;

            long runs = batches(amount, output);
            for (var slot : recipe.catalysts()) if (!slot.isEmpty()) {
                var catalyst = slot.get(0);
                catalysts.merge(identity(catalyst), catalyst, (a, b) -> a.amount() >= b.amount() ? a : b);
            }
            for (var child : node.children)
                require(child, Math.multiplyExact(child.requiredAmount(), runs));
            long remainder = Math.multiplyExact(runs, output) - amount;
            surplus.merge(key, remainder, Math::addExact);
        }
    }
}

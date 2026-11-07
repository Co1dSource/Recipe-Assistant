package com.cti.ctirecipeassistant.client.operation;

import com.cti.ctirecipeassistant.recipe.RecipeNode;
import com.cti.ctirecipeassistant.recipe.TreeIngredient;
import net.minecraft.world.item.ItemStack;
import java.util.*;
import java.util.function.ToLongFunction;

/** Quantity ledger only. Never matches crafting inputs or performs inventory operations. */
public final class CraftPlanner {
    public record Plan(List<RecipeNode> steps, String reason) {}
    private final Map<String, Long> stock;
    private final ToLongFunction<TreeIngredient> available;
    private final List<RecipeNode> steps = new ArrayList<>();
    private final List<RecipeNode> nodes = new ArrayList<>();
    private final Map<String, TreeIngredient> remainders = new HashMap<>();
    private int visited;
    private CraftPlanner(ToLongFunction<TreeIngredient> available) {
        this.stock = new HashMap<>(); this.available = available;
    }

    public static Plan plan(RecipeNode root, Map<String, Long> stock) {
        return plan(root, target -> stock.getOrDefault(target.key(), 0L));
    }
    public static Plan plan(RecipeNode root, ToLongFunction<TreeIngredient> available) {
        var planner = new CraftPlanner(available);
        try {
            planner.validate(root);
            planner.findRemainders();
            planner.craft(root, 1);
            return new Plan(List.copyOf(planner.steps), null);
        } catch (Blocked e) {
            return new Plan(List.of(), e.getMessage());
        }
    }

    private void validate(RecipeNode node) {
        if (++visited > 4096) throw new Blocked("配方树超过 4096 节点，请缩小选择范围");
        node.load();
        nodes.add(node);
        if (node.externalSupply || node.cycle || node.depth >= 64) return;
        if (node.selected != null) {
            if (!node.selected.category().equals("minecraft:crafting"))
                throw new Blocked(node.target.name() + "：" + node.selected.machine() + " 不是 3×3 工作台配方");
            for (var child : node.children) validate(child);
        }
    }

    private void findRemainders() {
        for (var node : nodes) {
            if (!(node.target.typed().getIngredient() instanceof ItemStack stack) || !stack.hasCraftingRemainingItem()) continue;
            var remainder = stack.getCraftingRemainingItem();
            for (var target : nodes) if (target.target.typed().getIngredient() instanceof ItemStack item
                    && ItemStack.isSameItemSameTags(item, remainder)) {
                remainders.put(node.target.key(), new TreeIngredient(target.target.typed(), target.target.key(),
                        target.target.name(), remainder.getCount(), ""));
                break;
            }
        }
    }

    private void require(RecipeNode node, long amount) {
        long owned = stock.computeIfAbsent(node.target.key(), key -> available.applyAsLong(node.target));
        long used = Math.min(owned, amount);
        stock.put(node.target.key(), owned - used);
        long missing = amount - used;
        if (missing == 0) return;
        if (node.externalSupply || node.selected == null) throw new Blocked("缺少原材料：" + node.target.name() + " ×" + missing + "，需要外部供应");
        if (node.cycle) throw new Blocked(node.target.name() + "：生产路线存在循环，仍需外部供应 " + missing);
        if (node.depth >= 64) throw new Blocked(node.target.name() + "：达到内部递归安全上限");
        long output = node.selected.outputAmount(node.target.key());
        if (output <= 0 || output == Long.MAX_VALUE) throw new Blocked(node.target.name() + "：无法确认单次产量");
        long batches = (missing - 1) / output + 1;
        craft(node, batches);
        stock.merge(node.target.key(), batches * output - missing, Long::sum);
    }

    private void craft(RecipeNode node, long batches) {
        if (node.selected == null) throw new Blocked(node.target.name() + "：请先选择配方");
        if (batches > 4096 - steps.size()) throw new Blocked("单次计划超过 4096 次合成");
        // Resolve a complete batch before reserving the next. The shared ledger reuses all leftovers.
        for (long batch = 0; batch < batches; batch++) {
            var demands = new LinkedHashMap<String, Long>();
            var representatives = new LinkedHashMap<String, RecipeNode>();
            for (var child : node.children) {
                String key = child.target.key() + "|" + (child.externalSupply ? "external"
                        : child.selected == null ? "unselected" : child.selected.id());
                demands.merge(key, child.target.amount(), Long::sum); representatives.putIfAbsent(key, child);
            }
            for (var demand : demands.entrySet()) require(representatives.get(demand.getKey()), demand.getValue());
            if (steps.size() >= 4096) throw new Blocked("单次计划超过 4096 次合成");
            steps.add(node);
            for (var child : node.children) {
                var remainder = remainders.get(child.target.key());
                if (remainder != null) {
                    stock.computeIfAbsent(remainder.key(), key -> available.applyAsLong(remainder));
                    stock.merge(remainder.key(), remainder.amount(), Long::sum);
                }
            }
        }
    }

    private static final class Blocked extends RuntimeException {
        Blocked(String reason) { super(reason); }
    }
}

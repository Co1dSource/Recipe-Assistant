package com.cti.ctirecipeassistant.client.operation;

import com.cti.ctirecipeassistant.recipe.RecipeNode;
import com.cti.ctirecipeassistant.recipe.TreeIngredient;
import com.cti.ctirecipeassistant.recipe.MaterialRequirements;
import net.minecraft.world.item.ItemStack;
import java.util.*;
import java.util.function.ToLongFunction;

/** Quantity ledger only. Never matches crafting inputs or performs inventory operations. */
public final class CraftPlanner {
    public static final int MAX_STEPS = 4096;
    public record Plan(List<RecipeNode> steps, String reason) {}
    private record Demand(Object ingredient, String recipe) {}
    private final Map<Object, Long> stock;
    private final ToLongFunction<TreeIngredient> available;
    private final List<RecipeNode> steps = new ArrayList<>();
    private final List<RecipeNode> nodes = new ArrayList<>();
    private final Map<RecipeNode, Object> identities = new IdentityHashMap<>();
    private final Map<Object, TreeIngredient> remainders = new HashMap<>();
    private int visited;
    private CraftPlanner(ToLongFunction<TreeIngredient> available) {
        this.stock = new HashMap<>(); this.available = available;
    }

    public static Plan plan(RecipeNode root, Map<String, Long> stock) {
        return plan(root, target -> stock.getOrDefault(target.key(), 0L));
    }
    public static Plan plan(RecipeNode root, ToLongFunction<TreeIngredient> available) {
        return plan(root, available, 1);
    }
    public static Plan plan(RecipeNode root, ToLongFunction<TreeIngredient> available, long quantity) {
        var planner = new CraftPlanner(available);
        try {
            if (quantity <= 0) throw new Blocked("请输入合成数量");
            planner.validate(root);
            planner.findRemainders();
            planner.craft(root, MaterialRequirements.batches(quantity, MaterialRequirements.rootOutput(root)));
            return new Plan(List.copyOf(planner.steps), null);
        } catch (Blocked e) {
            return new Plan(List.of(), e.getMessage());
        }
    }

    private void validate(RecipeNode node) {
        if (++visited > 4096) throw new Blocked("配方树超过 4096 节点，请缩小选择范围");
        nodes.add(node);
        identities.put(node, MaterialRequirements.identity(node.target));
        node.load();
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
            var identity = ItemIdentity.of(remainder);
            for (var target : nodes) if (identity.equals(identities.get(target))) {
                remainders.put(identities.get(node), new TreeIngredient(target.target.typed(), target.target.key(),
                        target.target.name(), remainder.getCount(), ""));
                break;
            }
        }
    }

    private void require(RecipeNode node, long amount) {
        var identity = identities.get(node);
        long owned = stock.computeIfAbsent(identity, key -> available.applyAsLong(node.target));
        long used = Math.min(owned, amount);
        stock.put(identity, owned - used);
        long missing = amount - used;
        if (missing == 0) return;
        if (node.externalSupply || node.selected == null) throw new Blocked("缺少材料：" + node.target.name() + " ×" + missing);
        if (node.cycle) throw new Blocked(node.target.name() + "：生产路线存在循环，仍需外部供应 " + missing);
        if (node.depth >= 64) throw new Blocked(node.target.name() + "：达到内部递归安全上限");
        long output = node.selected.outputAmount(node.target.key());
        if (output <= 0 || output == Long.MAX_VALUE) throw new Blocked(node.target.name() + "：无法确认单次产量");
        long batches = MaterialRequirements.batches(missing, output);
        craft(node, batches);
        stock.merge(identity, batches * output - missing, Long::sum);
    }

    private void craft(RecipeNode node, long batches) {
        if (node.selected == null) throw new Blocked(node.target.name() + "：请先选择配方");
        if (batches > MAX_STEPS - steps.size()) throw new Blocked("单次计划超过 4096 次合成");
        var demands = new LinkedHashMap<Demand, Long>();
        var representatives = new LinkedHashMap<Demand, RecipeNode>();
        for (var child : node.children) {
            var key = new Demand(identities.get(child), child.externalSupply ? "external"
                    : child.selected == null ? "unselected" : child.selected.id());
            demands.merge(key, child.target.amount(), Long::sum); representatives.putIfAbsent(key, child);
        }
        // Resolve a complete batch before reserving the next. The shared ledger reuses all leftovers.
        for (long batch = 0; batch < batches; batch++) {
            for (var demand : demands.entrySet()) require(representatives.get(demand.getKey()), demand.getValue());
            if (steps.size() >= MAX_STEPS) throw new Blocked("单次计划超过 4096 次合成");
            steps.add(node);
            for (var child : node.children) {
                var remainder = remainders.get(identities.get(child));
                if (remainder != null) {
                    var identity = MaterialRequirements.identity(remainder);
                    stock.computeIfAbsent(identity, key -> available.applyAsLong(remainder));
                    stock.merge(identity, remainder.amount(), Long::sum);
                }
            }
        }
    }

    private static final class Blocked extends RuntimeException {
        Blocked(String reason) { super(reason); }
    }
}

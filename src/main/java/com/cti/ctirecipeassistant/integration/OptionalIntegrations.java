package com.cti.ctirecipeassistant.integration;

import com.cti.ctirecipeassistant.client.operation.*;
import com.cti.ctirecipeassistant.recipe.RecipeNode;
import com.cti.ctirecipeassistant.recipe.MaterialRequirements;
import com.cti.ctirecipeassistant.recipe.TreeIngredient;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.client.gui.screens.Screen;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import net.minecraftforge.fml.ModList;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.function.Consumer;

/** Only this guarded initializer names the optional implementation. */
public final class OptionalIntegrations {
    public record Supply(long stored, boolean craftable) {
        public static final Supply MISSING = new Supply(0, false);
        public boolean needsOrder(long required) { return stored < required && craftable; }
    }
    public interface Ae2 {
        boolean busy();
        void preview(ClientRecipeOperation<?> operation);
        ClientRecipeOperation.Mode mode(AbstractContainerMenu menu);
        boolean crafting(AbstractContainerMenu menu);
        CraftingInventory inventory(AbstractContainerMenu menu);
        Map<ItemIdentity, Long> stock(AbstractContainerMenu menu);
        String unavailable(AbstractContainerMenu menu);
        boolean supportedHandler(IRecipeTransferHandler<?, ?> handler);
        Object recipe(RecipeNode node);
        void takeResult(int slot);
        boolean refillAvailable(AbstractContainerMenu menu);
        Map<Object, Supply> supplies(AbstractContainerMenu menu, List<TreeIngredient> materials);
        boolean refill(AbstractContainerMenu menu, List<TreeIngredient> materials);
        boolean refill(AbstractContainerMenu menu, List<TreeIngredient> orders, List<TreeIngredient> required, Consumer<Screen> finish);
    }
    private static Ae2 ae2;
    private OptionalIntegrations() {}
    public static void initialize() {
        if (ModList.get().isLoaded("ae2")) ae2 = new com.cti.ctirecipeassistant.integration.ae2.Ae2Integration();
    }
    public static boolean busy() { return ae2 != null && ae2.busy(); }
    public static boolean ae2Loaded() { return ae2 != null; }
    public static void preview(ClientRecipeOperation<?> operation) { if (ae2 != null) ae2.preview(operation); }
    public static ClientRecipeOperation.Mode mode(AbstractContainerMenu menu) { return ae2 == null ? null : ae2.mode(menu); }
    public static boolean crafting(AbstractContainerMenu menu) { return ae2 != null && ae2.crafting(menu); }
    public static CraftingInventory inventory(AbstractContainerMenu menu) { return ae2.inventory(menu); }
    public static Map<ItemIdentity, Long> stock(AbstractContainerMenu menu) { return crafting(menu) ? ae2.stock(menu) : Map.of(); }
    public static String unavailable(AbstractContainerMenu menu) { return crafting(menu) ? ae2.unavailable(menu) : null; }
    public static boolean supportedHandler(IRecipeTransferHandler<?, ?> handler) { return ae2.supportedHandler(handler); }
    public static Object recipe(RecipeNode node) { return ae2.recipe(node); }
    public static void takeResult(int slot) { ae2.takeResult(slot); }
    public static boolean refillAvailable(AbstractContainerMenu menu) { return ae2 != null && ae2.refillAvailable(menu); }
    public static Map<Object, Supply> supplies(AbstractContainerMenu menu, List<TreeIngredient> materials) {
        return refillAvailable(menu) ? ae2.supplies(menu, materials) : Map.of();
    }
    public static boolean sourcesCover(List<TreeIngredient> materials, Map<Object, Supply> stock) {
        return !materials.isEmpty() && materials.stream().allMatch(material -> {
            var supply = stock.getOrDefault(MaterialRequirements.identity(material), Supply.MISSING);
            return supply.stored() >= material.amount() || supply.craftable();
        });
    }
    public static List<TreeIngredient> refillOrders(List<TreeIngredient> materials, Map<Object, Supply> stock) {
        var orders = new ArrayList<TreeIngredient>();
        for (var material : materials) {
            var supply = stock.getOrDefault(MaterialRequirements.identity(material), Supply.MISSING);
            if (supply.stored() >= material.amount()) continue;
            if (!supply.needsOrder(material.amount())) return List.of();
            orders.add(material);
        }
        return orders;
    }
    public static List<TreeIngredient> refillOrders(List<TreeIngredient> materials, Map<Object, Supply> stock, long batches) {
        return refillOrders(materials, stock).stream().map(material -> {
            long stored = stock.getOrDefault(MaterialRequirements.identity(material), Supply.MISSING).stored();
            long amount = Math.subtractExact(Math.multiplyExact(material.amount(), batches), stored);
            return new TreeIngredient(material.typed(), material.key(), material.name(), amount, material.unit());
        }).toList();
    }
    public static boolean refill(AbstractContainerMenu menu, List<TreeIngredient> materials) {
        return refillAvailable(menu) && ae2.refill(menu, materials);
    }
    public static boolean refill(AbstractContainerMenu menu, List<TreeIngredient> orders,
                                 List<TreeIngredient> required, Consumer<Screen> finish) {
        return refillAvailable(menu) && ae2.refill(menu, orders, required, finish);
    }
}

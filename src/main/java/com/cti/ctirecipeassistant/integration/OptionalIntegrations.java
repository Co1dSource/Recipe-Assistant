package com.cti.ctirecipeassistant.integration;

import com.cti.ctirecipeassistant.client.operation.*;
import com.cti.ctirecipeassistant.recipe.RecipeNode;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.crafting.CraftingRecipe;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import net.minecraftforge.fml.ModList;
import java.util.Map;

/** Only this guarded initializer names the optional implementation. */
public final class OptionalIntegrations {
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
    }
    private static Ae2 ae2;
    private OptionalIntegrations() {}
    public static void initialize() {
        if (ModList.get().isLoaded("ae2")) ae2 = new com.cti.ctirecipeassistant.integration.ae2.Ae2Integration();
    }
    public static boolean busy() { return ae2 != null && ae2.busy(); }
    public static void preview(ClientRecipeOperation<?> operation) { if (ae2 != null) ae2.preview(operation); }
    public static ClientRecipeOperation.Mode mode(AbstractContainerMenu menu) { return ae2 == null ? null : ae2.mode(menu); }
    public static boolean crafting(AbstractContainerMenu menu) { return ae2 != null && ae2.crafting(menu); }
    public static CraftingInventory inventory(AbstractContainerMenu menu) { return ae2.inventory(menu); }
    public static Map<ItemIdentity, Long> stock(AbstractContainerMenu menu) { return crafting(menu) ? ae2.stock(menu) : Map.of(); }
    public static String unavailable(AbstractContainerMenu menu) { return crafting(menu) ? ae2.unavailable(menu) : null; }
    public static boolean supportedHandler(IRecipeTransferHandler<?, ?> handler) { return ae2.supportedHandler(handler); }
    public static Object recipe(RecipeNode node) { return ae2.recipe(node); }
    public static void takeResult(int slot) { ae2.takeResult(slot); }
}

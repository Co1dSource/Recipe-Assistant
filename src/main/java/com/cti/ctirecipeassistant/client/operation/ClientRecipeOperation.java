package com.cti.ctirecipeassistant.client.operation;

import com.cti.ctirecipeassistant.integration.OptionalIntegrations;
import com.cti.ctirecipeassistant.client.jei.TreeJeiPlugin;
import com.cti.ctirecipeassistant.recipe.RecipeCandidate;
import com.cti.ctirecipeassistant.recipe.TreeIngredient;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.transfer.IRecipeTransferManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.item.crafting.CraftingRecipe;

/** A pending client request. Creating this record never transfers ingredients. */
public record ClientRecipeOperation<R>(Mode mode, R recipe, IRecipeCategory<R> category,
                                      IRecipeSlotsView slotsView, Rect2i recipeArea,
                                      RecipeCandidate candidate, TreeIngredient target,
                                      Screen parentScreen) {
    public static boolean isThreeByThreeCrafting(IRecipeLayoutDrawable<?> layout) {
        return RecipeTypes.CRAFTING.equals(layout.getRecipeCategory().getRecipeType())
                && layout.getRecipe() instanceof CraftingRecipe recipe && recipe.canCraftInDimensions(3, 3);
    }

    public enum Mode {
        ONE_KEY_CRAFT("一键合成"),
        BATCH_PATTERN_ENCODING("批量编码"),
        HIDDEN("");

        private final String tooltip;
        Mode(String tooltip) { this.tooltip = tooltip; }
        public Component tooltip() { return Component.literal(tooltip); }

        public static <R> Mode fromParent(Screen parent, IRecipeLayoutDrawable<R> layout) {
            if (!(parent instanceof AbstractContainerScreen<?> screen)) return HIDDEN;
            var player = Minecraft.getInstance().player;
            if (player == null || player.containerMenu != screen.getMenu() || TreeJeiPlugin.runtime == null)
                return HIDDEN;
            var mode = fromMenu(screen.getMenu(), layout.getRecipeCategory(),
                    TreeJeiPlugin.runtime.getRecipeTransferManager());
            if (mode == ONE_KEY_CRAFT && !isThreeByThreeCrafting(layout)) return HIDDEN;
            return mode;
        }

        public static <R> Mode fromMenu(AbstractContainerMenu menu, IRecipeCategory<R> category,
                                        IRecipeTransferManager transfers) {
            if (menu == null) return HIDDEN;
            var optionalMode = OptionalIntegrations.mode(menu);
            if (optionalMode != null) return optionalMode;
            if (!RecipeTypes.CRAFTING.equals(category.getRecipeType())) return HIDDEN;
            if (!hasCraftingGrid(menu) && !CraftingInventory.supports(menu)) return HIDDEN;
            if (menu.getClass() == CraftingMenu.class) return ONE_KEY_CRAFT;
            return RecipeTypes.CRAFTING.equals(category.getRecipeType())
                    && transfers.getRecipeTransferHandler(menu, category).isPresent() ? ONE_KEY_CRAFT : HIDDEN;
        }

        private static boolean hasCraftingGrid(AbstractContainerMenu menu) {
            var vanillaInput = menu.slots.stream()
                    .filter(slot -> slot.container instanceof CraftingContainer grid
                            && grid.getWidth() == 3 && grid.getHeight() == 3).findFirst();
            if (vanillaInput.isPresent()) {
                var grid = vanillaInput.get().container;
                return menu.slots.stream().filter(slot -> slot.container == grid && slot.isActive())
                        .mapToInt(slot -> slot.getContainerSlot()).filter(index -> index >= 0 && index < 9)
                        .distinct().count() == 9
                        && menu.slots.stream().anyMatch(slot -> slot instanceof ResultSlot && slot.isActive());
            }
            return OptionalIntegrations.crafting(menu);
        }
    }
}

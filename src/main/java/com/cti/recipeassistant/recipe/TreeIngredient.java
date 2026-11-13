package com.cti.recipeassistant.recipe;

import net.minecraft.client.gui.GuiGraphics;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.runtime.IIngredientManager;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraftforge.fluids.FluidStack;
import java.util.List;
import java.util.Objects;

public record TreeIngredient(ITypedIngredient<?> typed, String key, String name, long amount, String unit) {
    public static <T> TreeIngredient of(ITypedIngredient<T> typed, IIngredientManager manager) {
        return of(typed, manager, false);
    }
    public static <T> TreeIngredient ofInput(ITypedIngredient<T> typed, IIngredientManager manager) {
        return of(typed, manager, true);
    }
    private static <T> TreeIngredient of(ITypedIngredient<T> typed, IIngredientManager manager, boolean exactInput) {
        var helper = manager.getIngredientHelper(typed.getType());
        T value = helper.copyIngredient(typed.getIngredient());
        T keyValue = value;
        if (value instanceof ItemStack stack) {
            var tag = RecipeItemState.tagForRecipe(BuiltInRegistries.ITEM.getKey(stack.getItem()), stack.getTag());
            if (!Objects.equals(tag, stack.getTag())) {
                if (exactInput) keyValue = helper.copyIngredient(value);
                ((ItemStack) keyValue).setTag(tag);
            }
        }
        var copy = manager.createTypedIngredient(typed.getType(), value).orElseThrow();
        String key = typed.getType().getIngredientClass().getName() + ":" + helper.getResourceLocation(keyValue) + ":"
                + helper.getUniqueId(keyValue, UidContext.Recipe);
        long amount = 1;
        String unit = "";
        if (keyValue instanceof ItemStack stack) {
            key += ":" + stack.getTag();
            amount = stack.getCount();
        } else if (value instanceof FluidStack fluid) {
            key += ":" + fluid.getTag();
            amount = fluid.getAmount();
            unit = " mB";
        } else {
            unit = " (?)"; // JEI has no generic quantity API for custom ingredient types.
        }
        return new TreeIngredient(copy, key, helper.getDisplayName(value), amount, unit);
    }
    String variantId() {
        return typed.getIngredient() instanceof ItemStack stack ? key + "|" + stack.save(new CompoundTag()) : key;
    }
    public void render(GuiGraphics graphics, IIngredientManager manager) { renderTyped(graphics, manager, typed); }
    private static <T> void renderTyped(GuiGraphics graphics, IIngredientManager manager, ITypedIngredient<T> ingredient) {
        var renderer = manager.getIngredientRenderer(ingredient.getType());
        graphics.pose().pushPose();
        graphics.pose().scale(16f / renderer.getWidth(), 16f / renderer.getHeight(), 1);
        renderer.render(graphics, ingredient.getIngredient());
        graphics.pose().popPose();
    }
    public List<Component> tooltip(IIngredientManager manager) { return tooltipTyped(manager, typed); }
    private static <T> List<Component> tooltipTyped(IIngredientManager manager, ITypedIngredient<T> ingredient) {
        return manager.getIngredientRenderer(ingredient.getType()).getTooltip(ingredient.getIngredient(), TooltipFlag.Default.NORMAL);
    }
    public String quantity() { return amount + unit; }
}

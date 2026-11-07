package com.cti.ctirecipeassistant.recipe;

import com.mojang.blaze3d.vertex.PoseStack;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.runtime.IIngredientManager;
import net.minecraft.network.chat.Component;
import net.minecraft.core.Registry;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraftforge.fluids.FluidStack;
import java.util.List;

public record TreeIngredient(ITypedIngredient<?> typed, String key, String name, long amount, String unit) {
    public static <T> TreeIngredient of(ITypedIngredient<T> typed, IIngredientManager manager) {
        T value = manager.getIngredientHelper(typed.getType()).copyIngredient(typed.getIngredient());
        if (value instanceof ItemStack stack) {
            // Only normalize our private snapshot; never alter the hovered inventory item.
            stack.setTag(RecipeItemState.tagForRecipe(Registry.ITEM.getKey(stack.getItem()), stack.getTag()));
        }
        var copy = manager.createTypedIngredient(typed.getType(), value).orElseThrow();
        String key = typed.getType().getIngredientClass().getName() + ":" + manager.getIngredientHelper(typed.getType()).getResourceLocation(value) + ":"
                + manager.getIngredientHelper(typed.getType()).getUniqueId(value, UidContext.Recipe);
        long amount = 1;
        String unit = "";
        if (value instanceof ItemStack stack) {
            key += ":" + stack.getTag();
            amount = stack.getCount();
        } else if (value instanceof FluidStack fluid) {
            key += ":" + fluid.getTag();
            amount = fluid.getAmount();
            unit = " mB";
        } else {
            unit = " (?)"; // JEI has no generic quantity API for custom ingredient types.
        }
        return new TreeIngredient(copy, key, manager.getIngredientHelper(typed.getType()).getDisplayName(value), amount, unit);
    }
    public void render(PoseStack pose, IIngredientManager manager) { renderTyped(pose, manager, typed); }
    private static <T> void renderTyped(PoseStack pose, IIngredientManager manager, ITypedIngredient<T> ingredient) {
        var renderer = manager.getIngredientRenderer(ingredient.getType());
        pose.pushPose();
        pose.scale(16f / renderer.getWidth(), 16f / renderer.getHeight(), 1);
        renderer.render(pose, ingredient.getIngredient());
        pose.popPose();
    }
    public List<Component> tooltip(IIngredientManager manager) { return tooltipTyped(manager, typed); }
    private static <T> List<Component> tooltipTyped(IIngredientManager manager, ITypedIngredient<T> ingredient) {
        return manager.getIngredientRenderer(ingredient.getType()).getTooltip(ingredient.getIngredient(), TooltipFlag.Default.NORMAL);
    }
    public String quantity() { return amount + unit; }
}

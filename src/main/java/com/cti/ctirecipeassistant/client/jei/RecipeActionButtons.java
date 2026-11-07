package com.cti.ctirecipeassistant.client.jei;

import com.cti.ctirecipeassistant.CtiRecipeAssistant;
import com.cti.ctirecipeassistant.client.operation.ClientRecipeOperation;
import com.cti.ctirecipeassistant.client.operation.RecipeOperationDispatcher;
import com.cti.ctirecipeassistant.mixin.RecipesGuiAccessor;
import com.cti.ctirecipeassistant.recipe.JeiRecipeIndex;
import com.cti.ctirecipeassistant.recipe.TreeIngredient;
import com.mojang.datafixers.util.Either;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.common.Internal;
import mezz.jei.gui.elements.GuiIconButtonSmall;
import mezz.jei.gui.recipes.RecipesGui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.screens.Screen;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.event.RenderTooltipEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.ArrayList;

@Mod.EventBusSubscriber(modid = CtiRecipeAssistant.MOD_ID, value = Dist.CLIENT)
public final class RecipeActionButtons {
    private static final int SIZE = 13;
    private static Component transferTooltipTitle;
    private RecipeActionButtons() {}

    public static Rect2i buttonArea(IRecipeLayoutDrawable<?> layout) {
        var rect = layout.getRect();
        var original = layout.getRecipeTransferButtonArea();
        int x = original.getX();
        int y = rect.getY();
        if (x < original.getX() + original.getWidth() && x + SIZE > original.getX()
                && y < original.getY() + original.getHeight() && y + SIZE > original.getY())
            y = original.getY() - SIZE - 1;
        return new Rect2i(x, y, SIZE, SIZE);
    }

    public static boolean isMouseOver(Rect2i area, double x, double y) {
        // Rect2i.contains includes its lower edge; JEI's native Button does not.
        return x >= area.getX() && x < area.getX() + area.getWidth()
                && y >= area.getY() && y < area.getY() + area.getHeight();
    }

    private static GuiIconButtonSmall button(IRecipeLayoutDrawable<?> layout, ClientRecipeOperation.Mode mode,
                                             Screen parent) {
        var area = buttonArea(layout);
        var textures = Internal.getTextures();
        var button = new GuiIconButtonSmall(area.getX(), area.getY(), SIZE, SIZE,
                textures.getRecipeTransfer(), ignored -> {}, textures);
        button.active = RecipeOperationDispatcher.buttonState(layout, mode, parent).enabled();
        return button;
    }

    @SubscribeEvent
    public static void render(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof RecipesGui gui) || TreeJeiPlugin.runtime == null) return;
        var access = (RecipesGuiAccessor) gui;
        var pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(0, 0, 400);
        IRecipeLayoutDrawable<?> hovered = null;
        var hoveredMode = ClientRecipeOperation.Mode.HIDDEN;
        for (var layout : access.cti$getRecipeLayouts()) {
            var mode = RecipeInteractions.mode(access.cti$getParentScreen(), layout);
            if (mode == ClientRecipeOperation.Mode.HIDDEN) continue;
            button(layout, mode, access.cti$getParentScreen()).render(pose, event.getMouseX(), event.getMouseY(), event.getPartialTick());
            if (isMouseOver(buttonArea(layout), event.getMouseX(), event.getMouseY())) {
                hovered = layout;
                hoveredMode = mode;
            }
        }
        if (hovered != null) {
            var tooltip = new ArrayList<Component>();
            tooltip.add(RecipeInteractions.tooltip(hoveredMode));
            var availability = RecipeOperationDispatcher.buttonState(hovered, hoveredMode, access.cti$getParentScreen());
            var error = availability.transferError();
            if (error != null && error.getType() != IRecipeTransferError.Type.INTERNAL) {
                transferTooltipTitle = RecipeInteractions.tooltip(hoveredMode);
                try {
                    error.showError(pose, event.getMouseX(), event.getMouseY(), hovered.getRecipeSlotsView(),
                            hovered.getRect().getX(), hovered.getRect().getY());
                } finally {
                    transferTooltipTitle = null;
                }
            } else {
                if (availability.state() == RecipeOperationDispatcher.ButtonState.DISABLED_REASON)
                    tooltip.add(availability.reason());
                gui.renderComponentTooltip(pose, tooltip, event.getMouseX(), event.getMouseY());
            }
        }
        pose.popPose();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void click(ScreenEvent.MouseButtonPressed.Pre event) {
        if (event.getButton() != 0 || !(event.getScreen() instanceof RecipesGui gui)
                || TreeJeiPlugin.runtime == null) return;
        var access = (RecipesGuiAccessor) gui;
        for (var layout : access.cti$getRecipeLayouts()) {
            var mode = RecipeInteractions.mode(access.cti$getParentScreen(), layout);
            if (mode == ClientRecipeOperation.Mode.HIDDEN) continue;
            var area = buttonArea(layout);
            if (!isMouseOver(area, event.getMouseX(), event.getMouseY())) continue;
            event.setCanceled(true);
            var button = button(layout, mode, access.cti$getParentScreen());
            if (button.active) {
                button.playDownSound(Minecraft.getInstance().getSoundManager());
                dispatch(layout, mode, access);
            }
            return;
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void transferErrorTooltip(RenderTooltipEvent.GatherComponents event) {
        if (transferTooltipTitle != null && !event.getTooltipElements().isEmpty())
            event.getTooltipElements().set(0, Either.left(transferTooltipTitle));
    }

    private static <R> void dispatch(IRecipeLayoutDrawable<R> layout, ClientRecipeOperation.Mode mode,
                                     RecipesGuiAccessor access) {
        var runtime = TreeJeiPlugin.runtime;
        var slots = layout.getRecipeSlotsView();
        if (RecipeInteractions.choosing()) { RecipeInteractions.accept(layout); return; }
        var target = RecipeInteractions.target(layout);
        if (target.isEmpty()) return;
        var context = new ClientRecipeOperation<>(mode, layout.getRecipe(), layout.getRecipeCategory(), slots,
                layout.getRect(), new JeiRecipeIndex(runtime).readLayout(layout),
                target.get(), access.cti$getParentScreen());
        RecipeOperationDispatcher.dispatch(context);
    }
}

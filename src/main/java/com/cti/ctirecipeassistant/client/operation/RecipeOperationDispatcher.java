package com.cti.ctirecipeassistant.client.operation;
import com.cti.ctirecipeassistant.client.jei.RecipeInteractions;

import com.cti.ctirecipeassistant.integration.OptionalIntegrations;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.recipe.RecipeIngredientRole;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.screens.Screen;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import java.util.Optional;

public final class RecipeOperationDispatcher {
    public enum ButtonState { ENABLED, DISABLED, DISABLED_REASON }
    public record Availability(ButtonState state, Component reason, IRecipeTransferError transferError) {
        public Availability(ButtonState state, Component reason) { this(state, reason, null); }
        public boolean enabled() { return state == ButtonState.ENABLED; }
    }

    private static ClientRecipeOperation<?> pending;
    private RecipeOperationDispatcher() {}

    public static <R> Availability buttonState(IRecipeLayoutDrawable<R> layout, ClientRecipeOperation.Mode mode,
                                              Screen parent) {
        if (RecipeInteractions.choosing()) {
            boolean valid = !OptionalIntegrations.busy() && !CraftExecutor.busy() && RecipeInteractions.target(layout).isPresent();
            return valid ? new Availability(ButtonState.ENABLED, null)
                    : new Availability(ButtonState.DISABLED_REASON, Component.literal("当前配方不能生产正在选择的材料"));
        }
        if (mode == ClientRecipeOperation.Mode.HIDDEN) return new Availability(ButtonState.DISABLED, null);
        if (OptionalIntegrations.busy()) return new Availability(ButtonState.DISABLED_REASON, Component.literal("批量编码正在进行"));
        if (RecipeInteractions.target(layout).isEmpty()) return new Availability(ButtonState.DISABLED_REASON,
                Component.literal("请将鼠标移到需要的目标产物后按 G"));
        if (mode == ClientRecipeOperation.Mode.ONE_KEY_CRAFT) {
            var availability = CraftExecutor.check(layout, parent);
            if (!availability.enabled()) return availability;
        }
        boolean hasOutput = layout.getRecipeSlotsView().getSlotViews(RecipeIngredientRole.OUTPUT).stream()
                .anyMatch(slot -> slot.getAllIngredients().findAny().isPresent());
        return hasOutput ? new Availability(ButtonState.ENABLED, null)
                : new Availability(ButtonState.DISABLED_REASON, Component.literal("配方未提供可选目标产物"));
    }

    public static Optional<ClientRecipeOperation<?>> pending() { return Optional.ofNullable(pending); }
    public static void clear() { pending = null; SingleRecipeTransfer.clear(); RecipeInteractions.clear(); }

    public static void dispatch(ClientRecipeOperation<?> operation) {
        if (RecipeInteractions.accept(operation)) return;
        if (operation.mode() == ClientRecipeOperation.Mode.HIDDEN || OptionalIntegrations.busy()) return;
        if (operation.mode() == ClientRecipeOperation.Mode.ONE_KEY_CRAFT) {
            CraftExecutor.start(operation);
            return;
        }
        pending = operation;
        if (RecipeInteractions.confirmEncoding(operation)) return;
        OptionalIntegrations.preview(operation);
    }
}

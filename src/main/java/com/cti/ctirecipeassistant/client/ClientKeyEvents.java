package com.cti.ctirecipeassistant.client;

import com.cti.ctirecipeassistant.CtiRecipeAssistant;
import com.cti.ctirecipeassistant.client.gui.RecipeTreeScreen;
import com.cti.ctirecipeassistant.integration.PatternConfirmation;

import com.cti.ctirecipeassistant.client.jei.TreeJeiPlugin;
import com.cti.ctirecipeassistant.client.jei.RecipeInteractions;
import com.cti.ctirecipeassistant.client.operation.CraftExecutor;
import com.cti.ctirecipeassistant.integration.OptionalIntegrations;

import mezz.jei.gui.recipes.RecipesGui;
import org.lwjgl.glfw.GLFW;
import net.minecraftforge.eventbus.api.EventPriority;
import com.cti.ctirecipeassistant.recipe.TreeIngredient;
import mezz.jei.api.constants.VanillaTypes;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.ModList;

@Mod.EventBusSubscriber(modid = CtiRecipeAssistant.MOD_ID, value = Dist.CLIENT)
public final class ClientKeyEvents {
    private ClientKeyEvents() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        while (ClientModEvents.OPEN_RECIPE_TREE.get().consumeClick()) {
            if (CraftExecutor.busy() || OptionalIntegrations.busy()) continue;
            var mc = Minecraft.getInstance();
            if (mc.player != null && mc.screen == null) {
                open(mc.player.getMainHandItem(), null);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onScreenKey(ScreenEvent.KeyPressed.Pre event) {
        if (CraftExecutor.busy() || OptionalIntegrations.busy()) return;
        if (event.getKeyCode() == GLFW.GLFW_KEY_ESCAPE && event.getScreen() instanceof RecipesGui
                && RecipeInteractions.cancelSelection()) { event.setCanceled(true); return; }
        if (ClientModEvents.OPEN_RECIPE_TREE.get().matches(event.getKeyCode(), event.getScanCode())
                && openHovered(event.getScreen())) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onScreenMouse(ScreenEvent.MouseButtonPressed.Pre event) {
        if (ClientModEvents.OPEN_RECIPE_TREE.get().matchesMouse(event.getButton())
                && openHovered(event.getScreen())) event.setCanceled(true);
    }

    private static boolean openHovered(Screen screen) {
        if (CraftExecutor.busy() || OptionalIntegrations.busy()) return false;
        var runtime = TreeJeiPlugin.runtime;
        // The preview hotkey acts on its current issue even while its numeric field has focus.
        if (screen instanceof PatternConfirmation preview && runtime != null) { preview.locateProblem(); return true; }
        if (screen.getFocused() instanceof EditBox) return false;
        boolean ftbLoaded = ModList.get().isLoaded("ftblibrary");
        if (ftbLoaded && FtbHoveredIngredient.isTyping(screen)) return false;
        if (runtime == null) { message("jei_unavailable"); return true; }
        if (runtime.getIngredientListOverlay().hasKeyboardFocus()) return false;
        var mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        var window = mc.getWindow();
        double mouseX = mc.mouseHandler.xpos() * window.getGuiScaledWidth() / window.getScreenWidth();
        double mouseY = mc.mouseHandler.ypos() * window.getGuiScaledHeight() / window.getScreenHeight();
        if (RecipeInteractions.key(screen, mouseX, mouseY)) return true;
        ItemStack target = runtime.getIngredientListOverlay().getIngredientUnderMouse().flatMap(i -> i.getItemStack())
                .or(() -> runtime.getBookmarkOverlay().getIngredientUnderMouse().flatMap(i -> i.getItemStack()))
                .or(() -> runtime.getRecipesGui().getIngredientUnderMouse(VanillaTypes.ITEM_STACK))
                .or(() -> runtime.getScreenHelper().getClickableIngredientUnderMouse(screen, mouseX, mouseY)
                        .flatMap(i -> i.getTypedIngredient().getItemStack().stream()).filter(i -> !i.isEmpty()).findFirst())
                .orElse(ItemStack.EMPTY);
        if (target.isEmpty() && ftbLoaded) target = FtbHoveredIngredient.get(screen, mouseX, mouseY);
        if (target.isEmpty() && screen instanceof AbstractContainerScreen<?> container) {
            var slot = container.getSlotUnderMouse();
            if (slot != null && slot.hasItem()) target = slot.getItem();
        }
        if (target.isEmpty()) target = mc.player.getMainHandItem();
        return open(target, screen);
    }

    private static boolean open(ItemStack target, Screen previous) {
        var mc = Minecraft.getInstance();
        var runtime = TreeJeiPlugin.runtime;
        if (mc.player == null) return false;
        if (runtime == null) { message("jei_unavailable"); return true; }
        if (target.isEmpty()) { RecipeInteractions.restore(previous); return true; }
        var typed = runtime.getIngredientManager().createTypedIngredient(VanillaTypes.ITEM_STACK, target.copy());
        if (typed.isEmpty()) { message("invalid_target"); return true; }
        var ingredient = TreeIngredient.of(typed.get(), runtime.getIngredientManager());
        RecipeInteractions.openItem(ingredient, previous);
        return true;
    }

    private static void message(String reason) {
        var player = Minecraft.getInstance().player;
        if (player != null) player.displayClientMessage(Component.translatable("message.ctirecipeassistant." + reason), true);
    }
}

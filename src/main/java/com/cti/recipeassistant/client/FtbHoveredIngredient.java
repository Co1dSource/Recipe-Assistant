package com.cti.recipeassistant.client;

import dev.ftb.mods.ftblibrary.ui.IScreenWrapper;
import dev.ftb.mods.ftblibrary.ui.Panel;
import dev.ftb.mods.ftblibrary.ui.TextBox;
import dev.ftb.mods.ftblibrary.util.client.PositionedIngredient;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;

/** Loaded only when FTB Library is installed. */
final class FtbHoveredIngredient {
    private FtbHoveredIngredient() {}

    static boolean isTyping(Screen screen) {
        if (!(screen instanceof IScreenWrapper wrapper)) return false;
        var gui = wrapper.getGui();
        return isTyping(gui.getContextMenu().<Panel>map(menu -> menu).orElse(gui));
    }

    private static boolean isTyping(Panel panel) {
        for (var widget : panel.getWidgets()) {
            if (!widget.isEnabled()) continue;
            if (widget instanceof TextBox text && text.isFocused()) return true;
            if (widget instanceof Panel child && isTyping(child)) return true;
        }
        return false;
    }

    static ItemStack get(Screen screen, double mouseX, double mouseY) {
        if (!(screen instanceof IScreenWrapper wrapper)) return ItemStack.EMPTY;
        var gui = wrapper.getGui();
        gui.updateMouseOver((int) mouseX, (int) mouseY);
        var panel = gui.getContextMenu().<Panel>map(menu -> menu).orElse(gui);
        var object = panel.getIngredientUnderMouse().map(PositionedIngredient::ingredient).orElse(null);
        return object instanceof ItemStack stack ? stack : ItemStack.EMPTY;
    }
}

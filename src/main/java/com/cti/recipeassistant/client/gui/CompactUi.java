package com.cti.recipeassistant.client.gui;

import com.cti.recipeassistant.client.jei.TreeJeiPlugin;
import com.cti.recipeassistant.integration.OptionalIntegrations.Supply;
import com.cti.recipeassistant.recipe.TreeIngredient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;

/** Vanilla-style neutral surfaces, shared by custom screens and terminal overlays. */
public final class CompactUi {
    private CompactUi() {}
    public static void panel(GuiGraphics graphics, int x, int y, int w, int h) {
        graphics.fill(x, y, x + w, y + h, 0xFF373737);
        graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFFFFFFFF);
        graphics.fill(x + 3, y + 3, x + w - 1, y + h - 1, 0xFF555555);
        graphics.fill(x + 3, y + 3, x + w - 3, y + h - 3, 0xFFC6C6C6);
    }
    public static void slot(GuiGraphics graphics, int x, int y) {
        graphics.fill(x, y, x + 18, y + 18, 0xFF373737);
        graphics.fill(x + 1, y + 1, x + 18, y + 18, 0xFFFFFFFF);
        graphics.fill(x + 1, y + 1, x + 17, y + 17, 0xFF8B8B8B);
    }
    public static void button(GuiGraphics graphics, int x, int y, int w, int h, boolean hover, boolean enabled) {
        graphics.fill(x, y, x + w, y + h, 0xFF373737);
        graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, enabled && hover ? 0xFFEEEEEE : 0xFFAAAAAA);
        graphics.fill(x + 2, y + 2, x + w - 2, y + h - 2, enabled && hover ? 0xFF999999 : 0xFF707070);
    }
    public static boolean hit(double x, double y, int l, int t, int w, int h) {
        return x >= l && x < l + w && y >= t && y < t + h;
    }
    public static void icon(GuiGraphics graphics, TreeIngredient ingredient, int x, int y) {
        graphics.pose().pushPose(); graphics.pose().translate(x, y, 0);
        ingredient.render(graphics, TreeJeiPlugin.runtime.getIngredientManager());
        graphics.pose().popPose();
    }
    public static void material(GuiGraphics graphics, Font font, TreeIngredient ingredient, Supply supply,
                                int x, int y, int width, boolean dark) {
        icon(graphics, ingredient, x, y);
        String amount = supply == null ? "×" + ingredient.quantity() : supply.stored() + "/" + ingredient.quantity();
        int color = supply == null || supply.stored() >= ingredient.amount() ? (dark ? 0xFFFFFF : 0x303030)
                : supply.craftable() ? (dark ? 0x5555FF : 0x2525AC) : (dark ? 0xFF5555 : 0xA02020);
        nameAndQuantity(graphics, font, ingredient.name(), amount, x + 20, y + 4, width - 20, color, color);
    }
    public static void nameAndQuantity(GuiGraphics graphics, Font font, String name, String amount,
                                       int x, int y, int width, int nameColor, int amountColor) {
        String label = fittedName(font, name, amount, width);
        int offset = font.width(label) + (label.isEmpty() ? 0 : 4);
        graphics.drawString(font, label, x, y, nameColor, false);
        graphics.drawString(font, font.plainSubstrByWidth(amount, Math.max(0, width - offset)), x + offset, y, amountColor, false);
    }
    public static int quantityOffset(Font font, String name, String amount, int width) {
        String label = fittedName(font, name, amount, width);
        return font.width(label) + (label.isEmpty() ? 0 : 4);
    }
    private static String fittedName(Font font, String name, String amount, int width) {
        return font.plainSubstrByWidth(name, Math.max(0, width - font.width(amount) - 4));
    }
}

package com.cti.ctirecipeassistant.client.gui;

import com.cti.ctirecipeassistant.client.jei.RecipeInteractions;
import com.cti.ctirecipeassistant.client.operation.CraftExecutor;
import com.cti.ctirecipeassistant.integration.OptionalIntegrations;
import com.cti.ctirecipeassistant.integration.OptionalIntegrations.Supply;
import com.cti.ctirecipeassistant.recipe.MaterialRequirements;
import com.cti.ctirecipeassistant.recipe.TreeIngredient;
import com.mojang.blaze3d.vertex.PoseStack;
import mezz.jei.gui.recipes.RecipesGui;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.util.Map;

public final class CraftQuantityScreen extends Screen {
    private static final int ROW = 18;
    private final RecipeInteractions.JeiContext jei;
    private final RecipeTreeScreen tree;
    private final boolean refill;
    private final AbstractContainerMenu menu;
    private String input;
    private int scroll, left, top, panelWidth, visibleRows;
    private long quantity;
    private EditBox amount;
    private Button confirm;
    private CraftExecutor.Session craft;
    private MaterialRequirements requirements;
    private Map<Object, Supply> stock = Map.of();
    private boolean terminal;

    public CraftQuantityScreen(RecipesGui parent, RecipeTreeScreen tree, boolean refill) {
        super(Component.literal(refill ? "ME 补料" : "一键合成"));
        jei = RecipeInteractions.captureJei(parent);
        this.tree = tree;
        this.refill = refill;
        menu = jei.parent() instanceof AbstractContainerScreen<?> screen ? screen.getMenu() : null;
        input = Long.toString(Math.min(Integer.MAX_VALUE, tree.targetQuantity()));
    }

    @Override protected void init() {
        panelWidth = Math.min(216, width - 12);
        left = (width - panelWidth) / 2;
        visibleRows = Math.max(1, Math.min(5, (height - 96) / ROW));
        top = (height - panelHeight()) / 2;
        if (!refill) craft = new CraftExecutor.Session(jei.parent(), tree.finalRoot(), tree.index());
        amount = addRenderableWidget(new EditBox(font, left + 8, top + 32, 82, 17, Component.literal("目标数量")));
        amount.setMaxLength(10);
        amount.setFilter(value -> value.chars().allMatch(c -> c >= '0' && c <= '9'));
        amount.setValue(input);
        int buttonsY = top + panelHeight() - 25;
        confirm = addRenderableWidget(new Button(left + panelWidth - 106, buttonsY, 46, 18,
                Component.literal("确认"), button -> execute()));
        addRenderableWidget(new Button(left + panelWidth - 55, buttonsY, 46, 18,
                Component.literal("取消"), button -> onClose()));
        amount.setResponder(this::inputChanged);
        setInitialFocus(amount);
        inputChanged(input);
    }

    private int panelHeight() { return 95 + visibleRows * ROW; }
    private int listTop() { return top + 66; }

    public TreeIngredient ingredientAt(double x, double y) {
        if (CompactUi.hit(x, y, left + 9, top + 9, 16, 16)) return tree.finalRoot().target;
        var rows = requirements.materials();
        for (int i = 0; i < visibleRows && scroll + i < rows.size(); i++)
            if (CompactUi.hit(x, y, left + 8, listTop() + i * ROW, 16, 16)) return rows.get(scroll + i);
        return null;
    }

    private void inputChanged(String value) {
        input = value;
        long parsed = value.isEmpty() ? 0 : Long.parseLong(value);
        quantity = parsed <= Integer.MAX_VALUE ? parsed : 0;
        tree.targetQuantity(quantity);
        requirements = tree.requirements();
        scroll = Math.min(scroll, Math.max(0, requirements.materials().size() - visibleRows));
        refresh();
    }

    @Override public void tick() { amount.tick(); refresh(); }

    private boolean sessionOpen() {
        return minecraft.player != null && (menu == null || minecraft.player.containerMenu == menu);
    }

    private void refresh() {
        if (refill) {
            terminal = sessionOpen() && OptionalIntegrations.refillAvailable(menu);
            stock = terminal ? OptionalIntegrations.supplies(menu, requirements.materials()) : Map.of();
            confirm.active = terminal && quantity > 0 && tree.finalRoot().selected != null
                    && requirements.problem() == null && !OptionalIntegrations.busy()
                    && !CraftExecutor.busy() && !OptionalIntegrations.refillOrders(requirements.materials(), stock).isEmpty();
        } else {
            craft.refresh();
            confirm.active = quantity > 0 && quantity <= craft.maximum();
        }
    }

    private void execute() {
        refresh();
        if (!confirm.active) return;
        boolean started = refill ? OptionalIntegrations.refill(menu, requirements.materials()) : craft.start(quantity);
        if (started) minecraft.setScreen(jei.parent());
    }

    @Override public void render(PoseStack pose, int mouseX, int mouseY, float partialTick) {
        renderBackground(pose);
        CompactUi.panel(pose, left, top, panelWidth, panelHeight());
        var target = tree.finalRoot().target;
        CompactUi.slot(pose, left + 8, top + 8);
        CompactUi.icon(pose, target, left + 9, top + 9);
        label(pose, target.name(), left + 31, top + 12, panelWidth - 40, 0x303030);
        if (!refill) label(pose, "可合成：" + craft.maximum(), left + 8, top + 53, panelWidth - 16, 0x303030);
        else if (!terminal) label(pose, "需要打开已同步的 ME 终端", left + 8, top + 53, panelWidth - 16, 0x303030);

        var rows = requirements.materials();
        for (int i = 0; i < visibleRows && scroll + i < rows.size(); i++) {
            var ingredient = rows.get(scroll + i);
            var supply = terminal ? stock.getOrDefault(MaterialRequirements.identity(ingredient), Supply.MISSING) : null;
            CompactUi.material(pose, font, ingredient, supply, left + 8, listTop() + i * ROW, panelWidth - 16, false);
        }
        if (requirements.problem() != null) label(pose, requirements.problem(), left + 8, listTop() + 4, panelWidth - 16, 0xA02020);
        super.render(pose, mouseX, mouseY, partialTick);
        if (!refill && craft.problem() != null && CompactUi.hit(mouseX, mouseY, left + panelWidth - 106,
                top + panelHeight() - 25, 46, 18))
            renderTooltip(pose, Component.literal(craft.problem()), mouseX, mouseY);
    }

    private void label(PoseStack pose, String text, int x, int y, int maxWidth, int color) {
        font.draw(pose, font.plainSubstrByWidth(text, maxWidth), x, y, color);
    }

    @Override public boolean mouseScrolled(double x, double y, double delta) {
        if (!CompactUi.hit(x, y, left + 8, listTop(), panelWidth - 16, visibleRows * ROW)) return false;
        scroll = Math.max(0, Math.min(requirements.materials().size() - visibleRows, scroll + (delta > 0 ? -1 : 1)));
        return true;
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        if (button == 1 && CompactUi.hit(x, y, left + 8, top + 8, panelWidth - 16, 18)) {
            tree.returnTo(this);
            minecraft.setScreen(tree);
            return true;
        }
        return super.mouseClicked(x, y, button);
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override public void onClose() {
        if (sessionOpen()) RecipeInteractions.restoreJei(jei);
        else minecraft.setScreen(null);
    }
}

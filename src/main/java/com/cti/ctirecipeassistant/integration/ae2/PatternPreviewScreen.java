package com.cti.ctirecipeassistant.integration.ae2;

import appeng.api.stacks.GenericStack;
import appeng.api.stacks.AEItemKey;
import appeng.client.gui.Icon;
import appeng.client.gui.widgets.ToggleButton;
import appeng.core.localization.ButtonToolTips;
import appeng.api.stacks.AmountFormat;
import com.cti.ctirecipeassistant.client.gui.CompactUi;
import com.cti.ctirecipeassistant.client.gui.RecipeTreeScreen;
import com.cti.ctirecipeassistant.client.operation.*;
import com.cti.ctirecipeassistant.client.jei.RecipeInteractions;
import com.cti.ctirecipeassistant.client.jei.TreeJeiPlugin;
import com.cti.ctirecipeassistant.integration.PatternConfirmation;
import com.cti.ctirecipeassistant.recipe.*;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.systems.RenderSystem;
import mezz.jei.api.constants.VanillaTypes;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Displays the exact input selection used by PatternEncoder; no recipe text inference. */
public final class PatternPreviewScreen extends Screen implements PatternConfirmation {
    private static final int LIST_TOP = 42, INPUT_ROW = 20;
    private final ClientRecipeOperation<?> operation;
    private final RecipeTreeScreen tree;
    private final RecipeInteractions.JeiContext jeiContext;
    private final List<TreeIngredient> targets;
    private int targetIndex, problemIndex, scroll;
    private PatternEncoder.Preview preview;
    private final Map<String, PatternEncoder.Substitutions> substitutions = new HashMap<>();
    private String error = "";
    private List<Component> tooltip = List.of();
    public PatternPreviewScreen(ClientRecipeOperation<?> operation) {
        super(Component.literal("批量编码"));
        this.operation = operation; tree = RecipeInteractions.tree(operation);
        jeiContext = RecipeInteractions.captureJei((mezz.jei.gui.recipes.RecipesGui) TreeJeiPlugin.runtime.getRecipesGui());
        var unique = new LinkedHashMap<String, TreeIngredient>();
        for (var slot : operation.candidate().outputs()) for (var target : slot) unique.putIfAbsent(target.key(), target);
        targets = List.copyOf(unique.values());
        for (int i = 0; i < targets.size(); i++) if (targets.get(i).key().equals(operation.target().key())) targetIndex = i;
        rebuild(false);
    }
    private TreeIngredient target() { return tree.finalRoot().target; }
    private void confirm() {
        rebuild(false);
        error = Optional.ofNullable(PatternEncoder.start(preview)).orElse("");
    }
    private void rebuild(boolean reset) {
        preview = PatternEncoder.prepare(operation, tree.finalRoot(), tree.index(), tree.preferences());
        for (var entry : preview.entries()) if (entry.crafting)
            entry.substitutions = substitutions.getOrDefault(entry.patternKey(), entry.substitutions);
        if (reset) { scroll = 0; problemIndex = 0; }
        problemIndex = Math.min(problemIndex, Math.max(0, preview.issues().size() - 1));
        scroll = Math.min(scroll, maxScroll());
    }
    public void locateProblem() {
        var current = currentIssue(); if (current.isEmpty()) return;
        var issue = current.get(); tree.focus(issue.node());
        RecipeInteractions.choose(issue.node(), tree, this, () -> { tree.refresh(); rebuild(false); });
    }
    public Optional<PatternPlanner.Issue> currentIssue() {
        return preview.issues().isEmpty() ? Optional.empty() : Optional.of(preview.issues().get(problemIndex));
    }
    @Override public TreeIngredient ingredientAt(double x, double y) {
        int left = left() + 10;
        if (CompactUi.hit(x, y, left, 18, 16, 16)) return target();
        if (CompactUi.hit(x, y, left, height - 68, 16, 16) && !preview.issues().isEmpty())
            return currentIssue().orElseThrow().node().target;
        if (y < LIST_TOP || y >= listBottom()) return null;
        int top = LIST_TOP - scroll;
        for (var entry : preview.entries()) {
            int bottom = top + rowHeight(entry);
            if (y >= top && y < bottom && entry.problem == null) {
                int outputX = entry.crafting ? left + 76 : left + inputWidth() + 18;
                if (CompactUi.hit(x, y, outputX, top + 25, 16, 16)) return entry.output();
                if (entry.crafting) {
                    for (int i = 0; i < 9; i++)
                        if (CompactUi.hit(x, y, left + i % 3 * 18 + 1, top + 21 + i / 3 * 18, 16, 16)
                                && !entry.node.selected.inputs().get(i).isEmpty()) return entry.node.selectedInput(i);
                } else {
                    var inputs = entry.inputs();
                    int columns = columns(), cell = inputWidth() / columns;
                    for (int i = 0; i < inputs.size(); i++)
                        if (CompactUi.hit(x, y, left + i % columns * cell, top + 21 + i / columns * INPUT_ROW, 16, 16)
                                && inputs.get(i).what() instanceof AEItemKey item) {
                            var manager = TreeJeiPlugin.runtime.getIngredientManager();
                            return manager.createTypedIngredient(VanillaTypes.ITEM_STACK, item.toStack())
                                    .map(typed -> TreeIngredient.of(typed, manager)).orElse(null);
                        }
                }
                return null;
            }
            top = bottom;
        }
        return null;
    }
    private int left() { return (width - span()) / 2; }
    private int span() { return Math.min(334, width - 16); }
    private int listBottom() { return height - (preview.issues().isEmpty() && error.isEmpty() ? 35 : 76); }
    private int inputWidth() { return Math.max(54, span() - 112); }
    private int columns() { return Math.max(1, inputWidth() / 104); }
    private int rowHeight(PatternEncoder.Entry entry) {
        if (entry.problem != null) return 42;
        return entry.crafting ? 80 : 26 + Math.max(1, (entry.inputs().size() + columns() - 1) / columns()) * INPUT_ROW;
    }
    private int maxScroll() { return Math.max(0, preview.entries().stream().mapToInt(this::rowHeight).sum() - Math.max(1, listBottom() - LIST_TOP)); }
    @Override protected void init() {
        clearWidgets(); int l = left();
        if (targets.size() > 1) addRenderableWidget(new Button(l + span() - 28, 18, 18, 18, Component.literal(">"), b -> {
            targetIndex = (targetIndex + 1) % targets.size(); tree.retarget(targets.get(targetIndex), operation.candidate());
            rebuild(true); init();
        }));
        int footerX = l + span() - 112;
        addRenderableWidget(new Button(footerX, height - 30, 48, 17, Component.literal("确定"), b -> confirm()));
        addRenderableWidget(new Button(footerX + 53, height - 30, 48, 17, Component.literal("取消"), b -> onClose()));
    }
    private ToggleButton substitutionButton(PatternEncoder.Entry entry, boolean fluids, int x, int y) {
        var button = new ToggleButton(fluids ? Icon.FLUID_SUBSTITUTION_ENABLED : Icon.SUBSTITUTION_ENABLED,
                fluids ? Icon.FLUID_SUBSTITUTION_DISABLED : Icon.SUBSTITUTION_DISABLED, enabled -> {
                    var selected = entry.substitutions;
                    entry.substitutions = fluids ? new PatternEncoder.Substitutions(selected.items(), enabled)
                            : new PatternEncoder.Substitutions(enabled, selected.fluids());
                    substitutions.put(entry.patternKey(), entry.substitutions);
                });
        button.x = x;
        button.y = y;
        button.setState(fluids ? entry.substitutions.fluids() : entry.substitutions.items());
        button.setTooltipOn(fluids
                ? List.of(ButtonToolTips.FluidSubstitutions.text(), ButtonToolTips.FluidSubstitutionsDescEnabled.text())
                : List.of(ButtonToolTips.SubstitutionsOn.text(), ButtonToolTips.SubstitutionsDescEnabled.text()));
        button.setTooltipOff(fluids
                ? List.of(ButtonToolTips.FluidSubstitutions.text(), ButtonToolTips.FluidSubstitutionsDescDisabled.text())
                : List.of(ButtonToolTips.SubstitutionsOff.text(), ButtonToolTips.SubstitutionsDescDisabled.text()));
        return button;
    }
    private String craftingTitle(int number) { return number + ". 合成样板"; }
    private int substitutionX(int number) { return left() + 10 + font.width(craftingTitle(number)) + 18; }

    private void renderSubstitution(PoseStack pose, ToggleButton button, int mx, int my, float partial) {
        button.render(pose, mx, my, partial);
        if (my >= LIST_TOP && my < listBottom() && button.isMouseOver(mx, my))
            tooltip = button.getTooltipMessage();
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (CompactUi.hit(mouseX, mouseY, left() + 6, LIST_TOP, span() - 12, Math.max(1, listBottom() - LIST_TOP))) {
            scroll = Math.max(0, Math.min(maxScroll(), scroll + (delta > 0 ? -26 : 26))); return true;
        }
        if (!preview.issues().isEmpty() && CompactUi.hit(mouseX, mouseY, left() + 6, height - 73, span() - 12, 37)) {
            problemIndex = Math.max(0, Math.min(preview.issues().size() - 1, problemIndex + (delta > 0 ? -1 : 1))); return true;
        }
        return false;
    }
    @Override public boolean mouseClicked(double x, double y, int button) {
        if (button == 0 && y >= LIST_TOP && y < listBottom()) {
            int top = LIST_TOP - scroll, number = 1;
            for (var entry : preview.entries()) {
                if (entry.problem == null && y >= top + 1 && y < top + 17) {
                    int right = left() + span() - 10;
                    if (entry.crafting) {
                        int toggleX = substitutionX(number);
                        if (substitutionButton(entry, false, toggleX, top + 1).mouseClicked(x, y, button)
                                || substitutionButton(entry, true, toggleX + 18, top + 1).mouseClicked(x, y, button))
                            return true;
                    } else if (CompactUi.hit(x, y, right - 18, top + 1, 18, 16)) {
                        minecraft.setScreen(new AmountScreen(this, entry)); return true;
                    }
                }
                top += rowHeight(entry); number++;
            }
        }
        return super.mouseClicked(x, y, button);
    }
    @Override public void tick() {
        if (minecraft.player == null || minecraft.player.containerMenu != preview.menu()) minecraft.setScreen(null);
    }
    private void text(PoseStack pose, String value, int x, int y, int w, int color) {
        font.draw(pose, font.plainSubstrByWidth(value, Math.max(1, w)), x, y, color);
    }
    private void ingredient(PoseStack pose, TreeIngredient ingredient, int x, int y, int mx, int my) {
        pose.pushPose(); pose.translate(x, y, 0); ingredient.render(pose, TreeJeiPlugin.runtime.getIngredientManager()); pose.popPose();
        if (my >= LIST_TOP && my < listBottom() && CompactUi.hit(mx, my, x, y, 16, 16))
            tooltip = ingredient.tooltip(TreeJeiPlugin.runtime.getIngredientManager());
    }
    private void input(PoseStack pose, GenericStack input, int x, int y, int w, int mx, int my) {
        itemRenderer.renderAndDecorateItem(input.what().wrapForDisplayOrFilter(), x, y);
        String quantity = input.what().formatAmount(input.amount(), AmountFormat.FULL);
        CompactUi.nameAndQuantity(pose, font, input.what().getDisplayName().getString(), "×" + quantity,
                x + 20, y + 4, w - 22, 0x303030, 0x555555);
        if (my >= LIST_TOP && my < listBottom() && CompactUi.hit(mx, my, x, y, w, INPUT_ROW))
            tooltip = List.of(input.what().getDisplayName(), Component.literal("×" + quantity));
    }
    private void row(PoseStack pose, PatternEncoder.Entry entry, int number, int top, int mx, int my, float partial) {
        int l = left() + 10, w = span() - 20;
        fill(pose, l, top + rowHeight(entry) - 2, l + w, top + rowHeight(entry) - 1, 0xFFAAAAAA);
        text(pose, entry.crafting ? craftingTitle(number) : number + ". 处理样板 / " + entry.node.selected.machine(),
                l, top + 4, w - (entry.crafting ? 54 : 22), 0x555555);
        if (entry.problem != null) { text(pose, entry.problem, l, top + 18, w, 0xAA3333); return; }
        if (!entry.crafting) {
            CompactUi.button(pose, l + w - 18, top + 1, 18, 16, CompactUi.hit(mx, my, l + w - 18, top + 1, 18, 16), true);
            drawCenteredString(pose, font, "#", l + w - 9, top + 5, 0xFFFFFF);
            if (my >= LIST_TOP && my < listBottom() && CompactUi.hit(mx, my, l + w - 18, top + 1, 18, 16)) tooltip = List.of(Component.literal("输入数量"));
            var inputs = entry.inputs(); int cell = inputWidth() / columns();
            for (int i = 0; i < inputs.size(); i++) input(pose, inputs.get(i), l + i % columns() * cell, top + 21 + i / columns() * INPUT_ROW, cell, mx, my);
        } else {
            int toggleX = substitutionX(number);
            renderSubstitution(pose, substitutionButton(entry, false, toggleX, top + 1), mx, my, partial);
            renderSubstitution(pose, substitutionButton(entry, true, toggleX + 18, top + 1), mx, my, partial);
            for (int i = 0; i < 9; i++) {
                int x = l + i % 3 * 18, y = top + 20 + i / 3 * 18; CompactUi.slot(pose, x, y);
                if (!entry.node.selected.inputs().get(i).isEmpty()) ingredient(pose, entry.node.selectedInput(i), x + 1, y + 1, mx, my);
            }
        }
        int x = entry.crafting ? l + 76 : l + inputWidth() + 18, y = top + 25;
        int outputWidth = left() + span() - x - 10;
        text(pose, "→", x - 18, y + 4, 16, 0x555555);
        ingredient(pose, entry.output(), x, y, mx, my);
        CompactUi.nameAndQuantity(pose, font, entry.output().name(), "×" + entry.output().quantity(),
                x + 20, y + 4, outputWidth - 20, 0x303030, 0x555555);
        if (my >= LIST_TOP && my < listBottom() && CompactUi.hit(mx, my, x, y, outputWidth, 20))
            tooltip = entry.output().tooltip(TreeJeiPlugin.runtime.getIngredientManager());
    }
    @Override public void render(PoseStack pose, int mouseX, int mouseY, float partial) {
        renderBackground(pose); tooltip = List.of(); int l = left(), w = span();
        CompactUi.panel(pose, l, 8, w, height - 16);
        ingredient(pose, target(), l + 10, 18, -1, -1);
        text(pose, target().name(), l + 32, 22, w - 65, 0x303030);
        var window = minecraft.getWindow(); double scale = window.getGuiScale();
        RenderSystem.enableScissor((int)((l + 6) * scale), (int)((height - listBottom()) * scale),
                (int)((w - 12) * scale), (int)(Math.max(1, listBottom() - LIST_TOP) * scale));
        int top = LIST_TOP - scroll, number = 1;
        for (var entry : preview.entries()) {
            int h = rowHeight(entry); if (top + h > LIST_TOP && top < listBottom()) row(pose, entry, number, top, mouseX, mouseY, partial);
            top += h; number++;
        }
        RenderSystem.disableScissor();
        if (maxScroll() > 0) {
            int track = listBottom() - LIST_TOP, thumb = Math.max(8, track * track / (track + maxScroll()));
            int y = LIST_TOP + (track - thumb) * scroll / maxScroll(); fill(pose, l + w - 6, y, l + w - 4, y + thumb, 0xFF555555);
        }
        if (!preview.issues().isEmpty()) {
            var issue = currentIssue().orElseThrow(); int y = height - 68;
            ingredient(pose, issue.node().target, l + 10, y, -1, -1);
            text(pose, (problemIndex + 1) + "/" + preview.issues().size() + " " + issue.message(), l + 32, y, w - 42, 0xAA3333);
            text(pose, issue.path(), l + 32, y + 13, w - 42, 0x555555);
            if (CompactUi.hit(mouseX, mouseY, l + 6, y - 2, w - 12, 38))
                tooltip = List.of(Component.literal(issue.message()), Component.literal(issue.path()));
        } else if (!error.isEmpty()) text(pose, error, l + 10, height - 61, w - 20, 0xAA3333);
        super.render(pose, mouseX, mouseY, partial);
        if (!tooltip.isEmpty()) renderComponentTooltip(pose, tooltip, mouseX, mouseY);
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() {
        if (minecraft.player != null && minecraft.player.containerMenu == preview.menu())
            RecipeInteractions.restoreJei(jeiContext);
        else minecraft.setScreen(null);
    }

    private static final class AmountScreen extends Screen {
        private final PatternPreviewScreen parent;
        private final PatternEncoder.Entry entry;
        private final List<Integer> inputs = new ArrayList<>();
        private final Map<Integer, String> values = new HashMap<>();
        private final Map<Integer, EditBox> boxes = new HashMap<>();
        private int page;
        private String error = "";
        AmountScreen(PatternPreviewScreen parent, PatternEncoder.Entry entry) {
            super(Component.literal("处理样板输入数量")); this.parent = parent; this.entry = entry;
            for (int i = 0; i < entry.node.selected.inputs().size(); i++) if (!entry.node.selected.inputs().get(i).isEmpty()) {
                inputs.add(i); values.put(i, Long.toString(entry.amounts.getOrDefault(i, entry.node.selectedInput(i).amount())));
            }
        }
        private String inputName(int slot) {
            return font.plainSubstrByWidth(entry.node.selectedInput(slot).name(), Math.max(0, Math.min(215, width - 102)));
        }
        private int pageSize() { return Math.max(1, (height - 115) / 26); }
        private void capture() { boxes.forEach((index, box) -> values.put(index, box.getValue())); }
        @Override protected void init() {
            clearWidgets(); boxes.clear(); int left = Math.max(8, (width - 314) / 2);
            for (int row = 0; row < pageSize(); row++) {
                int index = page * pageSize() + row; if (index >= inputs.size()) break;
                int input = inputs.get(index);
                int quantityX = left + font.width(inputName(input)) + 4;
                var box = new EditBox(font, quantityX, 47 + row * 26, 82, 20, Component.literal("输入数量"));
                box.setMaxLength(10); box.setFilter(s -> s.matches("[0-9]*")); box.setValue(values.get(input));
                boxes.put(input, addRenderableWidget(box));
            }
            addRenderableWidget(new Button(left, height - 37, 75, 20, Component.literal("恢复原量"), b -> {
                for (int input : inputs) values.put(input, Long.toString(entry.node.selectedInput(input).amount())); init();
            }));
            addRenderableWidget(new Button(left + 82, height - 37, 90, 20, Component.literal("保存数量"), b -> save()));
        }
        @Override public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
            if (mouseY < 44 || mouseY >= height - 65) return false;
            int pages = Math.max(1, (inputs.size() + pageSize() - 1) / pageSize());
            int next = Math.max(0, Math.min(pages - 1, page + (delta > 0 ? -1 : 1)));
            if (next != page) { capture(); page = next; init(); }
            return true;
        }
        private void save() {
            capture(); var changed = new HashMap<Integer, Long>();
            for (int i : inputs) {
                String value = values.get(i);
                if (value.isEmpty() || Long.parseLong(value) < 1 || Long.parseLong(value) > Integer.MAX_VALUE) {
                    error = "数量必须在 1 到 2147483647 之间"; return;
                }
                long amount = Long.parseLong(value);
                if (amount != entry.node.selectedInput(i).amount()) changed.put(i, amount);
            }
            entry.amounts.clear(); entry.amounts.putAll(changed);
            for (int i : inputs) parent.preview.preferences().put(entry.amountKey(i), changed.containsKey(i) ? Long.toString(changed.get(i)) : "");
            onClose();
        }
        @Override public void render(PoseStack pose, int mouseX, int mouseY, float partial) {
            renderBackground(pose); int left = Math.max(8, (width - 314) / 2);
            CompactUi.panel(pose, left - 5, 6, Math.min(314, width - 6), height - 12);
            font.draw(pose, title, (width - font.width(title)) / 2f, 12, 0x404040);
            font.draw(pose, font.plainSubstrByWidth("输出固定：" + entry.output().name() + " ×" + entry.output().quantity(), width - 16), left, 29, 0x404040);
            for (int row = 0; row < pageSize(); row++) {
                int index = page * pageSize() + row; if (index >= inputs.size()) break;
                font.draw(pose, inputName(inputs.get(index)), left, 53 + row * 26, 0x404040);
            }

            font.draw(pose, error, left, height - 55, 0xAA3333); super.render(pose, mouseX, mouseY, partial);
        }
        @Override public void tick() { if (minecraft.player == null || minecraft.player.containerMenu != parent.preview.menu()) minecraft.setScreen(null); }
        @Override public boolean isPauseScreen() { return false; }
        @Override public void onClose() { parent.rebuild(false); minecraft.setScreen(parent); }
    }
}

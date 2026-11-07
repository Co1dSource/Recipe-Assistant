package com.cti.ctirecipeassistant.integration.ae2;

import appeng.api.stacks.GenericStack;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.common.Internal;
import mezz.jei.gui.elements.GuiIconButtonSmall;
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
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Displays the exact input selection used by PatternEncoder; no recipe text inference. */
public final class PatternPreviewScreen extends Screen implements PatternConfirmation {
    private final ClientRecipeOperation<?> operation;
    private final RecipeTreeScreen tree;
    private final RecipeInteractions.JeiContext jeiContext;
    private final List<TreeIngredient> targets;
    private int targetIndex, problemIndex, depth, maximum, scroll;
    private PatternEncoder.Preview preview;
    private EditBox depthBox;
    private GuiIconButtonSmall decreaseDepth, increaseDepth;
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
        depth = tree.encodingDepth(); rebuild(false);
    }
    public Object sourceRecipe() { return operation.recipe(); }
    public RecipeInteractions.JeiContext jeiContext() { return jeiContext; }
    public Screen terminal() { return operation.parentScreen(); }
    public TreeIngredient target() { return tree.finalRoot().target; }
    public boolean matches(ClientRecipeOperation<?> selected) {
        return selected.parentScreen() == terminal() && tree.finalRoot().selected != null
                && selected.candidate().id().equals(tree.finalRoot().selected.id()) && selected.target().key().equals(target().key());
    }
    public void startFromJei() {
        rebuild(false); error = Optional.ofNullable(PatternEncoder.start(preview)).orElse("");
        if (!error.isEmpty()) net.minecraft.client.Minecraft.getInstance().setScreen(this);
    }
    private void rebuild(boolean reset) {
        maximum = PatternPlanner.maximumDepth(tree.finalRoot());
        preview = PatternEncoder.prepare(operation, tree.finalRoot(), tree.index(), tree.preferences(), Math.min(depth, maximum));
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
    private int left() { return (width - span()) / 2; }
    private int span() { return Math.min(480, width - 16); }
    private int listBottom() { return height - (preview.issues().isEmpty() && error.isEmpty() ? 18 : 62); }
    private int inputWidth() { return Math.max(72, span() - 144); }
    private int columns() { return Math.max(1, inputWidth() / 110); }
    private int rowHeight(PatternEncoder.Entry entry) {
        if (entry.problem != null) return 42;
        return entry.crafting ? 80 : 26 + Math.max(1, (entry.inputs().size() + columns() - 1) / columns()) * 26;
    }
    private int maxScroll() { return Math.max(0, preview.entries().stream().mapToInt(this::rowHeight).sum() - Math.max(1, listBottom() - 76)); }
    @Override protected void init() {
        clearWidgets(); int l = left();
        if (targets.size() > 1) addRenderableWidget(new Button(l + span() - 28, 18, 18, 18, Component.literal(">"), b -> {
            targetIndex = (targetIndex + 1) % targets.size(); tree.retarget(targets.get(targetIndex), operation.candidate());
            depth = tree.encodingDepth(); rebuild(true); init();
        }));
        depthBox = new EditBox(font, l + 51, 45, 66, 18, Component.literal("编码深度"));
        depthBox.setMaxLength(10);
        depthBox.setFilter(value -> value.matches("[0-9]*") && (value.isEmpty() || Long.parseLong(value) <= Integer.MAX_VALUE));
        depthBox.setValue(Integer.toString(depth));
        var textures = Internal.getTextures();
        decreaseDepth = addRenderableWidget(new GuiIconButtonSmall(l + 34, 47, 13, 13, minusIcon(), b -> adjustDepth(-1), textures));
        increaseDepth = addRenderableWidget(new GuiIconButtonSmall(l + 121, 47, 13, 13, textures.getRecipeTransfer(), b -> adjustDepth(1), textures));
        decreaseDepth.active = depth > 0; increaseDepth.active = depth < Integer.MAX_VALUE;
        depthBox.setResponder(value -> {
            depth = value.isEmpty() ? 0 : Integer.parseInt(value); tree.encodingDepth(depth); rebuild(true);
            decreaseDepth.active = depth > 0; increaseDepth.active = depth < Integer.MAX_VALUE;
        });
        addRenderableWidget(depthBox);
    }
    private void adjustDepth(int delta) {
        depthBox.setValue(Long.toString(Math.max(0L, Math.min(Integer.MAX_VALUE, (long)depth + delta))));
    }
    private static IDrawable minusIcon() {
        return new IDrawable() {
            public int getWidth() { return 7; }
            public int getHeight() { return 7; }
            public void draw(PoseStack pose, int x, int y) {
                fill(pose, x + 2, y + 4, x + 7, y + 5, 0xFF383838);
                fill(pose, x + 1, y + 3, x + 6, y + 4, 0xFFFFFFFF);
            }
        };
    }
    @Override public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (CompactUi.hit(mouseX, mouseY, left() + 6, 76, span() - 12, Math.max(1, listBottom() - 76))) {
            scroll = Math.max(0, Math.min(maxScroll(), scroll + (delta > 0 ? -26 : 26))); return true;
        }
        if (!preview.issues().isEmpty() && CompactUi.hit(mouseX, mouseY, left() + 6, height - 57, span() - 12, 44)) {
            problemIndex = Math.max(0, Math.min(preview.issues().size() - 1, problemIndex + (delta > 0 ? -1 : 1))); return true;
        }
        return false;
    }
    @Override public boolean mouseClicked(double x, double y, int button) {
        if (button == 0 && y >= 76 && y < listBottom()) {
            int top = 76 - scroll;
            for (var entry : preview.entries()) {
                if (!entry.crafting && entry.problem == null && CompactUi.hit(x, y, left() + span() - 28, top + 1, 18, 16)) {
                    minecraft.setScreen(new AmountScreen(this, entry)); return true;
                }
                top += rowHeight(entry);
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
        if (my >= 76 && my < listBottom() && CompactUi.hit(mx, my, x, y, 16, 16))
            tooltip = ingredient.tooltip(TreeJeiPlugin.runtime.getIngredientManager());
    }
    private void input(PoseStack pose, GenericStack input, int x, int y, int w, int mx, int my) {
        itemRenderer.renderAndDecorateItem(input.what().wrapForDisplayOrFilter(), x, y);
        text(pose, input.what().getDisplayName().getString(), x + 20, y, w - 22, 0x303030);
        String quantity = input.what().formatAmount(input.amount(), AmountFormat.FULL);
        text(pose, "×" + quantity, x + 20, y + 11, w - 22, 0x555555);
        if (my >= 76 && my < listBottom() && CompactUi.hit(mx, my, x, y, w, 24))
            tooltip = List.of(input.what().getDisplayName(), Component.literal("×" + quantity));
    }
    private void row(PoseStack pose, PatternEncoder.Entry entry, int number, int top, int mx, int my) {
        int l = left() + 10, w = span() - 20;
        fill(pose, l, top + rowHeight(entry) - 2, l + w, top + rowHeight(entry) - 1, 0xFFAAAAAA);
        text(pose, number + ". " + (entry.crafting ? "合成样板" : "处理样板 / " + entry.node.selected.machine()), l, top + 2, w - 22, 0x555555);
        if (entry.problem != null) { text(pose, entry.problem, l, top + 18, w, 0xAA3333); return; }
        if (!entry.crafting) {
            CompactUi.button(pose, l + w - 18, top + 1, 18, 16, CompactUi.hit(mx, my, l + w - 18, top + 1, 18, 16), true);
            drawCenteredString(pose, font, "#", l + w - 9, top + 5, 0xFFFFFF);
            if (my >= 76 && my < listBottom() && CompactUi.hit(mx, my, l + w - 18, top + 1, 18, 16)) tooltip = List.of(Component.literal("输入数量"));
            var inputs = entry.inputs(); int cell = inputWidth() / columns();
            for (int i = 0; i < inputs.size(); i++) input(pose, inputs.get(i), l + i % columns() * cell, top + 21 + i / columns() * 26, cell, mx, my);
        } else {
            for (int i = 0; i < 9; i++) {
                int x = l + i % 3 * 18, y = top + 20 + i / 3 * 18; CompactUi.slot(pose, x, y);
                if (!entry.node.selected.inputs().get(i).isEmpty()) ingredient(pose, entry.node.selectedInput(i), x + 1, y + 1, mx, my);
            }
            String flags = (preview.menu().isSubstitute() ? "物品替换" : "") + (preview.menu().isSubstituteFluids() ? " 流体替换" : "");
            text(pose, flags, l + 60, top + 23, Math.max(1, inputWidth() - 65), 0x555555);
            if (!flags.isBlank() && CompactUi.hit(mx, my, l + 60, top + 20, Math.max(1, inputWidth() - 65), 35))
                tooltip = List.of(Component.literal(flags.trim()), Component.literal("替代材料由 AE2 终端规则确定"));
        }
        int x = left() + span() - 114, y = top + 25;
        text(pose, "→", x - 18, y + 4, 16, 0x555555);
        ingredient(pose, entry.output(), x, y, mx, my);
        text(pose, entry.output().name(), x + 20, y, 82, 0x303030);
        text(pose, "×" + entry.output().quantity(), x + 20, y + 12, 82, 0x555555);
        if (my >= 76 && my < listBottom() && CompactUi.hit(mx, my, x, y, 102, 26))
            tooltip = entry.output().tooltip(TreeJeiPlugin.runtime.getIngredientManager());
    }
    @Override public void render(PoseStack pose, int mouseX, int mouseY, float partial) {
        renderBackground(pose); tooltip = List.of(); int l = left(), w = span();
        CompactUi.panel(pose, l, 8, w, height - 16);
        ingredient(pose, target(), l + 10, 18, -1, -1);
        text(pose, target().name(), l + 32, 22, w - 65, 0x303030);
        text(pose, "深度", l + 10, 50, 24, 0x404040);
        text(pose, "有效 " + Math.min(depth, maximum) + "   样板 " + preview.entries().size(), l + 146, 50, w - 156, 0x555555);
        var window = minecraft.getWindow(); double scale = window.getGuiScale();
        RenderSystem.enableScissor((int)((l + 6) * scale), (int)((height - listBottom()) * scale),
                (int)((w - 12) * scale), (int)(Math.max(1, listBottom() - 76) * scale));
        int top = 76 - scroll, number = 1;
        for (var entry : preview.entries()) {
            int h = rowHeight(entry); if (top + h > 76 && top < listBottom()) row(pose, entry, number, top, mouseX, mouseY);
            top += h; number++;
        }
        RenderSystem.disableScissor();
        if (maxScroll() > 0) {
            int track = listBottom() - 76, thumb = Math.max(8, track * track / (track + maxScroll()));
            int y = 76 + (track - thumb) * scroll / maxScroll(); fill(pose, l + w - 6, y, l + w - 4, y + thumb, 0xFF555555);
        }
        if (!preview.issues().isEmpty()) {
            var issue = currentIssue().orElseThrow(); int y = height - 55;
            ingredient(pose, issue.node().target, l + 10, y, -1, -1);
            text(pose, (problemIndex + 1) + "/" + preview.issues().size() + " " + issue.message(), l + 32, y, w - 42, 0xAA3333);
            text(pose, issue.path(), l + 32, y + 13, w - 42, 0x555555);
            if (CompactUi.hit(mouseX, mouseY, l + 6, y - 2, w - 12, 38))
                tooltip = List.of(Component.literal(issue.message()), Component.literal(issue.path()));
        } else if (!error.isEmpty()) text(pose, error, l + 10, height - 45, w - 20, 0xAA3333);
        super.render(pose, mouseX, mouseY, partial);
        if (!tooltip.isEmpty()) renderComponentTooltip(pose, tooltip, mouseX, mouseY);
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { RecipeInteractions.arm(this); }

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
        private int pageSize() { return Math.max(1, (height - 115) / 26); }
        private void capture() { boxes.forEach((index, box) -> values.put(index, box.getValue())); }
        @Override protected void init() {
            clearWidgets(); boxes.clear(); int left = Math.max(8, (width - 400) / 2);
            for (int row = 0; row < pageSize(); row++) {
                int index = page * pageSize() + row; if (index >= inputs.size()) break;
                int input = inputs.get(index);
                var box = new EditBox(font, left + Math.min(260, width - 150), 47 + row * 26, 110, 20, Component.literal("输入数量"));
                box.setMaxLength(10); box.setFilter(s -> s.matches("[0-9]*")); box.setValue(values.get(input));
                boxes.put(input, addRenderableWidget(box));
            }
            addRenderableWidget(new Button(left, height - 37, 75, 20, Component.literal("恢复原量"), b -> {
                for (int input : inputs) values.put(input, Long.toString(entry.node.selectedInput(input).amount())); init();
            }));
            addRenderableWidget(new Button(left + Math.min(280, width - 120), height - 37, 90, 20, Component.literal("保存数量"), b -> save()));
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
            renderBackground(pose); int left = Math.max(8, (width - 400) / 2);
            CompactUi.panel(pose, left - 5, 6, Math.min(400, width - 6), height - 12);
            font.draw(pose, title, (width - font.width(title)) / 2f, 12, 0x404040);
            font.draw(pose, font.plainSubstrByWidth("输出固定：" + entry.output().name() + " × " + entry.output().quantity(), width - 16), left, 29, 0x404040);
            for (int row = 0; row < pageSize(); row++) {
                int index = page * pageSize() + row; if (index >= inputs.size()) break;
                font.draw(pose, font.plainSubstrByWidth(entry.node.selectedInput(inputs.get(index)).name(), Math.max(30, Math.min(250, width - 160))), left, 53 + row * 26, 0x404040);
            }

            font.draw(pose, error, left, height - 55, 0xAA3333); super.render(pose, mouseX, mouseY, partial);
        }
        @Override public void tick() { if (minecraft.player == null || minecraft.player.containerMenu != parent.preview.menu()) minecraft.setScreen(null); }
        @Override public boolean isPauseScreen() { return false; }
        @Override public void onClose() { parent.rebuild(false); minecraft.setScreen(parent); }
    }
}

package com.cti.ctirecipeassistant.client.gui;

import com.cti.ctirecipeassistant.recipe.*;
import com.cti.ctirecipeassistant.client.jei.RecipeInteractions;
import mezz.jei.gui.recipes.RecipesGui;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.EditBox;
import net.minecraftforge.fluids.FluidStack;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import java.util.*;

public final class RecipeTreeScreen extends Screen {
    private final IJeiRuntime runtime;
    private final JeiRecipeIndex index;
    private final RecipePreferences preferences;
    private RecipeNode root, targetRoot;
    private Screen returnTo, jeiParent;
    private RecipeInteractions.JeiContext jeiContext;
    private static final int TOOLBAR = 24, MATERIAL_ROW = 17;
    private boolean positioned;
    private static final int ROW = 20;
    private static final int RECIPE_X = 80, VARIANT_X = 112, REMOVE_X = 145, ACTION_Y = 27;
    private long quantity, calculatedQuantity, calculatedRevision = -1;
    private int materialScroll;
    private MaterialRequirements requirements;
    private record SummaryRow(TreeIngredient ingredient, boolean catalyst) {}
    private record SummaryCell(SummaryRow row, int x, int width) {}
    private List<List<SummaryCell>> materialLines = List.of();
    private EditBox panelInput;
    private TreeLayout layout;
    private double panX = 32, panY, zoom = 1;
    private record View(RecipeNode root, RecipeNode target, double x, double y, double zoom, long quantity) {}
    private final Deque<View> roots = new ArrayDeque<>();
    private final Deque<Panel> panels = new ArrayDeque<>();
    private boolean dragging;
    private List<Component> tooltip = List.of();
    private record Row(String label, List<TreeIngredient> ingredients, int slot, String role) {}
    private enum PanelMode { DETAILS, VARIANTS, OUTPUTS, AMOUNT }
    private static final class Panel {
        final RecipeNode node;
        final PanelMode mode;
        final List<Row> rows;
        int scroll;
        Panel(RecipeNode node, PanelMode mode, List<Row> rows) {
            this.node = node; this.mode = mode; this.rows = rows;
        }
        boolean editing() { return mode == PanelMode.AMOUNT; }
    }
    public RecipeTreeScreen(TreeIngredient target, IJeiRuntime runtime) {
        this(target, runtime, null);
    }
    public RecipeTreeScreen(TreeIngredient target, IJeiRuntime runtime, Screen returnTo) {
        this(target, runtime, returnTo, false);
    }
    public RecipeTreeScreen(TreeIngredient target, IJeiRuntime runtime, Screen returnTo, boolean craftingOnly) {
        super(Component.literal("配方树"));
        this.runtime = runtime;
        returnTo(returnTo);
        index = new JeiRecipeIndex(runtime, craftingOnly);
        preferences = new RecipePreferences();
        root = targetRoot = new RecipeNode(target, null, index, preferences);
        root.load(); root.expanded = true;
    }
    public RecipeNode finalRoot() { return targetRoot; }
    public JeiRecipeIndex index() { return index; }
    public RecipePreferences preferences() { return preferences; }
    public long targetQuantity() { return quantity > 0 ? quantity : MaterialRequirements.rootOutput(targetRoot); }
    public void targetQuantity(long quantity) { this.quantity = quantity; }
    public MaterialRequirements requirements() {
        long target = targetQuantity(), revision = RecipePreferences.revision();
        if (requirements == null || calculatedQuantity != target || calculatedRevision != revision) {
            requirements = MaterialRequirements.calculate(targetRoot, target);
            calculatedQuantity = target; calculatedRevision = revision;
            var rows = new ArrayList<SummaryRow>();
            requirements.materials().stream().filter(i -> !(i.typed().getIngredient() instanceof FluidStack))
                    .forEach(i -> rows.add(new SummaryRow(i, false)));
            requirements.materials().stream().filter(i -> i.typed().getIngredient() instanceof FluidStack)
                    .forEach(i -> rows.add(new SummaryRow(i, false)));
            requirements.catalysts().forEach(i -> rows.add(new SummaryRow(i, true)));
            if (font != null) arrangeMaterials(rows);
        }
        return requirements;
    }
    private void arrangeMaterials(List<SummaryRow> rows) {
        var lines = new ArrayList<List<SummaryCell>>();
        var line = new ArrayList<SummaryCell>();
        int x = 6, end = width - 6;
        for (var row : rows) {
            var item = row.ingredient();
            int cellWidth = Math.min(Math.max(1, end - 6),
                    24 + font.width(item.name()) + font.width("×" + item.quantity()));
            if (!line.isEmpty() && x + cellWidth > end) {
                lines.add(line); line = new ArrayList<>(); x = 6;
            }
            line.add(new SummaryCell(row, x, cellWidth));
            x += cellWidth + 8;
        }
        if (!line.isEmpty()) lines.add(line);
        materialLines = List.copyOf(lines);
        materialScroll = Math.min(materialScroll, Math.max(0, materialLines.size() - summaryRows()));
    }
    public void returnTo(Screen screen) {
        returnTo = screen;
        jeiContext = screen instanceof RecipesGui gui ? RecipeInteractions.captureJei(gui) : null;
        jeiParent = jeiContext == null ? null : jeiContext.parent();
    }
    public void refresh() {
        while (!attached(root)) root = root.parent;
        while (!panels.isEmpty() && !attached(panels.peek().node)) back();
        requirements = null;
        requirements();
        layout = new TreeLayout(root);
    }
    private boolean attached(RecipeNode node) {
        for (var child = node; child.parent != null; child = child.parent)
            if (!child.parent.children.contains(child)) return false;
        return true;
    }
    public void focus(RecipeNode node) {
        if (node != root) { roots.push(new View(root, targetRoot, panX, panY, zoom, quantity)); root = node; }
        node.expanded = true; center();
    }
    public void retarget(TreeIngredient target, RecipeCandidate recipe) {
        roots.push(new View(root, targetRoot, panX, panY, zoom, quantity));
        root = targetRoot = new RecipeNode(target, null, index, preferences);
        root.load();
        if (!root.productionCut()) root.useRecipe(recipe);
        root.expanded = true; quantity = 0; center();
    }
    @Override protected void init() {
        if (!positioned) { center(); positioned = true; } else refresh();
        if (!panels.isEmpty() && panels.peek().editing()) back();
    }
    @Override public void tick() { if (panelInput != null) panelInput.tick(); }
    private void center() {
        refresh();
        panX = 32;
        panY = (TOOLBAR + summaryTop()) / 2.0 - layout.byNode.get(root).y() * zoom;
    }
    private void open(RecipeNode node) {
        if (node.children.isEmpty()) return;
        node.expanded = !node.expanded;
        layout = new TreeLayout(root);
    }
    private boolean hasProductionChoice(RecipeNode node) {
        return node.depth < 64 && (!node.loaded || node.selected != null || !node.candidates.isEmpty());
    }
    private void recipes(RecipeNode node) {
        if (hasProductionChoice(node))
            RecipeInteractions.choose(node, index, this, this::refresh);
    }
    private boolean canRemoveProduction(RecipeNode node) {
        return node.selected != null && (node == targetRoot || !node.children.isEmpty() || node.cycle);
    }
    private void variants(RecipeNode parent, int slot, List<TreeIngredient> ingredients) {
        var choices = ingredients.stream().map(i -> new Row("", List.of(i), slot, "input")).toList();
        panels.push(new Panel(parent, PanelMode.VARIANTS, choices));
    }
    private void details(RecipeNode node) {
        if (node.selected == null) return;
        List<Row> rows = new ArrayList<>();
        var recipe = node.selected;
        addRows(rows, recipe.inputs(), "输入", "input");
        addRows(rows, recipe.outputs(), "产物", "output");
        addRows(rows, recipe.catalysts(), "材料", "catalyst");
        panels.push(new Panel(node, PanelMode.DETAILS, rows));
    }
    private void addRows(List<Row> rows, List<List<TreeIngredient>> slots, String label, String role) {
        for (int i = 0; i < slots.size(); i++) if (!slots.get(i).isEmpty())
            rows.add(new Row(label + " " + (i + 1), slots.get(i), i, role));
    }
    private void back() {
        if (!panels.isEmpty()) {
            if (panels.pop().editing()) panelInput = null;
        }
        else if (!roots.isEmpty()) {
            var view = roots.pop();
            if (targetRoot != view.target) quantity = view.quantity;
            root = view.root; targetRoot = view.target;
            panX = view.x; panY = view.y; zoom = view.zoom; refresh();
        } else onClose();
    }
    @Override public void render(PoseStack pose, int mouseX, int mouseY, float partialTick) {
        tooltip = List.of();
        fill(pose, 0, 0, width, height, 0xED202020);
        var window = minecraft.getWindow();
        double guiScale = window.getGuiScale();
        requirements();
        int summaryTop = summaryTop();
        RenderSystem.enableScissor(0, (int)((height - summaryTop) * guiScale), window.getWidth(),
                Math.max(0, (int)((summaryTop - TOOLBAR) * guiScale)));
        pose.pushPose();
        pose.translate(panX, panY, 0);
        pose.scale((float)zoom, (float)zoom, 1);
        double tx = (mouseX - panX) / zoom, ty = (mouseY - panY) / zoom;
        for (var view : layout.views) {
            if (!view.node().expanded) continue;
            for (var child : view.node().children) {
                var end = layout.byNode.get(child);
                int x1 = view.x() + TreeLayout.WIDTH, y1 = view.y() + TreeLayout.HEIGHT / 2;
                int x2 = end.x(), y2 = end.y() + TreeLayout.HEIGHT / 2, mid = (x1 + x2) / 2;
                fill(pose, x1, y1, mid + 1, y1 + 1, 0xFF66717C);
                fill(pose, mid, Math.min(y1, y2), mid + 1, Math.max(y1, y2) + 1, 0xFF66717C);
                fill(pose, mid, y2, x2, y2 + 1, 0xFF66717C);
            }
        }
        for (var view : layout.views) {
            if (view.x() * zoom + panX > width || (view.x() + TreeLayout.WIDTH) * zoom + panX < 0
                    || view.y() * zoom + panY > height || (view.y() + TreeLayout.HEIGHT) * zoom + panY < 0) continue;
            RecipeNode node = view.node();
            int x = view.x(), y = view.y();
            boolean hover = inside(tx, ty, x, y, TreeLayout.WIDTH, TreeLayout.HEIGHT);
            CompactUi.panel(pose, x, y, TreeLayout.WIDTH, TreeLayout.HEIGHT);
            CompactUi.slot(pose, x + 5, y + 5);
            icon(pose, node.target, x + 6, y + 6);
            String amount = node.parent != null ? node.requiredAmount() + node.target.unit()
                    : node.selected == null ? node.target.quantity() : node.selected.output(node.target.key()).quantity();
            CompactUi.nameAndQuantity(pose, font, node.target.name(), "×" + amount,
                    x + 28, y + 10, 97, 0x303030, 0x555555);
            if (node != targetRoot)
                text(pose, node.status(), x + 6, y + 30, RECIPE_X - 9, node.cycle ? 0x995522 : 0x555555);
            else if (!node.hasConfiguredProduction())
                text(pose, "未配置", x + 6, y + 30, RECIPE_X - 9, 0x555555);
            boolean recipeButton = hasProductionChoice(node);
            boolean variantButton = node.inputVariants().size() > 1;
            boolean onRecipe = recipeButton && inside(tx, ty, x + RECIPE_X, y + ACTION_Y, 29, 15);
            boolean onVariant = variantButton && inside(tx, ty, x + VARIANT_X, y + ACTION_Y, 29, 15);
            if (recipeButton) nodeButton(pose, "配方", x + RECIPE_X, y + ACTION_Y, onRecipe);
            if (variantButton) nodeButton(pose, "变体", x + VARIANT_X, y + ACTION_Y, onVariant);
            boolean removable = canRemoveProduction(node);
            boolean onRemove = removable && inside(tx, ty, x + REMOVE_X, y + ACTION_Y, 14, 15);
            if (removable) {
                CompactUi.button(pose, x + REMOVE_X, y + ACTION_Y, 14, 15, onRemove, true);
                font.drawShadow(pose, "×", x + REMOVE_X + (14 - font.width("×")) / 2f, y + ACTION_Y + 3, 0xFFFFFF);
            }
            boolean onFold = !node.children.isEmpty() && inside(tx, ty, x + 145, y + 5, 14, 13);
            if (!node.children.isEmpty()) {
                CompactUi.button(pose, x + 145, y + 5, 14, 13, onFold, true);
                String fold = node.expanded ? "−" : "+";
                font.drawShadow(pose, fold, x + 152 - font.width(fold) / 2f, y + 7, 0xFFFFFF);
            }
            if (hover && panels.isEmpty() && mouseY >= TOOLBAR && mouseY < summaryTop) {
                if (onFold) tooltip = List.of(Component.literal(node.expanded ? "折叠" : "展开"));
                else if (onRemove) tooltip = List.of(Component.literal("删除此配方"));
                else if (onRecipe) tooltip = List.of(Component.literal("在 JEI 选择生产配方"));
                else if (onVariant) tooltip = List.of(Component.literal("替换输入物品"));
                else tooltip = node.target.tooltip(runtime.getIngredientManager());
            }
        }
        pose.popPose();
        RenderSystem.disableScissor();
        // JEI item rendering writes depth above flat GUI geometry. Start a fresh
        // depth layer so tree icons cannot punch through the toolbar or modal.
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        CompactUi.panel(pose, 0, 0, width, TOOLBAR);
        text(pose, targetRoot.target.name(), 8, 8, width - 16, 0x303030);
        if (summaryTop < height) renderSummary(pose, mouseX, mouseY);
        super.render(pose, mouseX, mouseY, partialTick);
        if (!panels.isEmpty()) {
            RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
            renderPanel(pose, mouseX, mouseY, partialTick);
        }
        if (!tooltip.isEmpty()) renderComponentTooltip(pose, tooltip, mouseX, mouseY);
    }

    private void nodeButton(PoseStack pose, String label, int x, int y, boolean hover) {
        CompactUi.button(pose, x, y, 29, 15, hover, true);
        font.drawShadow(pose, label, x + (29 - font.width(label)) / 2f, y + 3, 0xFFFFFF);
    }

    private int summaryRows() { return Math.max(1, Math.min(materialLines.size(), height >= 160 ? 2 : 1)); }
    private int summaryTop() {
        if (targetRoot.selected == null) return height;
        return height - 16 - summaryRows() * MATERIAL_ROW;
    }
    private void renderSummary(PoseStack pose, int mx, int my) {
        int top = summaryTop();
        CompactUi.panel(pose, 0, top, width, height - top);
        String heading = "材料需求";
        font.draw(pose, heading, 6, top + 5, 0x303030);
        if (requirements.problem() != null) text(pose, requirements.problem(), 6, top + 19, width - 12, 0xA02020);
        if (!preferences.error.isEmpty()) text(pose, preferences.error, 6 + font.width(heading) + 6,
                top + 5, width - font.width(heading) - 18, 0xA02020);
        for (int i = 0; i < summaryRows() && materialScroll + i < materialLines.size(); i++) {
            int y = top + 14 + i * MATERIAL_ROW;
            for (var cell : materialLines.get(materialScroll + i)) {
                var row = cell.row();
                CompactUi.material(pose, font, row.ingredient(), null, cell.x(), y, cell.width(), false);
                if (panels.isEmpty() && inside(mx, my, cell.x(), y, cell.width(), MATERIAL_ROW)) {
                    tooltip = new ArrayList<>(row.ingredient().tooltip(runtime.getIngredientManager()));
                    if (row.catalyst()) tooltip.add(Component.literal("催化剂，不计入消耗"));
                }
            }
        }
    }

    private int panelWidth() { return Math.min(248, width - 20); }
    private int panelX() { return (width - panelWidth()) / 2; }
    private int panelRows() { return Math.max(1, Math.min(6, (height - 76) / ROW)); }
    private int panelHeight(Panel p) { return 32 + (p.editing() ? 2 : Math.min(panelRows(), p.rows.size())) * ROW; }
    private int panelY(Panel p) { return (height - panelHeight(p)) / 2; }
    public TreeIngredient ingredientAt(double x, double y) {
        if (!panels.isEmpty()) {
            var panel = panels.peek();
            if (!panel.editing()) for (int i = 0; i < panelRows() && panel.scroll + i < panel.rows.size(); i++)
                if (inside(x, y, panelX() + 8, panelY(panel) + 24 + i * ROW, 16, 16))
                    return panelIngredient(panel, panel.rows.get(panel.scroll + i));
            return null;
        }
        int top = summaryTop();
        if (y >= top) {
            for (int i = 0; i < summaryRows() && materialScroll + i < materialLines.size(); i++)
                for (var cell : materialLines.get(materialScroll + i))
                    if (inside(x, y, cell.x(), top + 14 + i * MATERIAL_ROW, 16, 16)) return cell.row().ingredient();
        } else if (y >= TOOLBAR) {
            double tx = (x - panX) / zoom, ty = (y - panY) / zoom;
            for (var view : layout.views)
                if (inside(tx, ty, view.x() + 6, view.y() + 6, 16, 16)) return view.node().target;
        }
        return null;
    }
    private TreeIngredient panelIngredient(Panel panel, Row row) {
        return panel.mode == PanelMode.DETAILS && row.role().equals("input")
                ? panel.node.selectedInput(row.slot()) : row.ingredients().get(0);
    }
    private void renderPanel(PoseStack pose, int mx, int my, float partialTick) {
        tooltip = List.of();
        Panel p = panels.peek();
        int x = panelX(), y = panelY(p), w = panelWidth();
        fill(pose, 0, 0, width, height, 0x70000000);
        CompactUi.panel(pose, x, y, w, panelHeight(p));
        String heading = switch (p.mode) {
            case DETAILS -> "输入与产物";
            case VARIANTS -> "选择物品";
            case OUTPUTS -> "选择目标产物";
            case AMOUNT -> "单次输入数量";
        };
        text(pose, heading, x + 8, y + 9, w - 40, 0x303030);
        if (p.editing()) {
            panelInput.render(pose, mx, my, partialTick);
            font.draw(pose, "保存", x + w - 34, y + 33, 0x303030);
            return;
        }
        for (int row = 0; row < panelRows() && p.scroll + row < p.rows.size(); row++) {
            int i = p.scroll + row, cx = x + 6, cy = y + 23 + row * ROW, cw = w - 12;
            boolean hover = inside(mx, my, cx, cy, cw, ROW);
            fill(pose, cx, cy, cx + cw, cy + ROW - 1, hover ? 0xFFAAAAAA : 0xFFB8B8B8);
            var entry = p.rows.get(i);
            var ingredient = panelIngredient(p, entry);
            icon(pose, ingredient, cx + 2, cy + 1);
            if (p.mode == PanelMode.VARIANTS) {
                text(pose, ingredient.name(), cx + 22, cy + 5, cw - 26, 0x303030);
            } else {
                String name = p.mode == PanelMode.DETAILS ? detailName(entry, ingredient) : ingredient.name();
                String count = p.mode == PanelMode.DETAILS && entry.role().equals("input")
                        ? inputQuantity(p.node, entry.slot(), ingredient) : "×" + ingredient.quantity();
                CompactUi.nameAndQuantity(pose, font, name, count, cx + 22, cy + 5, cw - 26,
                        0x303030, 0x555555);
            }
            if (hover) tooltip = ingredient.tooltip(runtime.getIngredientManager());
        }
    }
    private String detailName(Row row, TreeIngredient ingredient) {
        return row.label() + " " + ingredient.name()
                + (row.ingredients().size() > 1 ? "（" + row.ingredients().size() + "）" : "");
    }
    private String inputQuantity(RecipeNode node, int slot, TreeIngredient ingredient) {
        return "×" + node.inputAmount(slot) + ingredient.unit();
    }
    private void icon(PoseStack pose, TreeIngredient ingredient, int x, int y) {
        pose.pushPose(); pose.translate(x, y, 0); ingredient.render(pose, runtime.getIngredientManager()); pose.popPose();
    }
    private void text(PoseStack pose, String value, int x, int y, int maxWidth, int color) {
        font.draw(pose, font.plainSubstrByWidth(value, Math.max(1, maxWidth)), x, y, color);
    }
    private boolean inside(double mx, double my, int x, int y, int w, int h) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    @Override public boolean mouseClicked(double mx, double my, int button) {
        if (!panels.isEmpty()) { panelClick(mx, my, button); return true; }
        if (my < TOOLBAR) return super.mouseClicked(mx, my, button);
        setFocused(null);
        if (my >= summaryTop()) return true;
        double tx = (mx - panX) / zoom, ty = (my - panY) / zoom;
        for (var view : layout.views) if (inside(tx, ty, view.x(), view.y(), TreeLayout.WIDTH, TreeLayout.HEIGHT)) {
            if (button == 2) focus(view.node());
            else if (button == 1) details(view.node());
            else if (button == 0) {
                var node = view.node();
                if (canRemoveProduction(node)
                        && inside(tx, ty, view.x() + REMOVE_X, view.y() + ACTION_Y, 14, 15)) {
                    node.rememberCut();
                    refresh();
                } else if (hasProductionChoice(node) && inside(tx, ty, view.x() + RECIPE_X, view.y() + ACTION_Y, 29, 15))
                    recipes(node);
                else if (node.inputVariants().size() > 1 && inside(tx, ty, view.x() + VARIANT_X, view.y() + ACTION_Y, 29, 15))
                    variants(node.parent, node.inputSlot(), node.inputVariants());
                else open(node);
            }
            return true;
        }
        dragging = button == 0 || button == 2;
        return dragging;
    }

    private void panelClick(double mx, double my, int button) {
        if (button != 0) return;
        Panel p = panels.peek();
        int x = panelX(), w = panelWidth(), y = panelY(p);
        if (!inside(mx, my, x, y, w, panelHeight(p))) return;
        if (p.editing()) {
            if (inside(mx, my, x + w - 42, y + 26, 36, 20)) saveInput();
            else panelInput.mouseClicked(mx, my, button);
            return;
        }
        int row = (int) (my - y - 23) / ROW;
        int index = p.scroll + row;
        if (my < y + 23 || row >= panelRows() || index >= p.rows.size()) return;
        Row chosen = p.rows.get(index);
        if (p.mode == PanelMode.VARIANTS) {
            var source = p.node.children.stream().filter(child -> child.inputSlot() == chosen.slot()).findFirst().orElseThrow();
            targetRoot.chooseInputsAtDepth(source, chosen.ingredients().get(0));
            if (root == source && attached(p.node))
                root = p.node.children.stream().filter(child -> child.inputSlot() == chosen.slot()).findFirst().orElseThrow();
            panels.pop(); refresh();
        } else if (p.mode == PanelMode.OUTPUTS) {
            panels.clear(); retarget(chosen.ingredients().get(0), p.node.selected);
        } else if (chosen.role().equals("output")) {
            var outputs = chosen.ingredients().stream()
                    .map(i -> new Row("", List.of(i), chosen.slot(), "output")).toList();
            panels.push(new Panel(p.node, PanelMode.OUTPUTS, outputs));
        } else if (chosen.role().equals("input")) {
            var ingredient = p.node.selectedInput(chosen.slot());
            String count = inputQuantity(p.node, chosen.slot(), ingredient);
            int quantityX = x + 28 + CompactUi.quantityOffset(font, detailName(chosen, ingredient), count, w - 38);
            if (!p.node.selected.category().equals("minecraft:crafting")
                    && ingredient.typed().getIngredient() instanceof ItemStack
                    && mx >= quantityX && mx < quantityX + font.width(count) + 3) {
                openEditor(new Panel(p.node, PanelMode.AMOUNT, List.of(chosen)), p.node.inputAmount(chosen.slot()));
            } else if (chosen.ingredients().size() > 1) variants(p.node, chosen.slot(), chosen.ingredients());
        }
    }

    private void openEditor(Panel panel, long value) {
        panels.push(panel);
        panelInput = new EditBox(font, panelX() + 8, panelY(panel) + 28, 105, 18, Component.literal("单次输入数量"));
        panelInput.setMaxLength(10);
        panelInput.setFilter(text -> text.chars().allMatch(c -> c >= '0' && c <= '9'));
        panelInput.setValue(Long.toString(value));
        panelInput.setFocus(true);
    }

    private void saveInput() {
        var p = panels.peek();
        var value = panelInput.getValue();
        if (value.isEmpty()) return;
        long amount = Long.parseLong(value);
        if (amount <= 0 || amount > Integer.MAX_VALUE) return;
        int slot = p.rows.get(0).slot();
        long original = p.node.selectedInput(slot).amount();
        preferences.put(p.node.amountKey(slot), amount == original ? "" : Long.toString(amount));
        back(); refresh();
    }

    @Override public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging) { panX += dx; panY += dy; return true; } return false;
    }
    @Override public boolean mouseReleased(double mx, double my, int button) { dragging = false; return true; }
    @Override public boolean mouseScrolled(double mx, double my, double delta) {
        if (!panels.isEmpty()) {
            Panel p = panels.peek();
            if (!p.editing())
                p.scroll = Math.max(0, Math.min(p.rows.size() - panelRows(), p.scroll + (delta > 0 ? -1 : 1)));
            return true;
        }
        if (my >= summaryTop()) {
            materialScroll = Math.max(0, Math.min(materialLines.size() - summaryRows(), materialScroll + (delta > 0 ? -1 : 1)));
            return true;
        }
        double next = Math.max(0.3, Math.min(2.5, zoom * Math.pow(1.12, delta)));
        panX = mx - (mx - panX) * next / zoom; panY = my - (my - panY) * next / zoom; zoom = next;
        return true;
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE) { back(); return true; }
        if (panelInput != null) {
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) { saveInput(); return true; }
            return panelInput.keyPressed(key, scan, modifiers);
        }
        if (key == GLFW.GLFW_KEY_BACKSPACE) { back(); return true; }
        return panels.isEmpty() && super.keyPressed(key, scan, modifiers);
    }
    @Override public boolean charTyped(char ch, int modifiers) {
        if (panelInput != null) return panelInput.charTyped(ch, modifiers);
        return panels.isEmpty() && super.charTyped(ch, modifiers);
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() {
        Screen parent = returnTo instanceof RecipesGui ? jeiParent : returnTo;
        if (parent instanceof AbstractContainerScreen<?> container
                && (minecraft.player == null || minecraft.player.containerMenu != container.getMenu())) {
            minecraft.setScreen(null); return;
        }
        if (jeiContext != null) RecipeInteractions.restoreJei(jeiContext);
        else minecraft.setScreen(parent);
    }
}

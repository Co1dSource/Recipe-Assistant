package com.cti.ctirecipeassistant.client.gui;

import com.cti.ctirecipeassistant.recipe.*;
import com.cti.ctirecipeassistant.client.jei.RecipeInteractions;
import mezz.jei.gui.recipes.RecipesGui;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
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
    private boolean positioned;
    private int encodingDepth = 1;
    private TreeLayout layout;
    private double panX = 32, panY, zoom = 1;
    private record View(RecipeNode root, RecipeNode target, double x, double y, double zoom, int depth) {}
    private final Deque<View> roots = new ArrayDeque<>();
    private final Deque<Panel> panels = new ArrayDeque<>();
    private boolean dragging;
    private List<Component> tooltip = List.of();
    private record Row(String label, List<TreeIngredient> ingredients, int slot, String role) {}
    private static final class Panel {
        final RecipeNode node;
        final String mode;
        final List<Row> rows;
        final int slot;
        final String role;
        int page;
        Panel(RecipeNode node, String mode, List<Row> rows, int slot, String role) {
            this.node = node; this.mode = mode; this.rows = rows; this.slot = slot; this.role = role;
        }
    }
    public RecipeTreeScreen(TreeIngredient target, IJeiRuntime runtime) {
        this(target, runtime, null);
    }
    public RecipeTreeScreen(TreeIngredient target, IJeiRuntime runtime, Screen returnTo) {
        super(Component.literal("配方树"));
        this.runtime = runtime;
        returnTo(returnTo);
        index = new JeiRecipeIndex(runtime);
        preferences = new RecipePreferences();
        root = targetRoot = new RecipeNode(target, null, index, preferences);
        root.load(); root.expanded = true;
        encodingDepth = savedDepth();
    }
    private int savedDepth() {
        String saved = preferences.get("pattern-depth|" + targetRoot.target.key());
        return saved.matches("[0-9]{1,10}") ? (int)Math.min(Integer.MAX_VALUE, Long.parseLong(saved)) : 1;
    }
    public RecipeNode finalRoot() { return targetRoot; }
    public JeiRecipeIndex index() { return index; }
    public RecipePreferences preferences() { return preferences; }
    public int encodingDepth() { return encodingDepth; }
    public void encodingDepth(int depth) {
        encodingDepth = depth; preferences.put("pattern-depth|" + targetRoot.target.key(), Integer.toString(depth));
    }
    public void returnTo(Screen screen) {
        returnTo = screen;
        jeiContext = screen instanceof RecipesGui gui ? RecipeInteractions.captureJei(gui) : null;
        jeiParent = jeiContext == null ? null : jeiContext.parent();
    }
    public void refresh() { layout = new TreeLayout(root); }
    public void focus(RecipeNode node) {
        if (node != root) { roots.push(new View(root, targetRoot, panX, panY, zoom, encodingDepth)); root = node; }
        node.expanded = true; center();
    }
    public void retarget(TreeIngredient target, RecipeCandidate recipe) {
        roots.push(new View(root, targetRoot, panX, panY, zoom, encodingDepth));
        root = targetRoot = new RecipeNode(target, null, index, preferences);
        root.useRecipe(recipe); root.expanded = true; encodingDepth = savedDepth(); center();
    }
    @Override protected void init() { if (!positioned) { center(); positioned = true; } else refresh(); }
    private void center() {
        layout = new TreeLayout(root);
        panX = 32;
        panY = height / 2.0 - layout.byNode.get(root).y() * zoom;
    }
    private void open(RecipeNode node) {
        node.load();
        if (node.selected == null && !node.candidates.isEmpty()) recipes(node);
        else node.expanded = !node.expanded;
        layout = new TreeLayout(root);
    }
    private void recipes(RecipeNode node) {
        RecipeInteractions.choose(node, this, this, this::refresh);
    }
    private void details(RecipeNode node) {
        if (node.selected == null) { recipes(node); return; }
        List<Row> rows = new ArrayList<>();
        var recipe = node.selected;
        addRows(rows, recipe.inputs(), "输入", "input");
        addRows(rows, recipe.outputs(), "产物", "output");
        addRows(rows, recipe.catalysts(), "催化剂 / 机器", "catalyst");
        panels.push(new Panel(node, "details", rows, -1, ""));
    }
    private void addRows(List<Row> rows, List<List<TreeIngredient>> slots, String label, String role) {
        for (int i = 0; i < slots.size(); i++) if (!slots.get(i).isEmpty())
            rows.add(new Row(label + " " + (i + 1), slots.get(i), i, role));
    }
    private void back() {
        if (!panels.isEmpty()) panels.pop();
        else if (!roots.isEmpty()) {
            var view = roots.pop();
            if (targetRoot != view.target) encodingDepth = view.depth;
            root = view.root; targetRoot = view.target;
            panX = view.x; panY = view.y; zoom = view.zoom; refresh();
        } else onClose();
    }
    @Override public void render(PoseStack pose, int mouseX, int mouseY, float partialTick) {
        tooltip = List.of();
        fill(pose, 0, 0, width, height, 0xED202020);
        var window = minecraft.getWindow();
        double guiScale = window.getGuiScale();
        RenderSystem.enableScissor(0, (int)(8 * guiScale), window.getWidth(), Math.max(0, (int)((height - 40) * guiScale)));
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
            fill(pose, x, y, x + TreeLayout.WIDTH, y + TreeLayout.HEIGHT, hover ? 0xFFEEEEEE : 0xFF777777);
            fill(pose, x + 1, y + 1, x + TreeLayout.WIDTH - 1, y + TreeLayout.HEIGHT - 1, 0xFFC6C6C6);
            icon(pose, node.target, x + 6, y + 6);
            String quantity = node.selected == null ? node.target.quantity() : node.selected.output(node.target.key()).quantity();
            text(pose, node.target.name(), x + 28, y + 6, 112, 0xFF303030);
            text(pose, "×" + quantity, x + 28, y + 19, 112, 0xFF555555);
            text(pose, node.status(), x + 6, y + 33, 145, node.cycle ? 0xFF995522 : 0xFF555555);
            font.draw(pose, node.expanded ? "−" : "+", x + 150, y + 6, 0xFF555555);
            font.draw(pose, "≡", x + 150, y + 21, 0xFF555555);
            if (hover && panels.isEmpty() && mouseY >= 32 && mouseY < height - 8) {
                tooltip = new ArrayList<>(node.target.tooltip(runtime.getIngredientManager()));
                if (tx >= x + 145 && ty >= y + 19) tooltip = List.of(Component.literal("选择此生产配方"));
            }
        }
        pose.popPose();
        RenderSystem.disableScissor();
        // JEI item rendering writes depth above flat GUI geometry. Start a fresh
        // depth layer so tree icons cannot punch through the toolbar or modal.
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        fill(pose, 0, 0, width, 32, 0xFFC6C6C6);
        text(pose, targetRoot.target.name(), 10, 12, Math.max(10, width - 155), 0xFF303030);
        if (!preferences.error.isEmpty()) text(pose, preferences.error, 10, height - 18, width - 20, 0xFFFFBB88);
        if (!panels.isEmpty()) renderPanel(pose, mouseX, mouseY);
        if (!tooltip.isEmpty()) renderComponentTooltip(pose, tooltip, mouseX, mouseY);
    }
    private int panelWidth() { return Math.min(620, width - 24); }
    private int panelX() { return (width - panelWidth()) / 2; }
    private int rowsPerPage() { return Math.max(1, (height - 140) / 48); }
    private int perPage(Panel p) { return rowsPerPage(); }
    private int count(Panel p) { return p.rows.size(); }
    private void renderPanel(PoseStack pose, int mx, int my) {
        tooltip = List.of();
        Panel p = panels.peek();
        int x = panelX(), w = panelWidth();
        fill(pose, 0, 32, width, height - 32, 0xA0000000);
        CompactUi.panel(pose, x, 40, w, height - 80);
        String heading = p.mode.equals("details") ? "输入与产物" : "选择材料 / 目标产物";
        text(pose, heading, x + 10, 51, w - 145, 0xFF303030);
        if (p.mode.equals("details") && p.node.selected != null)
            font.draw(pose, "删除此生产配方", x + w - 115, 51, 0xFF995522);
        int perPage = perPage(p), start = p.page * perPage;
        for (int i = start; i < Math.min(count(p), start + perPage); i++) {
            int local = i - start;
            int cellWidth = w - 20;
            int cx = x + 10, cy = 74 + local * 48;
            boolean hover = inside(mx, my, cx, cy, cellWidth, 44);
            fill(pose, cx, cy, cx + cellWidth, cy + 44, hover ? 0xFFAAAAAA : 0xFFB8B8B8);
            {
                var row = p.rows.get(i);
                var ingredient = p.mode.equals("details") && row.role().equals("input")
                        ? p.node.selectedInput(row.slot()) : row.ingredients().get(0);
                icon(pose, ingredient, cx + 5, cy + 5);
                text(pose, row.label() + "  " + ingredient.name(), cx + 27, cy + 6, cellWidth - 32, 0xFF303030);
                text(pose, "×" + ingredient.quantity() + (row.ingredients().size() > 1 ? "  [" + row.ingredients().size() + " 候选]" : ""), cx + 27, cy + 23, cellWidth - 32, 0xFF555555);
                if (hover) tooltip = ingredient.tooltip(runtime.getIngredientManager());
            }
        }
        int pages = Math.max(1, (count(p) + perPage - 1) / perPage);
        if (pages > 1) font.draw(pose, "" + (p.page + 1) + "/" + pages, x + 10, height - 55, 0xFF555555);
    }
    private void icon(PoseStack pose, TreeIngredient ingredient, int x, int y) {
        pose.pushPose(); pose.translate(x, y, 0); ingredient.render(pose, runtime.getIngredientManager()); pose.popPose();
    }
    private void text(PoseStack pose, String value, int x, int y, int maxWidth, int color) {
        font.draw(pose, font.plainSubstrByWidth(value, Math.max(1, maxWidth)), x, y, color);
    }
    private boolean inside(double mx, double my, int x, int y, int w, int h) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    @Override public boolean mouseClicked(double mx, double my, int button) {
        if (my < 32) return true;
        if (!panels.isEmpty()) { panelClick(mx, my, button); return true; }
        if (my >= height - 8) return true;
        double tx = (mx - panX) / zoom, ty = (my - panY) / zoom;
        for (var view : layout.views) if (inside(tx, ty, view.x(), view.y(), TreeLayout.WIDTH, TreeLayout.HEIGHT)) {
            if (button == 2) focus(view.node());
            else if (button == 1) details(view.node());
            else if (button == 0) {
                if (tx >= view.x() + 145 && ty >= view.y() + 19) recipes(view.node());
                else open(view.node());
            }
            return true;
        }
        dragging = button == 0 || button == 2;
        return dragging;
    }
    private void panelClick(double mx, double my, int button) {
        if (button != 0) return;
        Panel p = panels.peek();
        int x = panelX(), w = panelWidth(), perPage = perPage(p);
        if (!inside(mx, my, x, 40, w, height - 80)) return;
        if (my < 70) {
            if (p.mode.equals("details") && mx >= x + w - 122 && p.node.selected != null) {
                p.node.removeProduction(); panels.clear(); refresh();
            }
            return;
        }
        if (my >= height - 65) return;
        int cellWidth = w - 20;
        int row = (int)(my - 74) / 48;
        if (my < 74 || row >= rowsPerPage() || (my - 74) % 48 >= 44) return;
        int i = p.page * perPage + row;
        if (i >= count(p)) return;
        if (p.mode.equals("details")) {
            Row chosen = p.rows.get(i);
            List<Row> alternatives = chosen.ingredients().stream().map(a -> new Row(chosen.label(), List.of(a), chosen.slot(), chosen.role())).toList();
            panels.push(new Panel(p.node, "ingredients", alternatives, chosen.slot(), chosen.role()));
        } else {
            TreeIngredient ingredient = p.rows.get(i).ingredients().get(0);
            if (p.role.equals("input")) {
                p.node.chooseInput(p.slot, ingredient); p.node.expanded = true;
                panels.pop(); layout = new TreeLayout(root);
            } else if (p.role.equals("output")) {
                var recipe = p.node.selected;
                panels.clear(); retarget(ingredient, recipe);
            }
        }
    }
    @Override public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging) { panX += dx; panY += dy; return true; } return false;
    }
    @Override public boolean mouseReleased(double mx, double my, int button) { dragging = false; return true; }
    @Override public boolean mouseScrolled(double mx, double my, double delta) {
        if (!panels.isEmpty()) {
            Panel p = panels.peek(); int pages = Math.max(1, (count(p) + perPage(p) - 1) / perPage(p));
            p.page = Math.max(0, Math.min(pages - 1, p.page + (delta > 0 ? -1 : 1))); return true;
        }
        double next = Math.max(0.3, Math.min(2.5, zoom * Math.pow(1.12, delta)));
        panX = mx - (mx - panX) * next / zoom; panY = my - (my - panY) * next / zoom; zoom = next;
        return true;
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_BACKSPACE || key == GLFW.GLFW_KEY_ESCAPE) { back(); return true; }
        return super.keyPressed(key, scan, modifiers);
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

package com.cti.recipeassistant.integration.ae2;

import appeng.api.stacks.GenericStack;
import appeng.integration.modules.jei.GenericEntryStackHelper;
import appeng.menu.AutoCraftingMenu;
import appeng.client.gui.Icon;
import appeng.client.gui.widgets.ToggleButton;
import appeng.core.localization.ButtonToolTips;
import appeng.api.stacks.AmountFormat;
import com.cti.recipeassistant.client.gui.CompactUi;
import com.cti.recipeassistant.client.operation.*;
import com.cti.recipeassistant.client.jei.RecipeInteractions;
import com.cti.recipeassistant.client.jei.TreeJeiPlugin;
import com.cti.recipeassistant.integration.PatternConfirmation;
import com.cti.recipeassistant.recipe.*;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.systems.RenderSystem;
import mezz.jei.common.Internal;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.gui.GuiProperties;
import mezz.jei.gui.elements.GuiIconButtonSmall;
import mezz.jei.gui.ghost.GhostIngredientDrag;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import java.util.*;

/** Displays the exact input selection used by PatternEncoder; no recipe text inference. */
public final class PatternPreviewScreen extends Screen implements PatternConfirmation {
    private static final int LIST_TOP = 42, INPUT_ROW = 20, JEI_SPACE = 100;
    private final ClientRecipeOperation<?> operation;
    private final JeiRecipeIndex index;
    private final RecipePreferences preferences;
    private final RecipeInteractions.JeiContext jeiContext;
    private final List<TreeIngredient> targets;
    private RecipeNode root;
    private int targetIndex, problemIndex, scroll;
    private PatternEncoder.Preview preview;
    private final Map<String, PatternEncoder.Substitutions> substitutions = new HashMap<>();
    private final Set<String> removed = new HashSet<>();
    private final Map<String, Map<Integer, ItemStack>> inputEdits = new HashMap<>();
    private List<Rect2i> dragHighlights;
    private record MaterialCell(PatternEncoder.Entry entry, int slot, boolean output, Rect2i area) {
        GenericStack stack() { return output ? entry.processingOutput(slot) : entry.input(slot); }
    }
    private String error = "";
    private List<Component> tooltip = List.of();
    public PatternPreviewScreen(ClientRecipeOperation<?> operation) {
        super(Component.literal("批量编码"));
        this.operation = operation;
        index = new JeiRecipeIndex(TreeJeiPlugin.runtime);
        preferences = new RecipePreferences();
        jeiContext = RecipeInteractions.captureJei((mezz.jei.gui.recipes.RecipesGui) TreeJeiPlugin.runtime.getRecipesGui());
        var unique = new LinkedHashMap<String, TreeIngredient>();
        for (var slot : operation.candidate().outputs()) for (var target : slot) unique.putIfAbsent(target.key(), target);
        targets = List.copyOf(unique.values());
        for (int i = 0; i < targets.size(); i++) if (targets.get(i).key().equals(operation.target().key())) targetIndex = i;
        resetRoot(operation.target());
        rebuild(false);
    }
    private TreeIngredient target() { return root.target; }
    private void resetRoot(TreeIngredient target) {
        inputEdits.clear();
        root = new RecipeNode(target, null, index, preferences);
        var recipe = RecipeInteractions.layout(operation).map(layout -> index.readLayout(layout, target)).orElse(operation.candidate());
        root.useRecipe(recipe);
    }
    private void confirm() {
        rebuild(false);
        error = Optional.ofNullable(PatternEncoder.start(preview)).orElse("");
    }
    private void rebuild(boolean reset) {
        preview = PatternEncoder.prepare(operation, root, index, preferences, removed, inputEdits);
        for (var entry : preview.entries()) if (entry.crafting)
            entry.substitutions = substitutions.getOrDefault(entry.patternKey(), entry.substitutions);
        if (reset) { scroll = 0; problemIndex = 0; }
        problemIndex = Math.min(problemIndex, Math.max(0, preview.issues().size() - 1));
        scroll = Math.min(scroll, maxScroll());
    }
    public void locateProblem() {
        currentIssue().ifPresent(issue -> openEncodingRecipes(issue.node()));
    }
    private void openEncodingRecipes(RecipeNode node) {
        RecipeInteractions.choose(node, index, this, () -> { error = ""; rebuild(false); });
    }
    @Override public boolean canChooseEncodingRecipe(RecipeNode node, RecipeCandidate recipe) {
        var layout = index.layout(recipe.id());
        return encodingOccurrences(node).stream().allMatch(occurrence -> PatternEncoder.canChooseRecipe(
                occurrence.previewProduction(recipe), layout, preview.menu()));
    }
    @Override public void chooseEncodingRecipe(RecipeNode node, RecipeCandidate recipe) {
        for (var occurrence : encodingOccurrences(node)) {
            if (occurrence.selected == null || !occurrence.selected.id().equals(recipe.id())) {
                occurrence.useRecipe(recipe);
                var edits = inputEdits.get(PatternEncoder.patternKey(occurrence));
                if (edits != null) {
                    var entry = new PatternEncoder.Entry(occurrence, index.layout(recipe.id()), preferences);
                    edits.forEach((slot, stack) -> { if (slot >= 0) editChild(entry, occurrence, slot, stack); });
                }
            }
            removed.remove(PatternEncoder.patternKey(occurrence));
        }
    }
    private List<RecipeNode> encodingOccurrences(RecipeNode source) {
        var occurrences = new ArrayList<RecipeNode>();
        collectOccurrences(root, source, occurrences);
        return occurrences;
    }
    private void collectOccurrences(RecipeNode node, RecipeNode source, List<RecipeNode> occurrences) {
        if (node == source || node.target.key().equals(source.target.key())
                && (source.selected == null ? node.selected == null : node.selected != null && node.selected.id().equals(source.selected.id()))) {
            occurrences.add(node);
            return;
        }
        for (var child : node.children) collectOccurrences(child, source, occurrences);
    }
    public Optional<PatternPlanner.Issue> currentIssue() {
        return preview.issues().isEmpty() ? Optional.empty() : Optional.of(preview.issues().get(problemIndex));
    }
    @Override public TreeIngredient ingredientAt(double x, double y) {
        var node = encodingNodeAt(x, y);
        return node == null ? null : node.target;
    }
    private RecipeNode encodingNodeAt(double x, double y) {
        var material = materialAt(x, y);
        if (material != null) return materialNode(material);
        int left = left() + 10;
        if (CompactUi.hit(x, y, left, 18, 16, 16)) return root;
        if (CompactUi.hit(x, y, left, height - 68, 16, 16) && !preview.issues().isEmpty())
            return currentIssue().orElseThrow().node();
        if (y < LIST_TOP || y >= listBottom()) return null;
        int top = LIST_TOP - scroll;
        for (var entry : preview.entries()) {
            int bottom = top + rowHeight(entry);
            if (y >= top && y < bottom && entry.editable) {
                if (entry.crafting && CompactUi.hit(x, y, left + 76, top + 25, 16, 16)) return entry.node;
                return null;
            }
            top = bottom;
        }
        return null;
    }
    private int left() { return Math.max(8, Math.min((width - span()) / 2, width - span() - JEI_SPACE)); }
    private int span() { return Math.min(334, width - JEI_SPACE - 8); }
    private int listBottom() { return height - (preview.issues().isEmpty() && error.isEmpty() ? 35 : 76); }
    private int inputWidth() { return Math.max(54, span() - 112); }
    private int columns() { return Math.max(1, inputWidth() / 104); }
    private int arrowX() { return left() + 10 + inputWidth(); }
    private int materialX(PatternEncoder.Entry entry, int slot) {
        return left() + 10 + (entry.crafting ? slot % 3 * 18 + 1 : slot % columns() * (inputWidth() / columns()));
    }
    private int materialY(PatternEncoder.Entry entry, int top, int slot) {
        return top + 21 + (entry.crafting ? slot / 3 * 18 : slot / columns() * INPUT_ROW);
    }
    private int rowHeight(PatternEncoder.Entry entry) {
        if (!entry.editable) return 42;
        if (entry.crafting) return 80;
        int inputs = 0;
        for (int slot = 0, size = entry.processingSize(); slot < size; slot++)
            if (entry.processingInput(slot) != null) inputs++;
        int inputRows = (inputs + columns() - 1) / columns();
        return 26 + Math.max(inputRows, entry.processingOutputSize()) * INPUT_ROW;
    }
    private List<MaterialCell> visibleMaterials() {
        var cells = new ArrayList<MaterialCell>();
        int top = LIST_TOP - scroll;
        int bottom = listBottom();
        for (var entry : preview.entries()) {
            if (top >= bottom) break;
            if (entry.editable && top + rowHeight(entry) > LIST_TOP) {
                int count = entry.crafting ? 9 : entry.processingSize();
                int shown = 0;
                for (int slot = 0; slot < count; slot++) {
                    if (!entry.crafting && entry.processingInput(slot) == null) continue;
                    addCell(cells, entry, slot, false, materialX(entry, shown), materialY(entry, top, shown++), 16, 16, bottom);
                }
                if (!entry.crafting) {
                    for (int slot = 0, size = entry.processingOutputSize(); slot < size; slot++)
                        addCell(cells, entry, slot, true, arrowX() + 18, top + 25 + slot * INPUT_ROW, 16, 16, bottom);
                }
            }
            top += rowHeight(entry);
        }
        return cells;
    }
    private static void addCell(List<MaterialCell> cells, PatternEncoder.Entry entry, int slot, boolean output,
                                int x, int y, int width, int height, int listBottom) {
        int from = Math.max(LIST_TOP, y), to = Math.min(listBottom, y + height);
        if (from < to) cells.add(new MaterialCell(entry, slot, output, new Rect2i(x, from, width, to - from)));
    }
    private List<MaterialCell> dropCells() {
        var cells = visibleMaterials();
        int top = LIST_TOP - scroll, bottom = listBottom(), split = arrowX() + 8;
        int start = left() + 6, end = left() + span() - 6;
        for (var entry : preview.entries()) {
            if (top >= bottom) break;
            int height = rowHeight(entry);
            if (entry.editable && !entry.crafting && top + height > LIST_TOP) {
                int input = 0;
                int maxInputs = preview.menu().getProcessingInputSlots().length;
                while (input < maxInputs && entry.processingInput(input) != null) input++;
                if (input < maxInputs) addCell(cells, entry, input, false, start, top, split - start, height, bottom);
                int output = entry.processingOutputSize();
                if (output < preview.menu().getProcessingOutputSlots().length)
                    addCell(cells, entry, output, true, split, top, end - split, height, bottom);
            }
            top += height;
        }
        return cells;
    }
    private MaterialCell materialAt(double x, double y) {
        for (var cell : visibleMaterials()) {
            var area = cell.area();
            if (CompactUi.hit(x, y, area.getX(), area.getY(), area.getWidth(), area.getHeight())) return cell;
        }
        return null;
    }
    private RecipeNode materialNode(MaterialCell cell) {
        var entry = cell.entry();
        int slot = cell.slot();
        if (cell.output()) {
            var original = GenericEntryStackHelper.ingredientToStack(entry.output().typed());
            return slot == 0 && cell.stack().what().equals(original.what()) ? entry.node : null;
        }
        if (!entry.crafting) {
            int originalSlot = entry.recipeInputSlot(slot);
            slot = originalSlot >= 0 ? originalSlot : entry.node.selected.inputs().size() + slot;
        }
        for (var child : entry.node.children) if (child.inputSlot() == slot) return child;
        return null;
    }
    private boolean accepts(MaterialCell cell, ItemStack stack) {
        if (cell.entry().crafting)
            return !GenericStack.isWrapped(stack) && preview.menu().getCraftingGridSlots()[cell.slot()].canSetFilterTo(stack);
        var slots = cell.output() ? preview.menu().getProcessingOutputSlots() : preview.menu().getProcessingInputSlots();
        return slots[cell.slot()].canSetFilterTo(stack);
    }
    private void replace(MaterialCell cell, ItemStack stack) {
        replace(cell, stack, false);
    }
    private void replace(MaterialCell cell, ItemStack stack, boolean matchCraftingVariants) {
        var copy = stack.copy();
        if (cell.entry().crafting && !copy.isEmpty()) copy.setCount(1);
        var edits = inputEdits.computeIfAbsent(cell.entry().patternKey(), ignored -> new HashMap<>());
        if (cell.output()) edits.put(PatternEncoder.Entry.outputEditKey(cell.slot()), copy);
        else {
            var slots = matchCraftingVariants && cell.entry().crafting ? matchingVariantSlots(cell, copy) : List.of(cell.slot());
            var occurrences = encodingOccurrences(cell.entry().node);
            for (int slot : slots) {
                edits.put(slot, copy);
                for (var node : occurrences) editChild(cell.entry(), node, slot, copy);
            }
        }
        error = "";
        rebuild(false);
    }
    private List<Integer> matchingVariantSlots(MaterialCell source, ItemStack replacement) {
        var entry = source.entry();
        var original = ItemIdentity.of(entry.craftingInput(source.slot()));
        var selected = ItemIdentity.of(replacement);
        var slots = new ArrayList<Integer>();
        slots.add(source.slot());
        for (int slot = 0; slot < 9; slot++) {
            if (slot == source.slot() || !ItemIdentity.of(entry.craftingInput(slot)).equals(original)) continue;
            var cell = new MaterialCell(entry, slot, false, source.area());
            if (variants(cell).stream().anyMatch(candidate -> ItemIdentity.of(candidate).equals(selected))) slots.add(slot);
        }
        return slots;
    }
    private static void editChild(PatternEncoder.Entry entry, RecipeNode node, int slot, ItemStack stack) {
        if (entry.crafting) {
            node.editPreviewInput(slot, stack.isEmpty() ? null : inputIngredient(stack));
            return;
        }
        var input = GenericStack.fromItemStack(stack);
        int originalSlot = entry.recipeInputSlot(slot);
        int childSlot = originalSlot >= 0 ? originalSlot : node.selected.inputs().size() + slot;
        node.editPreviewInput(childSlot, input == null ? null : inputIngredient(GenericEntryStackHelper.stackToIngredient(input)));
    }
    private static TreeIngredient inputIngredient(Object stack) {
        var manager = TreeJeiPlugin.runtime.getIngredientManager();
        var type = manager.getIngredientTypeChecked(stack).orElseThrow();
        return TreeIngredient.ofInput(manager.createTypedIngredient(type, stack).orElseThrow(), manager);
    }
    private List<ItemStack> variants(MaterialCell cell) {
        var entry = cell.entry();
        var input = entry.input(cell.slot());
        if (input == null || !(input.what() instanceof appeng.api.stacks.AEItemKey current)) return List.of();
        int slot = cell.slot();
        if (!entry.crafting) {
            slot = entry.recipeInputSlot(slot);
            if (slot < 0) return List.of();
        }
        var candidates = new ArrayList<ItemStack>();
        for (var option : entry.node.selected.inputs().get(slot)) {
            if (option.typed().getIngredient() instanceof ItemStack stack) candidates.add(stack);
        }
        if (candidates.size() == 1) {
            if (entry.crafting) addCraftingVariants(entry, slot, candidates);
            else if (entry.layout.getRecipe() instanceof Recipe<?> recipe) {
                var ingredients = recipe.getIngredients();
                if (ingredients.size() == entry.node.selected.inputs().size() && ingredients.get(slot).test(candidates.get(0)))
                    candidates.addAll(List.of(ingredients.get(slot).getItems()));
            }
        }
        var alternatives = new LinkedHashMap<Object, ItemStack>();
        for (var stack : candidates) if (!stack.isEmpty() && accepts(cell, stack))
            alternatives.putIfAbsent(entry.crafting ? ItemIdentity.of(stack) : appeng.api.stacks.AEItemKey.of(stack), stack);
        alternatives.remove(entry.crafting ? ItemIdentity.of(entry.craftingInput(cell.slot())) : current);
        return List.copyOf(alternatives.values());
    }
    private void addCraftingVariants(PatternEncoder.Entry entry, int slot, List<ItemStack> candidates) {
        var recipe = (CraftingRecipe) entry.layout.getRecipe();
        var original = candidates.get(0);
        var grid = new CraftingContainer(new AutoCraftingMenu(), 3, 3);
        for (int i = 0; i < 9; i++) {
            var options = entry.node.selected.inputs().get(i);
            if (!options.isEmpty()) grid.setItem(i, ((ItemStack) entry.node.selectedInput(i).typed().getIngredient()).copy());
        }
        // JEI layouts can narrow a tag to one stack. Check each native candidate in the clicked slot.
        for (var ingredient : recipe.getIngredients()) if (ingredient.test(original)) {
            for (var stack : ingredient.getItems()) {
                var single = stack.copy();
                single.setCount(1);
                grid.setItem(slot, single);
                if (recipe.matches(grid, minecraft.level)) candidates.add(single);
            }
        }
    }
    public static void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addGuiScreenHandler(PatternPreviewScreen.class, screen -> {
            // JEI queries the opening screen before Minecraft initializes its dimensions.
            int span = screen.span();
            if (span <= 0 || screen.height <= 16) return null;
            return new GuiProperties(PatternPreviewScreen.class, screen.left(), 8, span,
                    screen.height - 16, screen.width, screen.height);
        });
        registration.addGhostIngredientHandler(PatternPreviewScreen.class, new IGhostIngredientHandler<>() {
            private PatternPreviewScreen active;
            @Override public <I> List<Target<I>> getTargets(PatternPreviewScreen screen, I ingredient, boolean doStart) {
                if (!doStart || !(ingredient instanceof ItemStack stack) || stack.isEmpty() || GenericStack.isWrapped(stack))
                    return List.of();
                var targets = new ArrayList<Target<I>>();
                var highlights = new ArrayList<Rect2i>();
                screen.dragHighlights = highlights;
                for (var cell : screen.dropCells()) if (screen.accepts(cell, stack)) {
                    if (cell.entry().crafting) highlights.add(cell.area());
                    targets.add(new Target<>() {
                        @Override public Rect2i getArea() { return cell.area(); }
                        @Override public void accept(I value) {
                            if (screen.dragHighlights != null) screen.replace(cell, (ItemStack) value);
                        }
                    });
                }
                if (targets.isEmpty()) screen.endDrag();
                else active = screen;
                return targets;
            }
            @Override public boolean shouldHighlightTargets() { return false; }
            @Override public void onComplete() {
                if (active != null) active.endDrag();
                active = null;
            }
        });
    }

    private void endDrag() {
        dragHighlights = null;
        scroll = Math.min(scroll, maxScroll());
    }
    private int maxScroll() { return Math.max(0, preview.entries().stream().mapToInt(this::rowHeight).sum() - Math.max(1, listBottom() - LIST_TOP)); }
    @Override protected void init() {
        dragHighlights = null;
        clearWidgets(); int l = left();
        if (targets.size() > 1) addRenderableWidget(new Button(l + span() - 28, 18, 18, 18, Component.literal(">"), b -> {
            targetIndex = (targetIndex + 1) % targets.size(); resetRoot(targets.get(targetIndex));
            error = "";
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
    private int encodingButtonX(PatternEncoder.Entry entry, int number) {
        return entry.crafting ? substitutionX(number) + 36 : left() + span() - 59;
    }
    private GuiIconButtonSmall encodingRecipeButton(PatternEncoder.Entry entry, int number, int top) {
        var textures = Internal.getTextures();
        return new GuiIconButtonSmall(encodingButtonX(entry, number), top + 2, 13, 13, textures.getArrowPrevious(),
                button -> openEncodingRecipes(entry.node), textures);
    }

    private void renderSubstitution(PoseStack pose, ToggleButton button, int mx, int my, float partial) {
        button.render(pose, mx, my, partial);
        if (my >= LIST_TOP && my < listBottom() && button.isMouseOver(mx, my))
            tooltip = button.getTooltipMessage();
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (dragHighlights != null) return true;
        if (CompactUi.hit(mouseX, mouseY, left() + 6, LIST_TOP, span() - 12, Math.max(1, listBottom() - LIST_TOP))) {
            scroll = Math.max(0, Math.min(maxScroll(), scroll + (delta > 0 ? -26 : 26))); return true;
        }
        if (!preview.issues().isEmpty() && CompactUi.hit(mouseX, mouseY, left() + 6, height - 73, span() - 12, 37)) {
            problemIndex = Math.max(0, Math.min(preview.issues().size() - 1, problemIndex + (delta > 0 ? -1 : 1))); return true;
        }
        return false;
    }
    @Override public boolean mouseClicked(double x, double y, int button) {
        if (dragHighlights != null) return true;
        if (button == 2) {
            var cell = materialAt(x, y);
            if (cell != null && !cell.entry().crafting) {
                minecraft.setScreen(new AmountScreen(this, cell.entry(), cell));
                return true;
            }
        }
        if (button == 1 || button == 0 && hasShiftDown()) {
            var cell = materialAt(x, y);
            if (cell != null && !cell.output()) {
                if (button == 0) {
                    if (cell.entry().input(cell.slot()) != null) replace(cell, ItemStack.EMPTY);
                } else {
                    var variants = variants(cell);
                    if (!variants.isEmpty()) minecraft.setScreen(new VariantScreen(this, cell, variants));
                }
                return true;
            }
        }
        if (button == 0) {
            var node = encodingNodeAt(x, y);
            if (node != null) { openEncodingRecipes(node); return true; }
        }
        if (button == 0 && y >= LIST_TOP && y < listBottom()) {
            int top = LIST_TOP - scroll, number = 1;
            for (var entry : preview.entries()) {
                if (y >= top + 1 && y < top + 17) {
                    if (encodingRecipeButton(entry, number, top).mouseClicked(x, y, button)) return true;
                    if (CompactUi.hit(x, y, encodingButtonX(entry, number) + 15, top + 2, 13, 13)) {
                        removed.add(entry.patternKey());
                        error = "";
                        rebuild(false);
                        return true;
                    }
                    if (!entry.editable) break;
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
                l, top + 4, w - (entry.crafting ? 54 : 53), 0x555555);
        var recipes = encodingRecipeButton(entry, number, top);
        recipes.render(pose, mx, my, partial);
        if (my >= LIST_TOP && my < listBottom() && recipes.isMouseOver(mx, my))
            tooltip = List.of(Component.literal("在 JEI 选择编码配方"));
        int removeX = encodingButtonX(entry, number) + 15;
        boolean overRemove = my >= LIST_TOP && my < listBottom() && CompactUi.hit(mx, my, removeX, top + 2, 13, 13);
        CompactUi.button(pose, removeX, top + 2, 13, 13, overRemove, true);
        font.drawShadow(pose, "×", removeX + (13 - font.width("×")) / 2f, top + 5, 0xFFFFFF);
        if (overRemove) tooltip = List.of(Component.literal("删除此预览样板"));
        if (!entry.editable) { text(pose, entry.problem, l, top + 18, w, 0xAA3333); return; }
        if (!entry.crafting) {
            CompactUi.button(pose, l + w - 18, top + 1, 18, 16, CompactUi.hit(mx, my, l + w - 18, top + 1, 18, 16), true);
            drawCenteredString(pose, font, "#", l + w - 9, top + 5, 0xFFFFFF);
            if (my >= LIST_TOP && my < listBottom() && CompactUi.hit(mx, my, l + w - 18, top + 1, 18, 16)) tooltip = List.of(Component.literal("输入数量"));
            int cell = inputWidth() / columns();
            int shown = 0;
            for (int i = 0, size = entry.processingSize(); i < size; i++) {
                var stack = entry.processingInput(i);
                if (stack != null) input(pose, stack, materialX(entry, shown), materialY(entry, top, shown++), cell, mx, my);
            }
            int x = arrowX() + 18, y = top + 25, outputWidth = left() + span() - x - 10;
            text(pose, "→", arrowX(), y + 4, 16, 0x555555);
            for (int i = 0, size = entry.processingOutputSize(); i < size; i++)
                input(pose, entry.processingOutput(i), x, y + i * INPUT_ROW, outputWidth, mx, my);
            return;
        } else {
            int toggleX = substitutionX(number);
            renderSubstitution(pose, substitutionButton(entry, false, toggleX, top + 1), mx, my, partial);
            renderSubstitution(pose, substitutionButton(entry, true, toggleX + 18, top + 1), mx, my, partial);
            for (int i = 0; i < 9; i++) {
                int x = materialX(entry, i), y = materialY(entry, top, i);
                CompactUi.slot(pose, x - 1, y - 1);
                var stack = entry.craftingInput(i);
                if (!stack.isEmpty()) {
                    itemRenderer.renderAndDecorateItem(stack, x, y);
                    if (my >= LIST_TOP && my < listBottom() && CompactUi.hit(mx, my, x, y, 16, 16)) tooltip = getTooltipFromItem(stack);
                }
            }
        }
        int x = l + 76, y = top + 25;
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
        String count = "样板 " + preview.entries().size();
        int countX = l + w - (targets.size() > 1 ? 34 : 10) - font.width(count);
        text(pose, target().name(), l + 32, 22, countX - l - 38, 0x303030);
        font.draw(pose, count, countX, 22, 0x555555);
        var window = minecraft.getWindow(); double scale = window.getGuiScale();
        RenderSystem.enableScissor((int)((l + 6) * scale), (int)((height - listBottom()) * scale),
                (int)((w - 12) * scale), (int)(Math.max(1, listBottom() - LIST_TOP) * scale));
        int top = LIST_TOP - scroll, number = 1;
        for (var entry : preview.entries()) {
            int h = rowHeight(entry); if (top + h > LIST_TOP && top < listBottom()) row(pose, entry, number, top, mouseX, mouseY, partial);
            top += h; number++;
        }
        RenderSystem.disableScissor();
        if (dragHighlights != null) GhostIngredientDrag.drawTargets(pose, mouseX, mouseY, dragHighlights);
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
    @Override public void removed() { dragHighlights = null; }
    @Override public void onClose() {
        if (minecraft.player != null && minecraft.player.containerMenu == preview.menu())
            RecipeInteractions.restoreJei(jeiContext);
        else minecraft.setScreen(null);
    }

    private static final class VariantScreen extends Screen {
        private static final int COLUMNS = 9, CELL = 20;
        private final PatternPreviewScreen parent;
        private final MaterialCell cell;
        private final List<ItemStack> variants;
        private int offset;
        VariantScreen(PatternPreviewScreen parent, MaterialCell cell, List<ItemStack> variants) {
            super(Component.literal("材料变体"));
            this.parent = parent; this.cell = cell; this.variants = variants;
        }
        private int rows() { return Math.min((variants.size() + COLUMNS - 1) / COLUMNS, Math.max(1, (height - 32) / CELL)); }
        private int left() { return (width - COLUMNS * CELL) / 2; }
        private int top() { return (height - rows() * CELL) / 2; }
        @Override public boolean mouseScrolled(double x, double y, double delta) {
            int max = Math.max(0, (variants.size() + COLUMNS - 1) / COLUMNS - rows());
            offset = Math.max(0, Math.min(max, offset + (delta > 0 ? -1 : 1)));
            return true;
        }
        @Override public boolean mouseClicked(double x, double y, int button) {
            if (button != 0) return false;
            if (!CompactUi.hit(x, y, left(), top(), COLUMNS * CELL, rows() * CELL)) { onClose(); return true; }
            int index = (offset + (int)(y - top()) / CELL) * COLUMNS + (int)(x - left()) / CELL;
            if (index < variants.size()) {
                var stack = variants.get(index);
                if (!cell.entry().crafting)
                    stack = GenericStack.wrapInItemStack(appeng.api.stacks.AEItemKey.of(stack), cell.entry().input(cell.slot()).amount());
                parent.replace(cell, stack, true);
                onClose();
            }
            return true;
        }
        @Override public void render(PoseStack pose, int mouseX, int mouseY, float partial) {
            renderBackground(pose);
            int left = left(), top = top(), rows = rows();
            CompactUi.panel(pose, left - 5, top - 5, COLUMNS * CELL + 10, rows * CELL + 10);
            ItemStack hovered = ItemStack.EMPTY;
            for (int i = 0; i < rows * COLUMNS && offset * COLUMNS + i < variants.size(); i++) {
                int x = left + i % COLUMNS * CELL, y = top + i / COLUMNS * CELL;
                var stack = variants.get(offset * COLUMNS + i);
                CompactUi.slot(pose, x, y);
                itemRenderer.renderAndDecorateItem(stack, x + 1, y + 1);
                if (CompactUi.hit(mouseX, mouseY, x, y, 18, 18)) hovered = stack;
            }
            if (!hovered.isEmpty()) renderTooltip(pose, hovered, mouseX, mouseY);
        }
        @Override public void tick() {
            if (minecraft.player == null || minecraft.player.containerMenu != parent.preview.menu()) minecraft.setScreen(null);
        }
        @Override public boolean isPauseScreen() { return false; }
        @Override public void onClose() { minecraft.setScreen(parent); }
    }

    private static final class AmountScreen extends Screen {
        private final PatternPreviewScreen parent;
        private final PatternEncoder.Entry entry;
        private final MaterialCell cell;
        private final List<Integer> inputs = new ArrayList<>();
        private final Map<Integer, String> values = new HashMap<>();
        private final Map<Integer, EditBox> boxes = new HashMap<>();
        private int page;
        private String error = "";
        AmountScreen(PatternPreviewScreen parent, PatternEncoder.Entry entry) {
            this(parent, entry, null);
        }
        AmountScreen(PatternPreviewScreen parent, PatternEncoder.Entry entry, MaterialCell cell) {
            super(Component.literal(cell != null && cell.output() ? "处理样板产物数量" : "处理样板输入数量"));
            this.parent = parent; this.entry = entry; this.cell = cell;
            if (cell != null) {
                inputs.add(cell.slot());
                values.put(cell.slot(), Long.toString(cell.stack().amount()));
            } else if (entry.edited()) {
                for (int i = 0; i < entry.processingSize(); i++) {
                    var input = entry.processingInput(i);
                    if (input != null) { inputs.add(i); values.put(i, Long.toString(input.amount())); }
                }
            } else {
                for (int i = 0; i < entry.node.selected.inputs().size(); i++) if (!entry.node.selected.inputs().get(i).isEmpty()) {
                    inputs.add(i); values.put(i, Long.toString(entry.amounts.getOrDefault(i, entry.node.selectedInput(i).amount())));
                }
            }
        }
        private String inputName(int slot) {
            String name = cell != null ? cell.stack().what().getDisplayName().getString()
                    : entry.edited() ? entry.processingInput(slot).what().getDisplayName().getString() : entry.node.selectedInput(slot).name();
            return font.plainSubstrByWidth(name, Math.max(0, Math.min(215, width - 102)));
        }
        private long originalAmount(int slot) {
            if (cell != null) {
                var original = cell.output() ? null : entry.defaultInput(slot);
                return original == null ? cell.stack().amount() : original.amount();
            }
            if (!entry.edited()) return entry.node.selectedInput(slot).amount();
            var original = entry.defaultInput(slot);
            return original == null ? entry.processingInput(slot).amount() : original.amount();
        }
        private int pageSize() { return Math.max(1, (height - 115) / 26); }
        private void capture() { boxes.forEach((index, box) -> values.put(index, box.getValue())); }
        private int compactWidth() { return Math.min(178, width - 16); }
        private int compactLeft() { return (width - compactWidth()) / 2; }
        private int compactTop() { return (height - 83) / 2; }
        private EditBox quantityBox(int slot, int x, int y) {
            var box = new EditBox(font, x, y, 82, 20, Component.literal("数量"));
            box.setMaxLength(10); box.setFilter(s -> s.matches("[0-9]*")); box.setValue(values.get(slot));
            boxes.put(slot, addRenderableWidget(box));
            return box;
        }
        @Override protected void init() {
            clearWidgets(); boxes.clear();
            if (cell != null) {
                int middle = width / 2, top = compactTop();
                setInitialFocus(quantityBox(cell.slot(), middle - 41, top + 20));
                addRenderableWidget(new Button(middle - 55, top + 56, 50, 18, Component.literal("保存"), b -> save()));
                addRenderableWidget(new Button(middle + 5, top + 56, 50, 18, Component.literal("取消"), b -> onClose()));
                return;
            }
            int left = Math.max(8, (width - 314) / 2);
            for (int row = 0; row < pageSize(); row++) {
                int index = page * pageSize() + row; if (index >= inputs.size()) break;
                int input = inputs.get(index);
                int quantityX = left + font.width(inputName(input)) + 4;
                quantityBox(input, quantityX, 47 + row * 26);
            }
            addRenderableWidget(new Button(left, height - 37, 75, 20, Component.literal("恢复原量"), b -> {
                for (int input : inputs) values.put(input, Long.toString(originalAmount(input))); init();
            }));
            addRenderableWidget(new Button(left + 82, height - 37, 90, 20, Component.literal("保存数量"), b -> save()));
        }
        @Override public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
            if (cell != null || mouseY < 44 || mouseY >= height - 65) return false;
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
                if (cell != null) {
                    var stack = cell.stack();
                    if (stack.amount() != amount) parent.replace(cell, GenericStack.wrapInItemStack(stack.what(), amount));
                    minecraft.setScreen(parent);
                    return;
                }
                if (entry.edited() || amount != originalAmount(i)) changed.put(i, amount);
            }
            if (entry.edited()) {
                var edits = parent.inputEdits.get(entry.patternKey());
                changed.forEach((slot, amount) -> {
                    var input = entry.processingInput(slot);
                    if (input.amount() != amount) edits.put(slot, GenericStack.wrapInItemStack(input.what(), amount));
                });
                onClose();
                return;
            }
            entry.amounts.clear(); entry.amounts.putAll(changed);
            for (int i : inputs) parent.preview.preferences().put(entry.amountKey(i), changed.containsKey(i) ? Long.toString(changed.get(i)) : "");
            onClose();
        }
        @Override public void render(PoseStack pose, int mouseX, int mouseY, float partial) {
            renderBackground(pose);
            if (cell != null) {
                int left = compactLeft(), top = compactTop(), span = compactWidth();
                CompactUi.panel(pose, left, top, span, 83);
                font.draw(pose, font.plainSubstrByWidth(inputName(cell.slot()), span - 16), left + 8, top + 7, 0x404040);
                font.draw(pose, font.plainSubstrByWidth(error, span - 16), left + 8, top + 43, 0xAA3333);
                super.render(pose, mouseX, mouseY, partial);
                return;
            }
            int left = Math.max(8, (width - 314) / 2);
            CompactUi.panel(pose, left - 5, 6, Math.min(314, width - 6), height - 12);
            font.draw(pose, title, (width - font.width(title)) / 2f, 12, 0x404040);
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

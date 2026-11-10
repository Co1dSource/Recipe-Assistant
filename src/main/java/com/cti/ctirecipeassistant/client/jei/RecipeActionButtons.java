package com.cti.ctirecipeassistant.client.jei;

import com.cti.ctirecipeassistant.CtiRecipeAssistant;
import com.cti.ctirecipeassistant.client.gui.CraftQuantityScreen;
import com.cti.ctirecipeassistant.client.gui.RecipeTreeScreen;
import com.cti.ctirecipeassistant.client.operation.CraftExecutor;
import com.cti.ctirecipeassistant.client.operation.CraftingInventory;
import com.cti.ctirecipeassistant.client.operation.ClientRecipeOperation;
import com.cti.ctirecipeassistant.client.operation.RecipeOperationDispatcher;
import com.cti.ctirecipeassistant.integration.OptionalIntegrations;
import com.cti.ctirecipeassistant.integration.OptionalIntegrations.Supply;
import com.cti.ctirecipeassistant.mixin.RecipesGuiAccessor;
import com.cti.ctirecipeassistant.recipe.JeiRecipeIndex;
import com.cti.ctirecipeassistant.recipe.MaterialRequirements;
import com.cti.ctirecipeassistant.recipe.RecipeCandidate;
import com.cti.ctirecipeassistant.recipe.RecipeNode;
import com.cti.ctirecipeassistant.recipe.RecipePreferences;
import com.cti.ctirecipeassistant.recipe.TreeIngredient;
import com.mojang.blaze3d.vertex.PoseStack;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.common.Internal;
import mezz.jei.gui.elements.GuiIconButtonSmall;
import mezz.jei.gui.recipes.RecipesGui;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.List;

@Mod.EventBusSubscriber(modid = CtiRecipeAssistant.MOD_ID, value = Dist.CLIENT)
public final class RecipeActionButtons {
    private static final int SIZE = 13;
    // AE2 12.9.9's JEI transfer ErrorRenderer uses this ARGB with IRecipeSlotView.drawHighlight.
    private static final int AE2_JEI_INPUT_BLUE = 0x400000FF;
    private static final int JEI_INPUT_RED = 0x66FF0000;
    private static final int AE2_JEI_BUTTON_BLUE = 0x804545FF;
    private static final IDrawable GRID = new IDrawable() {
        @Override public int getWidth() { return 7; }
        @Override public int getHeight() { return 7; }
        @Override public void draw(PoseStack pose, int x, int y) {
            RenderSystem.setShaderColor(1, 1, 1, 1);
            for (int row = 0; row < 3; row++) for (int col = 0; col < 3; col++) {
                int px = x + col * 3, py = y + row * 3;
                Screen.fill(pose, px, py, px + 1, py + 1, 0xFFFFFFFF);
            }
        }
    };
    private static final Map<IRecipeLayoutDrawable<?>, CraftPreview> previews = new IdentityHashMap<>();
    private static long tick;
    private static final class CraftPreview {
        final Object target;
        final long revision = RecipePreferences.revision();
        final RecipeTreeScreen tree;
        final CraftExecutor.Session craft;
        List<TreeIngredient> inputs = List.of();
        List<TreeIngredient> slots = List.of();
        Map<Object, Supply> stock;
        private long checkedTick = -1;
        private long sourceTick = -1;
        private boolean covered;
        private int orders;
        private String problem;

        CraftPreview(Object target, RecipeTreeScreen tree, Screen parent) {
            this.target = target;
            this.tree = tree;
            craft = new CraftExecutor.Session(parent, tree.finalRoot(), tree.index());
        }

        String problem() {
            if (checkedTick != tick) {
                problem = craft.oneCraftProblem();
                checkedTick = tick;
            }
            return problem;
        }

        void readSources(IRecipeLayoutDrawable<?> layout, Screen parent) {
            if (sourceTick == tick) return;
            sourceTick = tick;
            if (!(parent instanceof AbstractContainerScreen<?> screen) || !OptionalIntegrations.refillAvailable(screen.getMenu())) {
                stock = null; covered = false; orders = 0;
                return;
            }
            var manager = TreeJeiPlugin.runtime.getIngredientManager();
            slots = layout.getRecipeSlotsView().getSlotViews(RecipeIngredientRole.INPUT).stream()
                    .map(slot -> slot.getDisplayedIngredient().map(value -> TreeIngredient.ofInput(value, manager)).orElse(null))
                    .toList();
            inputs = MaterialRequirements.inputsForBatches(slots.stream().filter(java.util.Objects::nonNull).toList(), 1);
            stock = OptionalIntegrations.supplies(screen.getMenu(), inputs);
            covered = OptionalIntegrations.sourcesCover(inputs, stock);
            orders = OptionalIntegrations.refillOrders(inputs, stock).size();
        }

        boolean canRefill() {
            return orders > 0 && !CraftExecutor.busy();
        }
    }
    private RecipeActionButtons() {}

    public static Rect2i buttonArea(IRecipeLayoutDrawable<?> layout) {
        var rect = layout.getRect();
        var original = layout.getRecipeTransferButtonArea();
        int x = original.getX();
        int y = rect.getY();
        if (x < original.getX() + original.getWidth() && x + SIZE > original.getX()
                && y < original.getY() + original.getHeight() && y + SIZE > original.getY())
            y = original.getY() - SIZE - 1;
        return new Rect2i(x, y, SIZE, SIZE);
    }

    public static boolean isMouseOver(Rect2i area, double x, double y) {
        // Rect2i.contains includes its lower edge; JEI's native Button does not.
        return x >= area.getX() && x < area.getX() + area.getWidth()
                && y >= area.getY() && y < area.getY() + area.getHeight();
    }

    private static GuiIconButtonSmall button(IRecipeLayoutDrawable<?> layout, boolean enabled) {
        var area = buttonArea(layout);
        var textures = Internal.getTextures();
        var button = new GuiIconButtonSmall(area.getX(), area.getY(), SIZE, SIZE,
                RecipeInteractions.choosing() ? textures.getRecipeTransfer() : GRID, ignored -> {}, textures);
        button.active = enabled;
        return button;
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) tick++;
    }

    @SubscribeEvent
    public static void closing(ScreenEvent.Closing event) {
        if (event.getScreen() instanceof RecipesGui) {
            previews.clear();
        }
    }

    @SubscribeEvent
    public static void render(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof RecipesGui gui) || TreeJeiPlugin.runtime == null) return;
        var access = (RecipesGuiAccessor) gui;
        var layouts = access.cti$getRecipeLayouts();
        previews.keySet().retainAll(layouts);
        var parent = access.cti$getParentScreen();
        var pose = event.getPoseStack();
        List<Component> tooltip = List.of();
        boolean control = Screen.hasControlDown();
        pose.pushPose();
        pose.translate(0, 0, 400);
        for (var layout : layouts) {
            var mode = RecipeInteractions.mode(parent, layout);
            if (mode == ClientRecipeOperation.Mode.HIDDEN) continue;
            var state = RecipeOperationDispatcher.buttonState(layout, mode, parent);
            CraftPreview craft = state.enabled() && normalCraft(mode) ? craftPreview(layout, mode, parent) : null;
            String problem = craft == null ? null : craft.problem();
            boolean onButton = isMouseOver(buttonArea(layout), event.getMouseX(), event.getMouseY());
            if (craft != null) craft.readSources(layout, parent);
            boolean covered = craft != null && craft.covered;
            boolean refill = craft != null && craft.canRefill();
            boolean auto = covered && !CraftExecutor.busy();
            boolean enabled = state.enabled() && (control && craft != null && onButton ? auto : problem == null || covered);
            button(layout, enabled).render(pose, event.getMouseX(), event.getMouseY(), event.getPartialTick());
            if (refill) {
                var area = buttonArea(layout);
                Screen.fill(pose, area.getX(), area.getY(), area.getX() + area.getWidth(),
                        area.getY() + area.getHeight(), AE2_JEI_BUTTON_BLUE);
            }
            if (onButton) {
                if (craft != null) drawInputHighlights(pose, layout, parent, craft);
                tooltip = new ArrayList<>(operationTooltip(mode, RecipeInteractions.choosing(), refill));
                if (auto) tooltip.add(Component.literal("Ctrl+右键：补料后合成当前配方").withStyle(ChatFormatting.BLUE));
                if (craft != null && !craft.tree.finalRoot().hasConfiguredProduction())
                    tooltip.add(Component.literal("当前物品未配置配方树").withStyle(ChatFormatting.RED));
                if (control && craft != null && craft.stock != null) {
                    if (!covered) tooltip.add(craftProblemTooltip("缺少材料：", true));
                } else if (problem != null) tooltip.add(covered
                        ? Component.literal(problem).withStyle(ChatFormatting.RED)
                        : craftProblemTooltip(problem, parent instanceof AbstractContainerScreen<?> screen
                        && OptionalIntegrations.crafting(screen.getMenu())));
                else if (state.reason() != null) tooltip.add(state.reason());
            }
        }
        pose.popPose();
        if (!tooltip.isEmpty()) gui.renderComponentTooltip(pose, tooltip, event.getMouseX(), event.getMouseY());
    }

    private static Component craftProblemTooltip(String problem, boolean ae2Crafting) {
        var message = problem.startsWith("缺少材料：")
                ? Component.translatable(ae2Crafting ? "ae2.rei_jei_integration.no_items" : "jei.tooltip.error.recipe.transfer.missing")
                : Component.literal(problem);
        return message.withStyle(ChatFormatting.RED);
    }

    private static boolean normalCraft(ClientRecipeOperation.Mode mode) {
        return mode == ClientRecipeOperation.Mode.ONE_KEY_CRAFT && !RecipeInteractions.choosing();
    }

    private static CraftPreview craftPreview(IRecipeLayoutDrawable<?> layout, ClientRecipeOperation.Mode mode, Screen parent) {
        var target = MaterialRequirements.identity(RecipeInteractions.target(layout).orElseThrow());
        var previous = previews.get(layout);
        if (previous != null && previous.target.equals(target) && previous.revision == RecipePreferences.revision())
            return previous;
        var operation = operation(layout, mode, parent);
        var tree = RecipeInteractions.actionTree(operation, layout);
        var current = new CraftPreview(target, tree, parent);
        previews.put(layout, current);
        return current;
    }

    private static int[] inputColors(List<TreeIngredient> slots, Map<Object, Supply> stock) {
        var remaining = new HashMap<Object, Long>();
        int[] colors = new int[slots.size()];
        for (int i = 0; i < slots.size(); i++) {
            var ingredient = slots.get(i);
            if (ingredient == null) continue;
            var identity = MaterialRequirements.identity(ingredient);
            var supply = stock.getOrDefault(identity, Supply.MISSING);
            long available = remaining.getOrDefault(identity, supply.stored());
            remaining.put(identity, Math.max(0, available - ingredient.amount()));
            // JEI draws whole slots. Standard 3×3 recipes use one item per slot.
            if (available == 0) colors[i] = supply.craftable() ? AE2_JEI_INPUT_BLUE : JEI_INPUT_RED;
        }
        return colors;
    }

    private static void drawInputHighlights(PoseStack pose, IRecipeLayoutDrawable<?> layout, Screen parent, CraftPreview craft) {
        var slots = layout.getRecipeSlotsView().getSlotViews(RecipeIngredientRole.INPUT);
        var area = layout.getRect();
        pose.pushPose();
        pose.translate(area.getX(), area.getY(), 0);
        if (craft.stock != null) {
            var colors = inputColors(craft.slots, craft.stock);
            for (int i = 0; i < colors.length; i++) if (colors[i] != 0) slots.get(i).drawHighlight(pose, colors[i]);
        } else if (parent instanceof AbstractContainerScreen<?> screen && !OptionalIntegrations.crafting(screen.getMenu())
                && CraftingInventory.supports(screen.getMenu())) {
            var inventory = CraftingInventory.of(screen.getMenu(), (CraftingRecipe) layout.getRecipe());
            for (var slot : inventory.missingInputs(slots, Minecraft.getInstance().player)) slot.drawHighlight(pose, JEI_INPUT_RED);
        }
        pose.popPose();
    }

    static List<Component> operationTooltip(ClientRecipeOperation.Mode mode, boolean choosing, boolean canRefill) {
        if (choosing) return List.of(Component.literal(RecipeInteractions.choosingEncoding() ? "用此配方编码" : "选择此生产配方"));
        if (mode == ClientRecipeOperation.Mode.BATCH_PATTERN_ENCODING) return List.of(mode.tooltip());
        var lines = new ArrayList<Component>();
        lines.add(mode.tooltip());
        lines.add(Component.literal("左键：数量选择并合成"));
        lines.add(Component.literal("右键：打开配方树"));
        if (canRefill) lines.add(Component.literal("Ctrl+左键：按份数请求 AE2 合成").withStyle(ChatFormatting.BLUE));
        return lines;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void click(ScreenEvent.MouseButtonPressed.Pre event) {
        if ((event.getButton() != 0 && event.getButton() != 1) || !(event.getScreen() instanceof RecipesGui gui)
                || TreeJeiPlugin.runtime == null) return;
        var access = (RecipesGuiAccessor) gui;
        var parent = access.cti$getParentScreen();
        for (var layout : access.cti$getRecipeLayouts()) {
            var mode = RecipeInteractions.mode(parent, layout);
            if (mode == ClientRecipeOperation.Mode.HIDDEN || !isMouseOver(buttonArea(layout), event.getMouseX(), event.getMouseY())) continue;
            event.setCanceled(true);
            if (!RecipeOperationDispatcher.buttonState(layout, mode, parent).enabled()) return;
            if (RecipeInteractions.choosing()) {
                if (event.getButton() == 0) RecipeInteractions.accept(layout);
                return;
            }
            boolean right = event.getButton() == 1;
            boolean control = Screen.hasControlDown();
            boolean refill = !right && control;
            if (mode == ClientRecipeOperation.Mode.BATCH_PATTERN_ENCODING) {
                if (!right && !refill) {
                    button(layout, true).playDownSound(Minecraft.getInstance().getSoundManager());
                    RecipeOperationDispatcher.dispatch(operation(layout, mode, parent));
                }
                return;
            }
            var craft = craftPreview(layout, mode, parent);
            if (control) craft.readSources(layout, parent);
            if (right && control && craft.stock != null) {
                if (craft.covered && !CraftExecutor.busy()) {
                    button(layout, true).playDownSound(Minecraft.getInstance().getSoundManager());
                    openAutomaticCraft(gui, layout, craft);
                }
                return;
            }
            if (refill && !craft.canRefill()
                    || !right && !refill && craft.craft.oneCraftProblem() != null) return;
            var root = craft.tree.finalRoot();
            if (root.selected != null && !root.hasConfiguredProduction()) {
                root.select(root.selected);
                craft.tree.refresh();
            }
            button(layout, true).playDownSound(Minecraft.getInstance().getSoundManager());
            if (right) RecipeInteractions.openActionTree(operation(layout, mode, parent), gui, craft.tree);
            else Minecraft.getInstance().setScreen(refill ? CraftQuantityScreen.pattern(gui, craft.inputs)
                    : new CraftQuantityScreen(gui, craft.tree, false));
            return;
        }
    }

    private static void openAutomaticCraft(RecipesGui gui, IRecipeLayoutDrawable<?> layout, CraftPreview craft) {
        var target = RecipeInteractions.target(layout).orElseThrow();
        var index = new JeiRecipeIndex(TreeJeiPlugin.runtime, true);
        var recipe = index.readLayout(layout, target);
        var slots = craft.slots.stream().map(input -> input == null ? List.<TreeIngredient>of() : List.of(input)).toList();
        var root = new RecipeNode(target, null, index, craft.tree.preferences());
        root.useRecipe(new RecipeCandidate(recipe.id(), recipe.category(), recipe.machine(), slots, recipe.outputs(), recipe.catalysts()));
        for (var child : root.children) {
            child.loaded = true;
            child.externalSupply = true;
        }
        Minecraft.getInstance().setScreen(CraftQuantityScreen.pattern(gui, craft.inputs, (screen, batches) -> {
            var session = new CraftExecutor.Session(screen, root, index);
            if (!session.start(MaterialRequirements.rootOutput(root) * batches)) {
                String problem = session.problem();
                Minecraft.getInstance().player.displayClientMessage(Component.literal(
                        problem == null ? "当前终端无法完成所选份数" : problem), false);
            }
        }));
    }

    private static <R> ClientRecipeOperation<R> operation(IRecipeLayoutDrawable<R> layout, ClientRecipeOperation.Mode mode,
                                                         Screen parent) {
        var target = RecipeInteractions.target(layout).orElseThrow();
        return new ClientRecipeOperation<>(mode, layout.getRecipe(), layout.getRecipeCategory(), layout.getRecipeSlotsView(),
                layout.getRect(), new JeiRecipeIndex(TreeJeiPlugin.runtime).readLayout(layout, target), target, parent);
    }
}

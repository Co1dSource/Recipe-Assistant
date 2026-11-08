package com.cti.ctirecipeassistant.client.jei;

import com.cti.ctirecipeassistant.CtiRecipeAssistant;
import com.cti.ctirecipeassistant.client.gui.CraftQuantityScreen;
import com.cti.ctirecipeassistant.client.gui.RecipeTreeScreen;
import com.cti.ctirecipeassistant.client.operation.CraftExecutor;
import com.cti.ctirecipeassistant.client.operation.CraftingInventory;
import com.cti.ctirecipeassistant.client.operation.ClientRecipeOperation;
import com.cti.ctirecipeassistant.client.operation.RecipeOperationDispatcher;
import com.cti.ctirecipeassistant.client.operation.SelectedRecipeSlots;
import com.cti.ctirecipeassistant.integration.OptionalIntegrations;
import com.cti.ctirecipeassistant.mixin.RecipesGuiAccessor;
import com.cti.ctirecipeassistant.recipe.JeiRecipeIndex;
import com.cti.ctirecipeassistant.recipe.RecipePreferences;
import com.mojang.blaze3d.vertex.PoseStack;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
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
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.List;

@Mod.EventBusSubscriber(modid = CtiRecipeAssistant.MOD_ID, value = Dist.CLIENT)
public final class RecipeActionButtons {
    private static final int SIZE = 13;
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
        final String target;
        final String sourceRecipe;
        final long revision = RecipePreferences.revision();
        final RecipeTreeScreen tree;
        final CraftExecutor.Session craft;
        private long checkedTick = -1;
        private String problem;

        CraftPreview(String target, String sourceRecipe, RecipeTreeScreen tree, Screen parent) {
            this.target = target;
            this.sourceRecipe = sourceRecipe;
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
    }
    private record MissingPreview(IRecipeLayoutDrawable<?> layout, Screen parent, long tick, List<IRecipeSlotView> slots) {}
    private static MissingPreview missingPreview;
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
            missingPreview = null;
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
        pose.pushPose();
        pose.translate(0, 0, 400);
        for (var layout : layouts) {
            var mode = RecipeInteractions.mode(parent, layout);
            if (mode == ClientRecipeOperation.Mode.HIDDEN) continue;
            var state = RecipeOperationDispatcher.buttonState(layout, mode, parent);
            CraftPreview craft = state.enabled() && normalCraft(mode) ? craftPreview(layout, mode, parent) : null;
            String problem = craft == null ? null : craft.problem();
            button(layout, state.enabled() && problem == null).render(pose, event.getMouseX(), event.getMouseY(), event.getPartialTick());
            if (isMouseOver(buttonArea(layout), event.getMouseX(), event.getMouseY())) {
                if (normalCraft(mode)) drawMissingInputs(pose, layout, parent, craft);
                boolean refill = craft != null && canRefill(craft.tree, parent);
                tooltip = new ArrayList<>(operationTooltip(mode, RecipeInteractions.choosing(), refill));
                if (craft != null && !craft.tree.finalRoot().hasConfiguredProduction())
                    tooltip.add(Component.literal("当前物品未配置配方树"));
                if (problem != null) tooltip.add(craftProblemTooltip(problem,
                        parent instanceof AbstractContainerScreen<?> screen && OptionalIntegrations.crafting(screen.getMenu())));
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
        String target = RecipeInteractions.target(layout).orElseThrow().key();
        var previous = previews.get(layout);
        if (previous != null && previous.target.equals(target) && previous.revision == RecipePreferences.revision())
            return previous;
        var operation = operation(layout, mode, parent);
        var current = new CraftPreview(target, operation.candidate().id(), RecipeInteractions.actionTree(operation, layout), parent);
        previews.put(layout, current);
        return current;
    }

    private static boolean canRefill(RecipeTreeScreen tree, Screen parent) {
        if (tree.finalRoot().selected == null || !(parent instanceof AbstractContainerScreen<?> screen)
                || !OptionalIntegrations.refillAvailable(screen.getMenu()))
            return false;
        var requirements = tree.requirements();
        var materials = requirements.materials();
        return requirements.problem() == null && !OptionalIntegrations.refillOrders(materials,
                OptionalIntegrations.supplies(screen.getMenu(), materials)).isEmpty();
    }

    private static void drawMissingInputs(PoseStack pose, IRecipeLayoutDrawable<?> layout, Screen parent, CraftPreview craft) {
        if (missingPreview == null || missingPreview.layout() != layout || missingPreview.parent() != parent
                || missingPreview.tick() != tick) {
            var menu = ((AbstractContainerScreen<?>) parent).getMenu();
            if (!CraftingInventory.supports(menu)) return;
            var inventory = CraftingInventory.of(menu, (CraftingRecipe) layout.getRecipe());
            var slots = layout.getRecipeSlotsView();
            var inputs = slots.getSlotViews(RecipeIngredientRole.INPUT);
            if (craft != null) {
                var root = craft.tree.finalRoot();
                if (root.selected != null && root.selected.id().equals(craft.sourceRecipe)
                        && root.selected.inputs().size() == inputs.size())
                    inputs = new SelectedRecipeSlots(slots, root).getSlotViews(RecipeIngredientRole.INPUT);
            }
            missingPreview = new MissingPreview(layout, parent, tick,
                    inventory.missingInputs(inputs, Minecraft.getInstance().player));
        }
        if (missingPreview.slots().isEmpty()) return;
        var area = layout.getRect();
        pose.pushPose();
        pose.translate(area.getX(), area.getY(), 0);
        for (var slot : missingPreview.slots()) slot.drawHighlight(pose, 0x66FF0000);
        pose.popPose();
    }

    static List<Component> operationTooltip(ClientRecipeOperation.Mode mode, boolean choosing, boolean canRefill) {
        if (choosing) return List.of(Component.literal(RecipeInteractions.choosingEncoding() ? "用此配方编码" : "选择此生产配方"));
        if (mode == ClientRecipeOperation.Mode.BATCH_PATTERN_ENCODING) return List.of(mode.tooltip());
        var lines = new ArrayList<Component>();
        lines.add(mode.tooltip());
        lines.add(Component.literal("左键：数量选择并合成"));
        lines.add(Component.literal("右键：打开配方树"));
        if (canRefill) lines.add(Component.translatable("ae2.rei_jei_integration.ctrl_click_to_craft").withStyle(ChatFormatting.BLUE));
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
            boolean refill = !right && Screen.hasControlDown();
            if (mode == ClientRecipeOperation.Mode.BATCH_PATTERN_ENCODING) {
                if (!right && !refill) {
                    button(layout, true).playDownSound(Minecraft.getInstance().getSoundManager());
                    RecipeOperationDispatcher.dispatch(operation(layout, mode, parent));
                }
                return;
            }
            var craft = craftPreview(layout, mode, parent);
            if (refill && !canRefill(craft.tree, parent)
                    || !right && !refill && craft.craft.oneCraftProblem() != null) return;
            button(layout, true).playDownSound(Minecraft.getInstance().getSoundManager());
            if (right) RecipeInteractions.openActionTree(operation(layout, mode, parent), gui, craft.tree);
            else Minecraft.getInstance().setScreen(new CraftQuantityScreen(gui, craft.tree, refill));
            return;
        }
    }

    private static <R> ClientRecipeOperation<R> operation(IRecipeLayoutDrawable<R> layout, ClientRecipeOperation.Mode mode,
                                                         Screen parent) {
        var target = RecipeInteractions.target(layout).orElseThrow();
        return new ClientRecipeOperation<>(mode, layout.getRecipe(), layout.getRecipeCategory(), layout.getRecipeSlotsView(),
                layout.getRect(), new JeiRecipeIndex(TreeJeiPlugin.runtime).readLayout(layout, target), target, parent);
    }
}

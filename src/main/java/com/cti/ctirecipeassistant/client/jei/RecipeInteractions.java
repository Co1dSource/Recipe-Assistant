package com.cti.ctirecipeassistant.client.jei;

import com.cti.ctirecipeassistant.integration.PatternConfirmation;
import com.cti.ctirecipeassistant.integration.OptionalIntegrations;
import com.cti.ctirecipeassistant.client.gui.*;
import com.cti.ctirecipeassistant.client.operation.*;
import com.cti.ctirecipeassistant.mixin.RecipesGuiAccessor;
import com.cti.ctirecipeassistant.mixin.RecipeGuiLogicAccessor;
import mezz.jei.gui.ingredients.IngredientLookupState;
import com.cti.ctirecipeassistant.recipe.*;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.*;
import mezz.jei.gui.recipes.RecipesGui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import com.cti.ctirecipeassistant.CtiRecipeAssistant;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.*;
import net.minecraftforge.fml.common.Mod;
import java.util.*;

/** Local navigation only. A pending selection never reaches an item-operation dispatcher. */
@Mod.EventBusSubscriber(modid = CtiRecipeAssistant.MOD_ID, value = Dist.CLIENT)
public final class RecipeInteractions {
    public record Selection(RecipeNode node, RecipeTreeScreen tree, Screen returnTo, Runnable refresh) {}
    public record JeiContext(RecipesGui screen, Screen parent, IngredientLookupState state,
                             List<ITypedIngredient<?>> focuses, Map<Object, String> targets) {}
    public static JeiContext captureJei(RecipesGui gui) {
        var access = (RecipesGuiAccessor) gui;
        return new JeiContext(gui, access.cti$getParentScreen(),
                ((RecipeGuiLogicAccessor) access.cti$getLogic()).cti$getState(),
                outputFocus, new IdentityHashMap<>(outputs));
    }
    public static void restoreJei(JeiContext context) {
        var access = (RecipesGuiAccessor) context.screen();
        Minecraft.getInstance().setScreen(context.parent());
        ((RecipeGuiLogicAccessor) access.cti$getLogic()).cti$restoreState(context.state(), false);
        outputFocus = context.focuses(); outputs.clear(); outputs.putAll(context.targets());
        access.cti$open();
    }
    private static Selection selection;
    private static RecipeTreeScreen last;
    private static Object world;
    private static PatternConfirmation confirmation;
    private static List<ITypedIngredient<?>> outputFocus = List.of();
    private static final Map<Object, String> outputs = new IdentityHashMap<>();
    private RecipeInteractions() {}

    public static void clear() { selection = null; last = null; confirmation = null; world = null; outputs.clear(); outputFocus = List.of(); }
    private static void world() {
        var current = Minecraft.getInstance().level;
        if (world != current) { clear(); world = current; }
    }
    public static boolean choosing() { return selection != null; }
    public static Selection selection() { return selection; }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void opening(ScreenEvent.Opening event) {
        if (event.getNewScreen() instanceof RecipeTreeScreen) confirmation = null;
    }
    @SubscribeEvent
    public static void closing(ScreenEvent.Closing event) {
        if (event.getScreen() instanceof RecipesGui) selection = null;
    }
    public static void recordFocus(List<IFocus<?>> focuses) {
        world(); outputs.clear();
        outputFocus = focuses.stream().filter(f -> f.getRole() == RecipeIngredientRole.OUTPUT)
                .<ITypedIngredient<?>>map(f -> f.getTypedValue()).toList();
    }
    public static void clearFocus() { outputFocus = List.of(); outputs.clear(); }

    public static List<TreeIngredient> outputs(IRecipeLayoutDrawable<?> layout) {
        var unique = new LinkedHashMap<String, TreeIngredient>();
        layout.getRecipeSlotsView().getSlotViews(RecipeIngredientRole.OUTPUT).stream()
                .flatMap(slot -> slot.getAllIngredients()).map(RecipeInteractions::ingredient)
                .forEach(target -> unique.putIfAbsent(target.key(), target));
        return List.copyOf(unique.values());
    }
    private static TreeIngredient ingredient(ITypedIngredient<?> typed) {
        return TreeIngredient.of(typed, TreeJeiPlugin.runtime.getIngredientManager());
    }
    public static Optional<TreeIngredient> target(IRecipeLayoutDrawable<?> layout, double x, double y) {
        var candidates = outputs(layout);
        if (selection != null) return candidates.stream().filter(t -> t.key().equals(selection.node.target.key())).findFirst();
        var hovered = layout.getRecipeSlotUnderMouse(x, y).filter(s -> s.getRole() == RecipeIngredientRole.OUTPUT)
                .flatMap(s -> s.getDisplayedIngredient()).map(RecipeInteractions::ingredient);
        if (hovered.isPresent()) { outputs.put(layout.getRecipe(), hovered.get().key()); return hovered; }
        String remembered = outputs.get(layout.getRecipe());
        var saved = candidates.stream().filter(t -> t.key().equals(remembered)).findFirst();
        if (saved.isPresent()) return saved;
        var focused = outputFocus.stream().map(RecipeInteractions::ingredient)
                .flatMap(f -> candidates.stream().filter(t -> t.key().equals(f.key()))).distinct().toList();
        if (focused.size() == 1) { outputs.put(layout.getRecipe(), focused.get(0).key()); return Optional.of(focused.get(0)); }
        return candidates.size() == 1 ? Optional.of(candidates.get(0)) : Optional.empty();
    }
    public static Optional<TreeIngredient> target(IRecipeLayoutDrawable<?> layout) {
        var mc = Minecraft.getInstance(); var window = mc.getWindow();
        return target(layout, mc.mouseHandler.xpos() * window.getGuiScaledWidth() / window.getScreenWidth(),
                mc.mouseHandler.ypos() * window.getGuiScaledHeight() / window.getScreenHeight());
    }
    public static Component tooltip(ClientRecipeOperation.Mode mode) {
        return choosing() ? Component.literal("选择此生产配方") : mode.tooltip();
    }
    public static ClientRecipeOperation.Mode mode(Screen parent, IRecipeLayoutDrawable<?> layout) {
        return choosing() ? ClientRecipeOperation.Mode.ONE_KEY_CRAFT : ClientRecipeOperation.Mode.fromParent(parent, layout);
    }

    public static void choose(RecipeNode node, RecipeTreeScreen tree, Screen returnTo, Runnable refresh) {
        world(); confirmation = null;
        Minecraft.getInstance().setScreen(returnTo);
        selection = new Selection(node, tree, returnTo, refresh);
        var factory = TreeJeiPlugin.runtime.getJeiHelpers().getFocusFactory();
        TreeJeiPlugin.runtime.getRecipesGui().show(factory.createFocus(RecipeIngredientRole.OUTPUT, node.target.typed()));
        if (!(Minecraft.getInstance().screen instanceof RecipesGui)) {
            selection = null;
            Minecraft.getInstance().gui.getChat().addMessage(Component.literal("JEI 中没有可用的生产配方：" + node.target.name()));
        }
    }
    public static boolean accept(ClientRecipeOperation<?> operation) {
        if (selection == null) return false;
        var layout = layout(operation);
        if (layout.isPresent()) accept(layout.get());
        return true;
    }
    public static void accept(IRecipeLayoutDrawable<?> layout) {
        if (selection == null) return;
        var pending = selection;
        boolean matching = outputs(layout).stream().anyMatch(t -> t.key().equals(pending.node.target.key()));
        if (!matching || CraftExecutor.busy() || OptionalIntegrations.busy()) return;
        var candidate = pending.tree.index().readLayout(layout);
        pending.node.select(candidate);
        selection = null;
        pending.refresh.run();
        Minecraft.getInstance().setScreen(pending.returnTo);
    }
    private static <R> Optional<IRecipeLayoutDrawable<R>> layout(ClientRecipeOperation<R> operation) {
        return TreeJeiPlugin.runtime.getRecipeManager().createRecipeLayoutDrawable(operation.category(), operation.recipe(),
                TreeJeiPlugin.runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup());
    }
    public static boolean cancelSelection() {
        if (selection == null) return false;
        var parent = selection.returnTo; selection = null; Minecraft.getInstance().setScreen(parent); return true;
    }
    public static <R> boolean openLayout(IRecipeLayoutDrawable<R> layout, Screen previous, double x, double y) {
        world();
        var target = target(layout, x, y);
        if (target.isEmpty()) {
            Minecraft.getInstance().gui.getChat().addMessage(Component.literal("请将鼠标移到需要的目标产物后按 G")); return true;
        }
        last = new RecipeTreeScreen(target.get(), TreeJeiPlugin.runtime, previous);
        var candidate = last.index().readLayout(layout);
        last.finalRoot().useRecipe(candidate); last.finalRoot().expanded = true; last.refresh();
        confirmation = null; Minecraft.getInstance().setScreen(last); return true;
    }
    public static void openItem(TreeIngredient target, Screen previous) {
        world(); last = new RecipeTreeScreen(target, TreeJeiPlugin.runtime, previous);
        confirmation = null; Minecraft.getInstance().setScreen(last);
    }
    public static boolean restore(Screen previous) {
        world();
        if (last == null) return false;
        last.returnTo(previous); Minecraft.getInstance().setScreen(last); return true;
    }
    public static boolean key(Screen screen, double x, double y) {
        world();
        if (selection != null) return true; // G never confirms or replaces a pending node.
        if (screen instanceof PatternConfirmation preview) { preview.locateProblem(); return true; }
        if (screen instanceof RecipeTreeScreen) return true;
        if (screen instanceof RecipesGui gui) for (var layout : ((RecipesGuiAccessor) gui).cti$getRecipeLayouts())
            if (layout.isMouseOver(x, y) || RecipeActionButtons.isMouseOver(RecipeActionButtons.buttonArea(layout), x, y))
                return openLayout(layout, screen, x, y);
        return false;
    }
    public static RecipeTreeScreen tree(ClientRecipeOperation<?> operation) {
        world();
        if (last == null || last.finalRoot().selected == null
                || !last.finalRoot().selected.id().equals(operation.candidate().id())
                || !last.finalRoot().target.key().equals(operation.target().key())) {
            // Encoding previews are transient. Only explicitly opened trees enter G history.
            var tree = new RecipeTreeScreen(operation.target(), TreeJeiPlugin.runtime, operation.parentScreen());
            layout(operation).ifPresent(l -> tree.index().readLayout(l));
            tree.finalRoot().useRecipe(operation.candidate()); tree.finalRoot().expanded = true; tree.refresh();
            return tree;
        }
        return last;
    }
    /** Carry local cuts into this tree's crafting view; never change another independent tree. */
    public static void applyCurrentCuts(RecipeNode root) {
        world();
        if (last != null && last.finalRoot().target.key().equals(root.target.key()))
            copyCuts(last.finalRoot(), root);
    }
    private static void copyCuts(RecipeNode original, RecipeNode target) {
        if (original.externalSupply) { target.removeProduction(); return; }
        target.load();
        if (original.selected == null || target.selected == null
                || !original.selected.id().equals(target.selected.id())) return;
        for (int i = 0; i < Math.min(original.children.size(), target.children.size()); i++) {
            var sourceChild = original.children.get(i); var targetChild = target.children.get(i);
            if (sourceChild.target.key().equals(targetChild.target.key())) copyCuts(sourceChild, targetChild);
        }
    }
    public static void arm(PatternConfirmation preview) {
        confirmation = preview;
        restoreJei(preview.jeiContext());
        outputs.put(preview.sourceRecipe(), preview.target().key());
    }
    public static boolean confirmEncoding(ClientRecipeOperation<?> operation) {
        if (confirmation == null || !confirmation.matches(operation)) return false;
        var preview = confirmation; confirmation = null;
        preview.startFromJei(); return true;
    }
}

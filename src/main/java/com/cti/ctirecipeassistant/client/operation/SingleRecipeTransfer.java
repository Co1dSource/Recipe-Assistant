package com.cti.ctirecipeassistant.client.operation;

import com.cti.ctirecipeassistant.CtiRecipeAssistant;
import com.cti.ctirecipeassistant.client.jei.TreeJeiPlugin;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** One JEI transfer, followed only by read-only observation of the server's grid. */
@Mod.EventBusSubscriber(modid = CtiRecipeAssistant.MOD_ID, value = Dist.CLIENT)
public final class SingleRecipeTransfer {
    private static Pending pending;
    private SingleRecipeTransfer() {}

    private record Checked<R>(CraftingMenu menu, IRecipeTransferHandler<CraftingMenu, R> handler,
                              RecipeOperationDispatcher.Availability availability) {}

    public static <R> RecipeOperationDispatcher.Availability check(R recipe, IRecipeCategory<R> category,
                                                                  IRecipeSlotsView slots, Screen parent) {
        return prepare(recipe, category, slots, parent).availability();
    }

    private static <R> Checked<R> prepare(R recipe, IRecipeCategory<R> category,
                                          IRecipeSlotsView slots, Screen parent) {
        var mc = Minecraft.getInstance();
        if (pending != null) return blocked("正在等待工作台同步");
        if (!(parent instanceof AbstractContainerScreen<?> screen)
                || mc.player == null || mc.player.containerMenu != screen.getMenu())
            return blocked("需要打开 3×3 工作台");
        if (screen.getMenu().getClass() != CraftingMenu.class)
            return blocked("当前阶段仅支持原版工作台单步填入");
        if (!RecipeTypes.CRAFTING.equals(category.getRecipeType()) || !(recipe instanceof CraftingRecipe))
            return blocked("当前配方不支持一键合成");
        if (TreeJeiPlugin.runtime == null) return blocked("JEI 尚未就绪");
        var menu = (CraftingMenu) screen.getMenu();
        var handler = TreeJeiPlugin.runtime.getRecipeTransferManager().getRecipeTransferHandler(menu, category);
        if (handler.isEmpty() || handler.get().getContainerClass() != CraftingMenu.class
                || !RecipeTypes.CRAFTING.equals(handler.get().getRecipeType()))
            return blocked("当前配方没有对应的工作台转移处理器");
        var error = handler.get().transferRecipe(menu, recipe, slots, mc.player, false, false);
        return new Checked<>(menu, handler.get(), availability(error));
    }

    private static <R> Checked<R> blocked(String reason) {
        return new Checked<>(null, null, new RecipeOperationDispatcher.Availability(
                RecipeOperationDispatcher.ButtonState.DISABLED_REASON, Component.literal(reason)));
    }

    private static RecipeOperationDispatcher.Availability availability(IRecipeTransferError error) {
        if (error == null || error.getType().allowsTransfer)
            return new RecipeOperationDispatcher.Availability(RecipeOperationDispatcher.ButtonState.ENABLED, null, error);
        return new RecipeOperationDispatcher.Availability(RecipeOperationDispatcher.ButtonState.DISABLED_REASON,
                Component.literal("JEI 无法填入当前配方"), error);
    }

    public static <R> void execute(ClientRecipeOperation<R> operation) {
        var checked = prepare(operation.recipe(), operation.category(), operation.slotsView(), operation.parentScreen());
        if (!checked.availability().enabled()) return;
        var menu = checked.menu();
        var recipe = (CraftingRecipe) operation.recipe();
        if (menu.recipeMatches(recipe)) {
            message("工作台已符合当前配方，未执行新的填入");
            return;
        }
        var before = snapshot(menu);
        int stateId = menu.getStateId();
        var error = checked.handler().transferRecipe(menu, operation.recipe(), operation.slotsView(),
                Minecraft.getInstance().player, false, true);
        if (error != null && !error.getType().allowsTransfer) {
            message("JEI 未执行填入，请悬停按钮查看原因");
            return;
        }
        pending = new Pending(menu, recipe, stateId, before);
    }

    public static void clear() { pending = null; }
    public static boolean busy() { return pending != null; }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || pending == null) return;
        var mc = Minecraft.getInstance();
        var current = pending;
        if (mc.player == null || mc.player.containerMenu != current.menu || TreeJeiPlugin.runtime == null) {
            clear();
            if (mc.player != null) message("配方填入已停止：工作台会话已失效");
            return;
        }
        var grid = snapshot(current.menu);
        boolean synchronizedGrid = current.menu.getStateId() != current.stateId
                && !sameGrid(grid, current.before) && current.menu.recipeMatches(current.recipe);
        // A matching grid must remain unchanged across two client ticks, not an intermediate slot update.
        if (synchronizedGrid && sameGrid(grid, current.lastGrid)) {
            clear();
            message("配方已填入，工作台九宫格已同步");
        } else if (++current.ticks >= 100) {
            clear();
            message("配方填入未确认：等待工作台同步超时");
        } else {
            current.lastGrid = grid;
        }
    }

    private static ItemStack[] snapshot(CraftingMenu menu) {
        var grid = new ItemStack[9];
        for (int i = 0; i < 9; i++) grid[i] = menu.getSlot(i + 1).getItem().copy();
        return grid;
    }

    private static boolean sameGrid(ItemStack[] first, ItemStack[] second) {
        for (int i = 0; i < 9; i++) if (!ItemStack.matches(first[i], second[i])) return false;
        return true;
    }

    private static void message(String text) {
        Minecraft.getInstance().gui.getChat().addMessage(Component.literal(text));
    }

    private static final class Pending {
        final CraftingMenu menu;
        final CraftingRecipe recipe;
        final int stateId;
        final ItemStack[] before;
        ItemStack[] lastGrid;
        int ticks;

        Pending(CraftingMenu menu, CraftingRecipe recipe, int stateId, ItemStack[] before) {
            this.menu = menu;
            this.recipe = recipe;
            this.stateId = stateId;
            this.before = before;
            this.lastGrid = before;
        }
    }
}

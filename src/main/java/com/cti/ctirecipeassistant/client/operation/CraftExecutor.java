package com.cti.ctirecipeassistant.client.operation;

import com.cti.ctirecipeassistant.integration.OptionalIntegrations;
import com.cti.ctirecipeassistant.CtiRecipeAssistant;
import com.cti.ctirecipeassistant.client.jei.TreeJeiPlugin;
import com.cti.ctirecipeassistant.recipe.*;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;
import java.util.*;

@Mod.EventBusSubscriber(modid = CtiRecipeAssistant.MOD_ID, value = Dist.CLIENT)
public final class CraftExecutor {
    public enum State { IDLE, PLANNING, TRANSFERRING, CRAFTING, WAITING_SYNC, COMPLETED, FAILED, CANCELLED }
    private enum Await { TRANSFER, PICKUP, DEPOSIT }
    private record Prepared(AbstractContainerMenu menu, CraftingInventory inventory, List<RecipeNode> steps, JeiRecipeIndex index,
                            String target, String reason) {}
    private static State state = State.IDLE;
    private static Prepared task;
    private static int completed, stateId, ticks, destination;
    private static Await awaiting;
    private static boolean cancel;
    private static ItemStack expected = ItemStack.EMPTY, beforeCarried = ItemStack.EMPTY, beforeDestination = ItemStack.EMPTY;
    private static ItemStack[] beforeGrid;
    private static final Map<IRecipeLayoutDrawable<?>, Prepared> previews = new IdentityHashMap<>();
    private static AbstractContainerMenu cachedMenu;
    private static Screen cachedScreen;
    private static ItemStack[] cachedInventory;
    private static Prepared cached;
    private static long cachedRevision;
    private static long clientTick, networkTick = -1;
    private static Map<ItemIdentity, Long> networkAtTick = Map.of(), cachedNetwork = Map.of();

    private CraftExecutor() {}
    public static boolean busy() { return task != null; }
    public static State state() { return state; }
    public static void cancel() { if (busy()) cancel = true; }

    public static RecipeOperationDispatcher.Availability check(IRecipeLayoutDrawable<?> layout, Screen parent) {
        if (busy() || OptionalIntegrations.busy() || SingleRecipeTransfer.busy()) return blocked("正在执行一键合成任务");
        var mc = Minecraft.getInstance();
        if (!(parent instanceof AbstractContainerScreen<?> screen) || mc.player == null
                || mc.player.containerMenu != screen.getMenu()) return blocked("需要打开有效的 3×3 工作台");
        if (!RecipeTypes.CRAFTING.equals(layout.getRecipeCategory().getRecipeType())
                || !(layout.getRecipe() instanceof CraftingRecipe recipe) || !recipe.canCraftInDimensions(3, 3))
            return blocked("一键合成只支持 3×3 工作台配方");
        var menu = screen.getMenu();
        if (!CraftingInventory.supports(menu)) return blocked("当前菜单尚未支持逐层合成");
        if (!menu.getCarried().isEmpty()) return blocked("请先放下鼠标光标携带的物品");
        String unavailable = OptionalIntegrations.unavailable(menu);
        if (unavailable != null) return blocked(unavailable);
        var source = CraftingInventory.of(menu, recipe);
        if (networkTick != clientTick || cachedMenu != menu) {
            networkAtTick = source.networkStock(); networkTick = clientTick;
        }
        var inventory = snapshot(menu);
        if (cachedMenu != menu || cachedScreen != mc.screen
                || cachedRevision != RecipePreferences.revision() || !same(inventory, cachedInventory)
                || !cachedNetwork.equals(networkAtTick)) {
            previews.clear(); cachedMenu = menu; cachedScreen = mc.screen; cachedInventory = inventory;
            cachedRevision = RecipePreferences.revision(); cachedNetwork = networkAtTick;
        }
        cached = previews.computeIfAbsent(layout, key -> prepare(key, source, networkAtTick));
        cachedRevision = RecipePreferences.revision();
        return cached.reason == null ? new RecipeOperationDispatcher.Availability(
                RecipeOperationDispatcher.ButtonState.ENABLED, null) : blocked(cached.reason);
    }

    private static RecipeOperationDispatcher.Availability blocked(String reason) {
        return new RecipeOperationDispatcher.Availability(RecipeOperationDispatcher.ButtonState.DISABLED_REASON,
                Component.literal(reason));
    }

    private static Prepared prepare(IRecipeLayoutDrawable<?> layout, CraftingInventory source, Map<ItemIdentity, Long> network) {
        var menu = source.menu();
        var index = new JeiRecipeIndex(TreeJeiPlugin.runtime, true);
        var candidate = index.readLayout(layout);
        var output = layout.getRecipeSlotsView().getSlotViews(RecipeIngredientRole.OUTPUT).stream()
                .flatMap(slot -> slot.getDisplayedIngredient().stream()).findFirst();
        if (output.isEmpty()) return new Prepared(menu, source, List.of(), index, "", "配方未提供目标产物");
        var target = TreeIngredient.of(output.get(), TreeJeiPlugin.runtime.getIngredientManager());
        var root = new RecipeNode(target, null, index, new RecipePreferences());
        root.useRecipe(candidate);
        com.cti.ctirecipeassistant.client.jei.RecipeInteractions.applyCurrentCuts(root);
        var plan = CraftPlanner.plan(root, ingredient -> source.available(ingredient, network));
        if (plan.reason() != null) return new Prepared(menu, source, List.of(), index, target.name(), plan.reason());
        String reason = validateLayouts(root, index, menu);
        if (reason == null) reason = checkSpace(source, plan.steps(), network);
        if (reason == null) reason = preview(index.layout(plan.steps().get(0).selected.id()), menu, plan.steps().get(0));
        return new Prepared(menu, source, plan.steps(), index, target.name(), reason);
    }

    private static String validateLayouts(RecipeNode node, JeiRecipeIndex index, AbstractContainerMenu menu) {
        if (node.selected == null) return null;
        var layout = index.layout(node.selected.id());
        if (layout == null || !RecipeTypes.CRAFTING.equals(layout.getRecipeCategory().getRecipeType())
                || !(layout.getRecipe() instanceof CraftingRecipe recipe) || !recipe.canCraftInDimensions(3, 3)
                || node.selected.inputs().size() > 9)
            return node.target.name() + "：不是可执行的 3×3 工作台配方";
        var transfer = handler(layout, menu);
        if (transfer.isEmpty()) return node.target.name() + "：当前菜单没有 JEI 转移处理器";
        boolean supportedHandler = OptionalIntegrations.crafting(menu)
                ? OptionalIntegrations.supportedHandler(transfer.get())
                : transfer.get().getClass() == mezz.jei.library.transfer.BasicRecipeTransferHandler.class;
        if (!supportedHandler || !transfer.get().getContainerClass().isInstance(menu)
                || !RecipeTypes.CRAFTING.equals(transfer.get().getRecipeType()))
            return node.target.name() + "：此处理器的材料来源尚未确认";
        if (!(node.selected.output(node.target.key()).typed().getIngredient() instanceof ItemStack))
            return node.target.name() + "：工作台目标不是物品";
        for (var child : node.children) {
            if (!(child.target.typed().getIngredient() instanceof ItemStack) || child.target.amount() != 1)
                return child.target.name() + "：不是普通工作台单槽物品输入";
            String reason = validateLayouts(child, index, menu);
            if (reason != null) return reason;
        }
        return null;
    }

    /** Pack only private copies. This is space accounting, never a recipe input matcher. */
    private static String checkSpace(CraftingInventory source, List<RecipeNode> steps, Map<ItemIdentity, Long> network) {
        var inventory = new ArrayList<ItemStack>();
        for (var slot : source.playerSlots()) inventory.add(slot.getItem().copy());
        for (var slot : source.grid()) if (!insert(inventory, slot.getItem()))
            return "物品栏没有安全空间接收工作台材料";
        var external = source.externalStock(network);
        for (var node : steps) {
            for (var child : node.children) {
                var input = (ItemStack) child.target.typed().getIngredient();
                int left = (int) child.target.amount();
                var key = ItemIdentity.of(input);
                long amount = external.getOrDefault(key, 0L);
                int takenExternal = (int) Math.min(left, amount);
                external.put(key, amount - takenExternal); left -= takenExternal;
                for (var stack : inventory) if (ItemStack.isSameItemSameTags(stack, input)) {
                    int taken = Math.min(left, stack.getCount()); stack.shrink(taken); left -= taken;
                }
                if (left != 0) return child.target.name() + "：选定物品或 NBT 数量不足";
                if (input.hasCraftingRemainingItem() && !insert(inventory, input.getCraftingRemainingItem()))
                    return "物品栏没有安全空间接收容器返还物";
            }
            if (!insert(inventory, (ItemStack) node.selected.output(node.target.key()).typed().getIngredient()))
                return "物品栏没有安全空间接收：" + node.target.name();
        }
        return null;
    }

    private static boolean insert(List<ItemStack> inventory, ItemStack input) {
        int left = input.getCount();
        for (var stack : inventory) if (!stack.isEmpty() && ItemStack.isSameItemSameTags(stack, input)) {
            int count = Math.min(left, stack.getMaxStackSize() - stack.getCount()); stack.grow(count); left -= count;
        }
        for (int i = 0; i < inventory.size() && left > 0; i++) if (inventory.get(i).isEmpty()) {
            int count = Math.min(left, input.getMaxStackSize());
            var copy = input.copy(); copy.setCount(count); inventory.set(i, copy); left -= count;
        }
        return left == 0;
    }

    private static <R> Optional<IRecipeTransferHandler<AbstractContainerMenu, R>> handler(IRecipeLayoutDrawable<R> layout, AbstractContainerMenu menu) {
        return TreeJeiPlugin.runtime.getRecipeTransferManager().getRecipeTransferHandler(menu, layout.getRecipeCategory());
    }

    private static <R> String preview(IRecipeLayoutDrawable<R> layout, AbstractContainerMenu menu, RecipeNode node) {
        return transferRecipe(layout, menu, node, false);
    }

    @SuppressWarnings("unchecked") // CRAFTING and CraftingRecipe are checked at the layout boundary.
    private static <R> String transferRecipe(IRecipeLayoutDrawable<R> layout, AbstractContainerMenu menu, RecipeNode node,
                                             boolean doTransfer) {
        var handler = handler(layout, menu);
        if (handler.isEmpty()) return node.target.name() + "：JEI 转移处理器已失效";
        try {
            if (OptionalIntegrations.crafting(menu) && Screen.hasControlDown()) return "请松开 Ctrl；未发送 AE2 自动合成请求";
            R recipe = OptionalIntegrations.crafting(menu) ? (R) OptionalIntegrations.recipe(node) : layout.getRecipe();
            var error = handler.get().transferRecipe(menu, recipe,
                    new SelectedRecipeSlots(layout.getRecipeSlotsView(), node), Minecraft.getInstance().player, false, doTransfer);
            return error == null || !(OptionalIntegrations.crafting(menu)) && error.getType().allowsTransfer ? null
                    : node.target.name() + "：JEI 拒绝填入，请检查服务端 JEI、选定材料及腾挪空间";
        } catch (RuntimeException e) {
            CtiRecipeAssistant.LOGGER.warn("JEI transfer failed for {}", node.selected.id(), e);
            return node.target.name() + "：JEI 处理器异常 " + e.getClass().getSimpleName() + "：" + e.getMessage();
        }
    }

    public static void start(ClientRecipeOperation<?> operation) {
        if (busy()) return;
        var mc = Minecraft.getInstance();
        if (!(operation.parentScreen() instanceof AbstractContainerScreen<?> screen)
                || mc.player == null || screen.getMenu() != mc.player.containerMenu
                || !CraftingInventory.supports(screen.getMenu())) return;
        // The generic helper below keeps the recipe/category pair's type together.
        startLayout(operation);
    }

    private static <R> void startLayout(ClientRecipeOperation<R> operation) {
        state = State.PLANNING;
        var layout = TreeJeiPlugin.runtime.getRecipeManager().createRecipeLayoutDrawable(operation.category(),
                operation.recipe(), TreeJeiPlugin.runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup());
        if (layout.isEmpty()) { finish(State.FAILED, "JEI 无法建立当前配方布局"); return; }
        cached = null; previews.clear();
        var availability = check(layout.get(), operation.parentScreen());
        if (!availability.enabled()) { finish(State.FAILED, availability.reason().getString()); return; }
        task = cached; completed = 0; cancel = false; state = State.TRANSFERRING;
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        clientTick++;
        if (!busy()) return;
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null || mc.player.containerMenu != task.menu
                || TreeJeiPlugin.runtime == null) { finish(State.FAILED, "工作台已关闭或连接已断开"); return; }
        String unavailable = OptionalIntegrations.unavailable(task.menu);
        if (unavailable != null) { finish(State.FAILED, unavailable); return; }
        if (state == State.WAITING_SYNC) { observe(); return; }
        if (cancel && task.menu.getCarried().isEmpty()) { finish(State.CANCELLED, "已取消，已完成的物品保留"); return; }
        if (state == State.TRANSFERRING) transfer(task.index.layout(task.steps.get(completed).selected.id()));
        else if (state == State.CRAFTING) {
            var result = task.inventory.result().getItem();
            var node = task.steps.get(completed);
            var declared = (ItemStack) node.selected.output(node.target.key()).typed().getIngredient();
            if (!ItemStack.matches(result, declared) || !task.menu.getCarried().isEmpty()
                    || !canReceive(result)) { finish(State.FAILED, "结果与计划不符、光标非空或背包空间不足"); return; }
            expected = result.copy(); beforeGrid = task.inventory.gridSnapshot();
            click(task.inventory.result().index, Await.PICKUP);
        }
    }

    private static <R> void transfer(IRecipeLayoutDrawable<R> layout) {
        var menu = task.menu;
        String error = transferRecipe(layout, menu, task.steps.get(completed), false);
        if (error != null) { finish(State.FAILED, error); return; }
        if (ready(task.inventory, task.steps.get(completed))) { state = State.CRAFTING; return; }
        beforeGrid = task.inventory.gridSnapshot(); stateId = menu.getStateId();
        error = transferRecipe(layout, menu, task.steps.get(completed), true);
        if (error != null) { finish(State.FAILED, error); return; }
        awaiting = Await.TRANSFER; ticks = 0; state = State.WAITING_SYNC;
    }

    private static void click(int slot, Await action) {
        var menu = task.menu;
        stateId = menu.getStateId(); beforeCarried = menu.getCarried().copy();
        beforeDestination = menu.getSlot(slot).getItem().copy(); destination = slot;
        // Vanilla container protocol without local prediction: all observed changes are server updates.
        if (action == Await.PICKUP && OptionalIntegrations.crafting(menu))
            OptionalIntegrations.takeResult(slot);
        else Minecraft.getInstance().getConnection().send(new ServerboundContainerClickPacket(menu.containerId,
                    stateId, slot, 0, ClickType.PICKUP, beforeCarried, new Int2ObjectOpenHashMap<>()));
        awaiting = action; ticks = 0; state = State.WAITING_SYNC;
    }

    private static void observe() {
        var menu = task.menu;
        boolean updated = menu.getStateId() != stateId;
        if (awaiting == Await.TRANSFER) {
            if ((updated || !same(task.inventory.gridSnapshot(), beforeGrid))
                    && ready(task.inventory, task.steps.get(completed))) { state = State.CRAFTING; return; }
        } else if (awaiting == Await.PICKUP) {
            // AE2 can refill the grid from ME during a single craft, leaving its input snapshot unchanged.
            if (ItemStack.matches(menu.getCarried(), expected) && (OptionalIntegrations.crafting(menu)
                    || updated && !same(task.inventory.gridSnapshot(), beforeGrid))) {
                deposit(); return;
            }
        } else {
            var carried = menu.getCarried();
            int moved = beforeCarried.getCount() - carried.getCount();
            var slot = menu.getSlot(destination).getItem();
            if (updated && moved > 0 && ItemStack.isSameItemSameTags(slot, beforeCarried)
                    && slot.getCount() == beforeDestination.getCount() + moved
                    && (carried.isEmpty() || ItemStack.isSameItemSameTags(carried, beforeCarried))) {
                if (!carried.isEmpty()) { deposit(); return; }
                completed++;
                if (cancel) finish(State.CANCELLED, "已取消，已完成的物品保留");
                else if (completed == task.steps.size()) finish(State.COMPLETED, "最终产物已进入物品栏");
                else state = State.TRANSFERRING;
                return;
            }
        }
        if (++ticks >= 100) finish(State.FAILED, awaiting == Await.TRANSFER
                ? "填入尚未符合选定配方或材料，等待同步超时" : "等待服务器同步超时，保留当前物品状态");
    }

    private static boolean canReceive(ItemStack output) {
        int room = 0;
        for (var slot : task.inventory.playerSlots()) {
            var stack = slot.getItem();
            if (slot.mayPlace(output) && (stack.isEmpty() || ItemStack.isSameItemSameTags(stack, output)))
                room += Math.min(slot.getMaxStackSize(output), output.getMaxStackSize()) - stack.getCount();
        }
        return room >= output.getCount();
    }

    private static void deposit() {
        var carried = task.menu.getCarried();
        for (var slot : task.inventory.playerSlots()) {
            var stack = slot.getItem();
            if (slot.mayPlace(carried) && (stack.isEmpty() || ItemStack.isSameItemSameTags(stack, carried))
                    && stack.getCount() < Math.min(slot.getMaxStackSize(carried), carried.getMaxStackSize())) {
                click(slot.index, Await.DEPOSIT); return;
            }
        }
        finish(State.FAILED, "无法放回产物，请手动处理光标物品");
    }

    private static boolean ready(CraftingInventory source, RecipeNode node) {
        var output = (ItemStack) node.selected.output(node.target.key()).typed().getIngredient();
        if (!ItemStack.matches(source.result().getItem(), output)) return false;
        // Verify the JEI-selected slot contents, without implementing a recipe matcher or choosing alternatives.
        for (int i = 0; i < 9; i++) {
            var stack = source.grid().get(i).getItem();
            if (i >= node.selected.inputs().size() || node.selected.inputs().get(i).isEmpty()) {
                if (!stack.isEmpty()) return false;
            } else if (!ItemStack.isSameItemSameTags(stack, (ItemStack) node.selectedInput(i).typed().getIngredient())) return false;
        }
        return true;
    }
    private static ItemStack[] snapshot(AbstractContainerMenu menu) {
        return menu.slots.stream().map(slot -> slot.getItem().copy()).toArray(ItemStack[]::new);
    }
    private static boolean same(ItemStack[] first, ItemStack[] second) {
        if (first == null || second == null || first.length != second.length) return false;
        for (int i = 0; i < first.length; i++) if (!ItemStack.matches(first[i], second[i])) return false;
        return true;
    }
    private static void finish(State end, String reason) {
        String progress = task == null ? "" : " [" + completed + "/" + task.steps.size() + "] "
                + task.target + (completed < task.steps.size() ? "；当前：" + task.steps.get(completed).target.name() : "");
        state = end; task = null; cached = null; previews.clear();
        var mc = Minecraft.getInstance();
        if (mc.player != null) mc.gui.getChat().addMessage(Component.literal("一键合成：" + reason + progress));
    }

    @SubscribeEvent
    public static void render(ScreenEvent.Render.Post event) {
        if (!busy()) return;
        var mc = Minecraft.getInstance(); var screen = event.getScreen();
        var current = task.steps.get(completed);
        String phase = switch (state) {
            case TRANSFERRING -> "填入配方";
            case CRAFTING -> "取得产物";
            case WAITING_SYNC -> "等待同步";
            default -> "检查计划";
        };
        String text = task.target + "  " + completed + "/" + task.steps.size() + "  "
                + "当前：" + current.target.name() + "（" + current.selected.machine() + "）  " + phase;
        var pose = event.getPoseStack(); pose.pushPose(); pose.translate(0, 0, 700);
        Screen.fill(pose, 0, 0, screen.width, 26, 0xEE303030);
        Screen.drawString(pose, mc.font, mc.font.plainSubstrByWidth(text, Math.max(20, screen.width - 85)), 5, 8, 0xFFFFFF);
        Screen.drawString(pose, mc.font, cancel ? "正在取消" : "取消", screen.width - 78, 8, 0xFFE0A0);
        pose.popPose();
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void mouse(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!busy()) return;
        if (event.getMouseY() < 26 && event.getMouseX() >= event.getScreen().width - 80) cancel();
        event.setCanceled(true);
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void key(ScreenEvent.KeyPressed.Pre event) {
        if (!busy()) return;
        if (event.getKeyCode() == GLFW.GLFW_KEY_BACKSPACE) cancel();
        if (event.getKeyCode() != GLFW.GLFW_KEY_ESCAPE) event.setCanceled(true);
    }
}

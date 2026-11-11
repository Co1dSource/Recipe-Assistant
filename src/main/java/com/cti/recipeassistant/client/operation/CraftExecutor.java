package com.cti.recipeassistant.client.operation;

import com.cti.recipeassistant.integration.OptionalIntegrations;
import com.cti.recipeassistant.RecipeAssistant;
import com.cti.recipeassistant.client.jei.TreeJeiPlugin;
import com.cti.recipeassistant.recipe.*;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
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
import java.util.function.ToLongFunction;

@Mod.EventBusSubscriber(modid = RecipeAssistant.MOD_ID, value = Dist.CLIENT)
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
    private CraftExecutor() {}
    public static boolean busy() { return task != null; }
    public static State state() { return state; }
    public static void cancel() { if (busy()) cancel = true; }

    /** One quantity dialog's route and latest synchronized inventory. */
    public static final class Session {
        private final AbstractContainerMenu menu;
        private final RecipeNode root;
        private final JeiRecipeIndex index;
        private final CraftingInventory source;
        private final String routeProblem;
        private ItemStack[] previousSlots;
        private Map<ItemIdentity, Long> network = Map.of();
        private final Map<Object, Long> available = new HashMap<>();
        private long maximum;
        private String problem;
        private boolean maximumKnown;

        public Session(Screen parent, RecipeNode root, JeiRecipeIndex index) {
            this.root = root;
            this.index = index;
            menu = parent instanceof AbstractContainerScreen<?> container ? container.getMenu() : null;
            var layout = root.selected == null ? null : index.layout(root.selected.id());
            if (menu == null || !CraftingInventory.supports(menu)) {
                source = null; routeProblem = "需要打开有效的 3×3 工作台";
            } else if (root.selected == null) {
                source = null; routeProblem = "请先选择工作台配方";
            } else if (layout == null || !(layout.getRecipe() instanceof CraftingRecipe recipe)
                    || !recipe.canCraftInDimensions(3, 3)) {
                source = null; routeProblem = "一键合成只支持 3×3 工作台配方";
            } else {
                source = CraftingInventory.of(menu, recipe);
                routeProblem = validateLayouts(root, index, menu, new int[1]);
            }
        }

        public long maximum() { return maximum; }
        public String problem() { return problem; }

        public String oneCraftProblem() {
            refreshStock();
            return problem;
        }

        public void refresh() {
            refreshStock();
            if (problem != null || maximumKnown) return;
            maximumKnown = true;
            var first = prepare(1);
            problem = preview(index.layout(first.steps.get(0).selected.id()), menu, first.steps.get(0));
            if (problem != null) return;

            long output = MaterialRequirements.rootOutput(root);
            long low = 1, high = CraftPlanner.MAX_STEPS;
            while (low < high) {
                long mid = (low + high + 1) / 2;
                if (prepare(mid * output).reason == null) low = mid;
                else high = mid - 1;
            }
            maximum = Math.min(Integer.MAX_VALUE, low * output);
        }

        private void refreshStock() {
            var player = Minecraft.getInstance().player;
            String unavailable = routeProblem;
            if (unavailable == null) {
                if (player == null || player.containerMenu != menu) unavailable = "工作台已关闭";
                else if (busy() || OptionalIntegrations.busy() || SingleRecipeTransfer.busy()) unavailable = "正在执行物品操作";
                else if (!menu.getCarried().isEmpty()) unavailable = "请先放下光标携带的物品";
                else unavailable = OptionalIntegrations.unavailable(menu);
            }
            if (unavailable != null) {
                problem = unavailable; maximum = 0; maximumKnown = false; previousSlots = null;
                return;
            }
            var slots = snapshot(menu);
            var stock = source.networkStock();
            if (same(slots, previousSlots) && network.equals(stock)) return;
            previousSlots = slots; network = stock; available.clear();
            maximum = 0; maximumKnown = false;
            problem = prepare(1).reason;
        }

        private Prepared prepare(long quantity) {
            ToLongFunction<TreeIngredient> stock = ingredient -> available.computeIfAbsent(
                    MaterialRequirements.identity(ingredient), key -> source.available(ingredient, network));
            var plan = CraftPlanner.plan(root, stock, quantity);
            String reason = plan.reason();
            if (reason == null) reason = checkSpace(source, plan.steps(), network);
            return new Prepared(menu, source, plan.steps(), index, root.target.name(), reason);
        }

        public boolean start(long quantity) {
            refresh();
            if (quantity <= 0 || quantity > maximum) return false;
            var prepared = prepare(quantity);
            if (prepared.reason != null) { problem = prepared.reason; return false; }
            task = prepared; completed = 0; cancel = false; state = State.TRANSFERRING;
            return true;
        }
    }

    private static String validateLayouts(RecipeNode node, JeiRecipeIndex index, AbstractContainerMenu menu, int[] visited) {
        if (node.depth >= 64) return null;
        if (++visited[0] > 4096) return "配方树超过 4096 节点，请缩小选择范围";
        node.load();
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
            String reason = validateLayouts(child, index, menu, visited);
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
            RecipeAssistant.LOGGER.warn("JEI transfer failed for {}", node.selected.id(), e);
            return node.target.name() + "：JEI 处理器异常 " + e.getClass().getSimpleName() + "：" + e.getMessage();
        }
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
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
        state = end; task = null;
        var mc = Minecraft.getInstance();
        if (mc.player != null) mc.gui.getChat().addMessage(Component.literal("一键合成：" + reason + progress));
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void mouse(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!busy()) return;
        event.setCanceled(true);
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void key(ScreenEvent.KeyPressed.Pre event) {
        if (!busy()) return;
        if (event.getKeyCode() == GLFW.GLFW_KEY_BACKSPACE) cancel();
        if (event.getKeyCode() != GLFW.GLFW_KEY_ESCAPE) event.setCanceled(true);
    }
}

package com.cti.ctirecipeassistant.integration.ae2;

import appeng.api.networking.crafting.CraftingSubmitErrorCode;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.client.gui.me.crafting.CraftAmountScreen;
import appeng.client.gui.me.crafting.CraftConfirmScreen;
import appeng.client.gui.widgets.NumberEntryWidget;
import appeng.core.localization.GuiText;
import appeng.core.sync.packets.CraftingJobStatusPacket.Status;
import appeng.helpers.InventoryAction;
import appeng.menu.me.common.GridInventoryEntry;
import appeng.menu.me.common.MEStorageMenu;
import appeng.menu.me.crafting.CraftAmountMenu;
import appeng.menu.me.crafting.CraftConfirmMenu;
import com.cti.ctirecipeassistant.client.gui.CompactUi;
import com.cti.ctirecipeassistant.client.operation.CraftExecutor;
import com.cti.ctirecipeassistant.client.operation.ItemIdentity;
import com.cti.ctirecipeassistant.integration.OptionalIntegrations.Supply;
import com.cti.ctirecipeassistant.recipe.MaterialRequirements;
import com.cti.ctirecipeassistant.recipe.TreeIngredient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fluids.FluidStack;
import org.lwjgl.glfw.GLFW;

import java.util.*;
import java.util.function.Consumer;

/** Submits each material through AE2's native confirmation screens. */
public final class Ae2Refill {
    private enum Phase { TERMINAL, OPENING_AMOUNT, AMOUNT, CONFIRM, FINAL }
    private static final int CANCEL_WIDTH = 42, CANCEL_HEIGHT = 18, CANCEL_MARGIN = 4;
    private static Ae2Refill active;

    private final ClientLevel level = Minecraft.getInstance().level;
    private final MEStorageMenu original;
    private final Deque<TreeIngredient> remaining;
    private final List<TreeIngredient> required;
    private final Consumer<Screen> finish;
    private String jobId;
    private boolean started;
    private Phase phase = Phase.TERMINAL;
    private MEStorageMenu requestedFrom;
    private AEKey requestedKey;
    private int requestedAmount;
    private int ticks;

    private Ae2Refill(MEStorageMenu menu, List<TreeIngredient> orders, List<TreeIngredient> required, Consumer<Screen> finish) {
        original = menu;
        remaining = new ArrayDeque<>(orders);
        this.required = required;
        this.finish = finish;
    }

    static boolean busy() { return active != null; }

    static boolean available(AbstractContainerMenu menu) {
        return menu instanceof MEStorageMenu terminal && terminal.isPowered() && terminal.getClientRepo() != null;
    }

    static Map<Object, Supply> supplies(AbstractContainerMenu menu, List<TreeIngredient> materials) {
        var entries = entries((MEStorageMenu) menu);
        var result = new HashMap<Object, Supply>();
        for (var material : materials) {
            var entry = entries.get(key(material));
            if (entry != null) result.put(MaterialRequirements.identity(material), supply(entry));
        }
        return result;
    }

    static boolean start(AbstractContainerMenu menu, List<TreeIngredient> materials) {
        return start(menu, materials, List.of(), null);
    }

    static boolean start(AbstractContainerMenu menu, List<TreeIngredient> orders, List<TreeIngredient> required, Consumer<Screen> finish) {
        if (active != null || orders.isEmpty() && finish == null
                || orders.stream().anyMatch(m -> m.amount() <= 0 || m.amount() > Integer.MAX_VALUE)) return false;
        active = new Ae2Refill((MEStorageMenu) menu, orders, required, finish);
        return true;
    }

    private static Map<AEKey, GridInventoryEntry> entries(MEStorageMenu menu) {
        var result = new HashMap<AEKey, GridInventoryEntry>();
        for (var entry : menu.getClientRepo().getAllEntries()) result.put(entry.getWhat(), entry);
        return result;
    }

    private static Supply supply(GridInventoryEntry entry) {
        return entry == null ? Supply.MISSING : new Supply(entry.getStoredAmount(), entry.isCraftable());
    }

    private static AEKey key(TreeIngredient material) {
        var value = material.typed().getIngredient();
        if (value instanceof FluidStack fluid) return AEFluidKey.of(fluid);
        if (!(value instanceof ItemStack item)) return null;
        var key = AEItemKey.of(item);
        return key != null && ItemIdentity.of(item).equals(ItemIdentity.of(key.toStack())) ? key : null;
    }

    public static void prefill(CraftAmountScreen screen, NumberEntryWidget amount) {
        if (active == null || active.phase != Phase.OPENING_AMOUNT) return;
        var what = screen.getMenu().getWhatToCraft();
        if (what == null) return;
        if (!what.what().equals(active.requestedKey)) { active = null; return; }
        int requested = active.requestedAmount;
        amount.setLongValue(requested);
        active.phase = Phase.AMOUNT;
        active.ticks = 0;
        screen.getMenu().confirm(requested, true);
    }

    public static void jobStatus(String id, AEKey key, long amount, Status status) {
        var task = active;
        if (task == null) return;
        if (status == Status.STARTED && task.phase == Phase.CONFIRM && !task.started
                && task.requestedKey.equals(key) && task.requestedAmount == amount) {
            if (task.finish != null) task.jobId = id;
            task.started = true;
        } else if (id.equals(task.jobId)) {
            if (status == Status.FINISHED) task.jobId = null;
            else if (status == Status.CANCELLED) stop("AE2 材料订单已取消");
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void opening(ScreenEvent.Opening event) {
        if (active == null) return;
        if (active.phase == Phase.FINAL) { CraftExecutor.cancel(); return; }
        if (!(event.getNewScreen() instanceof AbstractContainerScreen<?> screen)) { active = null; return; }
        if (active.phase == Phase.AMOUNT && screen.getMenu() instanceof CraftConfirmMenu) active.phase = Phase.CONFIRM;
        else if (active.phase == Phase.CONFIRM && screen.getMenu() instanceof CraftAmountMenu) active = null;
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END && active != null) active.advance();
    }

    private static boolean locksInput() {
        return active != null && active.finish != null;
    }

    private static void cancelAutomatic(Screen screen) {
        if (active.phase == Phase.FINAL) {
            CraftExecutor.cancel();
        } else {
            stop("已取消自动合成");
            if (screen instanceof AbstractContainerScreen<?> container && container.getMenu() instanceof CraftConfirmMenu confirm)
                confirm.goBack();
        }
    }

    private static int cancelX(Screen screen) { return screen.width - CANCEL_WIDTH - CANCEL_MARGIN; }

    @SubscribeEvent
    public static void render(ScreenEvent.Render.Post event) {
        var screen = event.getScreen();
        if (!locksInput() || screen instanceof CraftConfirmScreen) return;
        int x = cancelX(screen);
        var pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(0, 0, 750);
        CompactUi.button(pose, x, CANCEL_MARGIN, CANCEL_WIDTH, CANCEL_HEIGHT,
                CompactUi.hit(event.getMouseX(), event.getMouseY(), x, CANCEL_MARGIN, CANCEL_WIDTH, CANCEL_HEIGHT), true);
        Screen.drawCenteredString(pose, Minecraft.getInstance().font, GuiText.Cancel.text(),
                x + CANCEL_WIDTH / 2, CANCEL_MARGIN + 5, 0xFFFFFF);
        pose.popPose();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void mouse(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!locksInput()) return;
        event.setCanceled(true);
        if (event.getButton() != 0) return;
        var screen = event.getScreen();
        double x = event.getMouseX(), y = event.getMouseY();
        if (screen instanceof CraftConfirmScreen confirm) {
            var cancel = GuiText.Cancel.text();
            if (confirm.children().stream().anyMatch(child -> child instanceof Button button
                    && button.active && button.isMouseOver(x, y) && button.getMessage().equals(cancel)))
                cancelAutomatic(screen);
        } else if (CompactUi.hit(x, y, cancelX(screen), CANCEL_MARGIN, CANCEL_WIDTH, CANCEL_HEIGHT)) {
            cancelAutomatic(screen);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void key(ScreenEvent.KeyPressed.Pre event) {
        if (!locksInput()) return;
        event.setCanceled(true);
        if (event.getKeyCode() == GLFW.GLFW_KEY_ESCAPE) cancelAutomatic(event.getScreen());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void keyReleased(ScreenEvent.KeyReleased.Pre event) { if (locksInput()) event.setCanceled(true); }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void typed(ScreenEvent.CharacterTyped.Pre event) { if (locksInput()) event.setCanceled(true); }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void scroll(ScreenEvent.MouseScrolled.Pre event) { if (locksInput()) event.setCanceled(true); }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void drag(ScreenEvent.MouseDragged.Pre event) { if (locksInput()) event.setCanceled(true); }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void release(ScreenEvent.MouseButtonReleased.Pre event) { if (locksInput()) event.setCanceled(true); }

    private void advance() {
        if (phase == Phase.FINAL) {
            if (!CraftExecutor.busy()) active = null;
            return;
        }
        var mc = Minecraft.getInstance();
        if (mc.level != level || mc.player == null || !(mc.screen instanceof AbstractContainerScreen<?> screen)
                || screen.getMenu() != mc.player.containerMenu) { active = null; return; }
        var menu = screen.getMenu();
        if (menu instanceof CraftAmountMenu) {
            if (phase == Phase.CONFIRM) active = null;
            else if (phase == Phase.OPENING_AMOUNT || phase == Phase.AMOUNT) waitForSync();
            return;
        }
        if (menu instanceof CraftConfirmMenu confirm && (phase == Phase.AMOUNT || phase == Phase.CONFIRM)) {
            phase = Phase.CONFIRM;
            var plan = confirm.getPlan();
            var error = confirm.submitError.result();
            boolean missing = (plan != null && plan.isSimulation())
                    || (error != null && error.errorCode() == CraftingSubmitErrorCode.MISSING_INGREDIENT);
            if (missing || error != null) {
                stop(missing ? "AE2 报告材料不足，当前补料订单已停止" : "AE2 未能提交当前材料订单");
                if (missing) confirm.goBack();
            }
            return;
        }
        if (!(menu instanceof MEStorageMenu terminal) || menu.getType() != original.getType()
                || !Objects.equals(terminal.getLocator(), original.getLocator()) || phase == Phase.AMOUNT) {
            active = null; return;
        }
        if (phase == Phase.OPENING_AMOUNT) {
            if (terminal != requestedFrom) active = null;
            else waitForSync();
            return;
        }
        if (phase == Phase.CONFIRM) {
            if (!started) { stop("未收到 AE2 材料订单的开始状态"); return; }
            phase = Phase.TERMINAL;
            ticks = 0;
        }
        if (jobId != null) return;
        if (remaining.isEmpty()) {
            if (finish == null) active = null;
            else finishWhenStockSynced(screen, terminal);
            return;
        }
        if (!available(terminal) || terminal.getClientRepo().getAllEntries().isEmpty()) {
            waitForSync(); return;
        }
        var material = remaining.removeFirst();
        var entry = entries(terminal).get(key(material));
        if (entry == null) {
            active = null;
            mc.player.displayClientMessage(Component.literal("AE2 终端没有此材料的请求条目：" + material.name()), false);
            return;
        }
        requestedFrom = terminal;
        requestedKey = entry.getWhat();
        requestedAmount = Math.toIntExact(material.amount());
        started = false;
        phase = Phase.OPENING_AMOUNT;
        ticks = 0;
        terminal.handleInteraction(entry.getSerial(), InventoryAction.AUTO_CRAFT);
    }

    private void finishWhenStockSynced(Screen screen, MEStorageMenu terminal) {
        if (ticks++ % 5 != 0) return;
        if (available(terminal)) {
            var stock = supplies(terminal, required);
            if (required.stream().allMatch(m -> stock.getOrDefault(MaterialRequirements.identity(m), Supply.MISSING).stored() >= m.amount())) {
                active = null;
                finish.accept(screen);
                if (CraftExecutor.busy()) {
                    phase = Phase.FINAL;
                    active = this;
                }
                return;
            }
        }
        if (ticks >= 100) stop("材料订单已结束，但 ME 库存未能满足最终合成");
    }

    private static void stop(String message) {
        active = null;
        var player = Minecraft.getInstance().player;
        if (player != null) player.displayClientMessage(Component.literal(message), false);
    }

    private void waitForSync() {
        if (++ticks < 100) return;
        active = null;
        var player = Minecraft.getInstance().player;
        if (player != null) player.displayClientMessage(Component.literal("ME 补料：未收到 AE2 菜单或库存同步"), false);
    }
}

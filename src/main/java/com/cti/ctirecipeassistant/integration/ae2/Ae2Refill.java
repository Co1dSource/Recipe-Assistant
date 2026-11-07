package com.cti.ctirecipeassistant.integration.ae2;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AmountFormat;
import appeng.client.gui.me.crafting.CraftAmountScreen;
import appeng.client.gui.widgets.NumberEntryWidget;
import appeng.helpers.InventoryAction;
import appeng.menu.me.common.GridInventoryEntry;
import appeng.menu.me.common.MEStorageMenu;
import appeng.menu.me.crafting.CraftAmountMenu;
import appeng.menu.me.crafting.CraftConfirmMenu;
import com.cti.ctirecipeassistant.client.operation.ItemIdentity;
import com.cti.ctirecipeassistant.integration.OptionalIntegrations;
import com.cti.ctirecipeassistant.integration.OptionalIntegrations.Supply;
import com.cti.ctirecipeassistant.recipe.MaterialRequirements;
import com.cti.ctirecipeassistant.recipe.TreeIngredient;
import net.minecraft.client.Minecraft;
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

import java.util.*;

/** Opens one native request at a time and leaves submission to AE2's screens. */
public final class Ae2Refill {
    private enum Phase { TERMINAL, OPENING_AMOUNT, AMOUNT, CONFIRM }
    private static Ae2Refill active;

    private final ClientLevel level = Minecraft.getInstance().level;
    private final MEStorageMenu original;
    private final Deque<TreeIngredient> remaining;
    private Phase phase = Phase.TERMINAL;
    private MEStorageMenu requestedFrom;
    private AEKey requestedKey;
    private long shortage;
    private int ticks;

    private Ae2Refill(MEStorageMenu menu, List<TreeIngredient> materials) {
        original = menu;
        remaining = new ArrayDeque<>(materials);
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
        var terminal = (MEStorageMenu) menu;
        var missing = OptionalIntegrations.refillOrders(materials, supplies(terminal, materials));
        if (missing.isEmpty()) return false;
        active = new Ae2Refill(terminal, missing);
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
        amount.setLongValue(active.shortage);
        if (amount.getLongValue().orElse(0) != active.shortage) {
            String requested = what.what().formatAmount(active.shortage, AmountFormat.FULL);
            screen.getMenu().getPlayer().displayClientMessage(
                    Component.literal("ME 补料：实际缺量 " + requested + " 超出原生数量框范围"), false);
        }
        active.phase = Phase.AMOUNT;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void opening(ScreenEvent.Opening event) {
        if (active == null) return;
        if (!(event.getNewScreen() instanceof AbstractContainerScreen<?> screen)) { active = null; return; }
        if (active.phase == Phase.AMOUNT && screen.getMenu() instanceof CraftConfirmMenu) active.phase = Phase.CONFIRM;
        else if (active.phase == Phase.CONFIRM && screen.getMenu() instanceof CraftAmountMenu) active.phase = Phase.AMOUNT;
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END && active != null) active.advance();
    }

    private void advance() {
        var mc = Minecraft.getInstance();
        if (mc.level != level || mc.player == null || !(mc.screen instanceof AbstractContainerScreen<?> screen)
                || screen.getMenu() != mc.player.containerMenu) { active = null; return; }
        var menu = screen.getMenu();
        if (menu instanceof CraftAmountMenu) {
            if (phase == Phase.CONFIRM) phase = Phase.AMOUNT;
            if (phase == Phase.OPENING_AMOUNT) waitForSync();
            return;
        }
        if (menu instanceof CraftConfirmMenu && (phase == Phase.AMOUNT || phase == Phase.CONFIRM)) {
            phase = Phase.CONFIRM;
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
            phase = Phase.TERMINAL;
            ticks = 0;
            if (remaining.isEmpty()) { active = null; return; }
        }
        if (!available(terminal) || terminal.getClientRepo().getAllEntries().isEmpty()) {
            waitForSync(); return;
        }
        var entries = entries(terminal);
        while (!remaining.isEmpty()) {
            var material = remaining.removeFirst();
            var entry = entries.get(key(material));
            var supply = supply(entry);
            if (supply.stored() >= material.amount()) continue;
            if (!supply.craftable()) {
                active = null;
                mc.player.displayClientMessage(Component.literal("ME 补料已停止，无法供应：" + material.name()), false);
                return;
            }
            requestedFrom = terminal;
            requestedKey = entry.getWhat();
            shortage = material.amount() - entry.getStoredAmount();
            phase = Phase.OPENING_AMOUNT;
            ticks = 0;
            terminal.handleInteraction(entry.getSerial(), InventoryAction.AUTO_CRAFT);
            return;
        }
        active = null;
    }

    private void waitForSync() {
        if (++ticks < 100) return;
        active = null;
        var player = Minecraft.getInstance().player;
        if (player != null) player.displayClientMessage(Component.literal("ME 补料：未收到 AE2 菜单或库存同步"), false);
    }
}

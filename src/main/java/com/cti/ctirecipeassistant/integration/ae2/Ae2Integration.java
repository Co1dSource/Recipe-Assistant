package com.cti.ctirecipeassistant.integration.ae2;

import appeng.api.stacks.AEItemKey;
import appeng.menu.SlotSemantics;
import appeng.menu.me.items.CraftingTermMenu;
import appeng.menu.me.items.PatternEncodingTermMenu;
import appeng.menu.implementations.PatternAccessTermMenu;
import appeng.integration.modules.jei.transfer.UseCraftingRecipeTransfer;
import appeng.core.sync.network.NetworkHandler;
import appeng.core.sync.packets.InventoryActionPacket;
import appeng.helpers.InventoryAction;
import com.cti.ctirecipeassistant.integration.OptionalIntegrations;
import com.cti.ctirecipeassistant.client.operation.*;
import com.cti.ctirecipeassistant.recipe.RecipeNode;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import com.cti.ctirecipeassistant.recipe.TreeIngredient;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraftforge.common.MinecraftForge;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

public final class Ae2Integration implements OptionalIntegrations.Ae2 {
    private static boolean extendedProviders;
    public static boolean supportsProvider(int size) { return size == 9 || size == 36 && extendedProviders; }
    public Ae2Integration() {
        extendedProviders = net.minecraftforge.fml.ModList.get().isLoaded("expatternprovider");
        MinecraftForge.EVENT_BUS.register(PatternEncoder.class);
        MinecraftForge.EVENT_BUS.register(PatternDistributor.class);
        MinecraftForge.EVENT_BUS.register(DistributionPreview.class);
        MinecraftForge.EVENT_BUS.register(Ae2Refill.class);
    }
    public boolean busy() { return PatternEncoder.busy() || PatternDistributor.busy() || DistributionPreview.active() || Ae2Refill.busy(); }
    public void preview(ClientRecipeOperation<?> operation) { PatternEncoder.preview(operation); }
    public ClientRecipeOperation.Mode mode(AbstractContainerMenu menu) {
        if (menu instanceof PatternAccessTermMenu) return ClientRecipeOperation.Mode.HIDDEN;
        return menu instanceof PatternEncodingTermMenu ? ClientRecipeOperation.Mode.BATCH_PATTERN_ENCODING : null;
    }
    public boolean crafting(AbstractContainerMenu menu) { return menu instanceof CraftingTermMenu; }
    public CraftingInventory inventory(AbstractContainerMenu menu) {
        var terminal = (CraftingTermMenu) menu;
        var player = Stream.concat(terminal.getSlots(SlotSemantics.PLAYER_INVENTORY).stream(),
                        terminal.getSlots(SlotSemantics.PLAYER_HOTBAR).stream())
                .filter(slot -> !terminal.isPlayerInventorySlotLocked(slot.getContainerSlot())).toList();
        return new CraftingInventory(menu, terminal.getSlots(SlotSemantics.CRAFTING_GRID), player, player,
                terminal.getSlots(SlotSemantics.CRAFTING_RESULT).get(0));
    }
    public Map<ItemIdentity, Long> stock(AbstractContainerMenu menu) {
        var result = new HashMap<ItemIdentity, Long>();
        for (var entry : ((CraftingTermMenu) menu).getClientRepo().getAllEntries())
            if (entry.getWhat() instanceof AEItemKey key && entry.getStoredAmount() > 0)
                result.put(ItemIdentity.of(key.toStack()), entry.getStoredAmount());
        return result;
    }
    public String unavailable(AbstractContainerMenu menu) {
        var terminal = (CraftingTermMenu) menu;
        if (terminal.getClientRepo() == null || !terminal.isPowered()) return "合成终端尚未同步或网络无电";
        return null;
    }
    public boolean refillAvailable(AbstractContainerMenu menu) { return Ae2Refill.available(menu); }
    public Map<Object, OptionalIntegrations.Supply> supplies(AbstractContainerMenu menu, List<TreeIngredient> materials) {
        return Ae2Refill.supplies(menu, materials);
    }
    public boolean refill(AbstractContainerMenu menu, List<TreeIngredient> materials) { return Ae2Refill.start(menu, materials); }
    public boolean refill(AbstractContainerMenu menu, List<TreeIngredient> orders, List<TreeIngredient> required, Consumer<Screen> finish) {
        return Ae2Refill.start(menu, orders, required, finish);
    }
    public boolean supportedHandler(IRecipeTransferHandler<?, ?> handler) { return handler instanceof UseCraftingRecipeTransfer<?>; }
    public Object recipe(RecipeNode node) { return AeCraftingSelection.template(node); }
    public void takeResult(int slot) {
        NetworkHandler.instance().sendToServer(new InventoryActionPacket(InventoryAction.CRAFT_ITEM, slot, 0L));
    }
}

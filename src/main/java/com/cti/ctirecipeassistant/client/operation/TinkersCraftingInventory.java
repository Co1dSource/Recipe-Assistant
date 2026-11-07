package com.cti.ctirecipeassistant.client.operation;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.crafting.CraftingRecipe;
import slimeknights.tconstruct.tables.menu.CraftingStationContainerMenu;
import slimeknights.tconstruct.plugin.jei.transfer.CraftingStationTransferInfo;
import java.util.List;

/** Loaded only when TConstruct is present. No adjacent-container discovery is performed here. */
final class TinkersCraftingInventory {
    static boolean isStation(AbstractContainerMenu menu) { return menu instanceof CraftingStationContainerMenu; }
    static CraftingInventory of(AbstractContainerMenu menu, CraftingRecipe recipe) {
        var station = (CraftingStationContainerMenu) menu;
        var info = new CraftingStationTransferInfo();
        int end = menu.slots.size();
        return new CraftingInventory(menu, info.getRecipeSlots(station, recipe),
                List.copyOf(menu.slots.subList(end - 36, end)), info.getInventorySlots(station, recipe), menu.getSlot(9));
    }
}

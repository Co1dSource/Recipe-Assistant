package com.cti.ctirecipeassistant.client.operation;

import com.cti.ctirecipeassistant.integration.OptionalIntegrations;
import com.cti.ctirecipeassistant.recipe.TreeIngredient;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraftforge.fml.ModList;
import java.util.*;
import java.util.stream.Stream;

/** The real slots and existing terminal repository used by the registered transfer handler. */
public record CraftingInventory(AbstractContainerMenu menu, List<Slot> grid, List<Slot> playerSlots,
                                List<Slot> materialSlots, Slot result) {
    public static boolean supports(AbstractContainerMenu menu) {
        return menu.getClass() == CraftingMenu.class || OptionalIntegrations.crafting(menu)
                || ModList.get().isLoaded("tconstruct") && TinkersCraftingInventory.isStation(menu);
    }

    public static CraftingInventory of(AbstractContainerMenu menu, CraftingRecipe recipe) {
        if (menu.getClass() == CraftingMenu.class) {
            var player = List.copyOf(menu.slots.subList(10, 46));
            return new CraftingInventory(menu, List.copyOf(menu.slots.subList(1, 10)), player, player, menu.getSlot(0));
        }
        if (OptionalIntegrations.crafting(menu)) return OptionalIntegrations.inventory(menu);
        return TinkersCraftingInventory.of(menu, recipe);
    }

    public Map<ItemIdentity, Long> networkStock() {
        return OptionalIntegrations.stock(menu);
    }

    public long available(TreeIngredient target, Map<ItemIdentity, Long> network) {
        if (!(target.typed().getIngredient() instanceof ItemStack wanted)) return 0;
        return Stream.concat(grid.stream(), materialSlots.stream()).map(Slot::getItem)
                .filter(stack -> ItemStack.isSameItemSameTags(stack, wanted)).mapToLong(ItemStack::getCount).sum()
                + network.getOrDefault(ItemIdentity.of(wanted), 0L);
    }

    public Map<ItemIdentity, Long> externalStock(Map<ItemIdentity, Long> network) {
        var result = new HashMap<>(network);
        for (var slot : materialSlots) if (!playerSlots.contains(slot) && !slot.getItem().isEmpty())
            result.merge(ItemIdentity.of(slot.getItem()), (long) slot.getItem().getCount(), Long::sum);
        return result;
    }

    public ItemStack[] gridSnapshot() {
        return grid.stream().map(slot -> slot.getItem().copy()).toArray(ItemStack[]::new);
    }
}

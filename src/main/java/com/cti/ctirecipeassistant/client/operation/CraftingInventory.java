package com.cti.ctirecipeassistant.client.operation;

import com.cti.ctirecipeassistant.client.jei.TreeJeiPlugin;
import com.cti.ctirecipeassistant.integration.OptionalIntegrations;
import com.cti.ctirecipeassistant.recipe.TreeIngredient;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.common.transfer.RecipeTransferUtil;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
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

    public List<IRecipeSlotView> missingInputs(List<IRecipeSlotView> inputs, Player player) {
        if (inputs.size() > grid.size()) return List.of();
        var available = new LinkedHashMap<Slot, ItemStack>();
        Stream.concat(grid.stream(), materialSlots.stream())
                .filter(slot -> slot.isActive() && slot.mayPickup(player) && slot.hasItem())
                .forEach(slot -> available.put(slot, slot.getItem().copy()));
        if (OptionalIntegrations.refillAvailable(menu)) {
            var network = networkStock();
            var counted = new HashSet<ItemIdentity>();
            var viewOnly = new SimpleContainer(1);
            for (var input : inputs) input.getItemStacks().forEach(stack -> {
                var identity = ItemIdentity.of(stack);
                long stock = network.getOrDefault(identity, 0L);
                if (stock <= 0 || !counted.add(identity)) return;
                var copy = stack.copy();
                copy.setCount((int) Math.min(grid.size(), stock));
                available.put(new Slot(viewOnly, 0, 0, 0), copy);
            });
        }
        return RecipeTransferUtil.getRecipeTransferOperations(TreeJeiPlugin.runtime.getJeiHelpers().getStackHelper(),
                available, inputs, grid).missingItems;
    }

    public long available(TreeIngredient target, Map<ItemIdentity, Long> network) {
        if (!(target.typed().getIngredient() instanceof ItemStack wanted)) return 0;
        var identity = ItemIdentity.of(wanted);
        return Stream.concat(grid.stream(), materialSlots.stream()).map(Slot::getItem)
                .filter(stack -> ItemStack.isSameItemSameTags(stack, wanted) && identity.equals(ItemIdentity.of(stack)))
                .mapToLong(ItemStack::getCount).sum() + network.getOrDefault(identity, 0L);
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

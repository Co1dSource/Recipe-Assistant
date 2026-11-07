package com.cti.ctirecipeassistant.integration.ae2;

import appeng.api.stacks.*;
import com.cti.ctirecipeassistant.client.gui.CompactUi;
import com.cti.ctirecipeassistant.client.operation.*;
import com.cti.ctirecipeassistant.recipe.*;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.*;
import mezz.jei.api.ingredients.*;
import mezz.jei.api.recipe.RecipeIngredientRole;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.stream.Stream;
import sun.misc.Unsafe;

/** Real selected slots and AE2 GenericStack conversion; fixture recipes, not a CTI game session. */
public class PreviewDataCheck {
    static Unsafe unsafe;
    static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
    record Typed(ItemStack getIngredient) implements ITypedIngredient<ItemStack> {
        public IIngredientType<ItemStack> getType() { return VanillaTypes.ITEM_STACK; }
    }
    static TreeIngredient item(Item item, int count) {
        var stack = new ItemStack(item, count);
        return new TreeIngredient(new Typed(stack), item.toString(), item.toString(), count, "");
    }
    record Slot(RecipeIngredientRole getRole, List<ITypedIngredient<?>> values) implements IRecipeSlotView {
        public Stream<ITypedIngredient<?>> getAllIngredients() { return values.stream(); }
        public <T> Stream<T> getIngredients(IIngredientType<T> type) {
            return values.stream().filter(v -> v.getType().equals(type)).map(v -> type.getIngredientClass().cast(v.getIngredient()));
        }
        public boolean isEmpty() { return values.isEmpty(); }
        public <T> Optional<T> getDisplayedIngredient(IIngredientType<T> type) { return getIngredients(type).findFirst(); }
        public Optional<ITypedIngredient<?>> getDisplayedIngredient() { return values.stream().findFirst(); }
        public Optional<String> getSlotName() { return Optional.empty(); }
        public void drawHighlight(PoseStack pose, int color) {}
    }
    record Slots(List<IRecipeSlotView> getSlotViews) implements IRecipeSlotsView {
        public List<IRecipeSlotView> getSlotViews(RecipeIngredientRole role) { return getSlotViews.stream().filter(s -> s.getRole() == role).toList(); }
        public Optional<IRecipeSlotView> findSlotByName(String name) { return Optional.empty(); }
    }
    static RecipePreferences preferences() throws Exception {
        var prefs = (RecipePreferences)unsafe.allocateInstance(RecipePreferences.class);
        var f = RecipePreferences.class.getDeclaredField("data"); f.setAccessible(true); f.set(prefs, new JsonObject()); return prefs;
    }
    static PatternEncoder.Entry entry(List<List<TreeIngredient>> inputs, List<List<TreeIngredient>> outputs, boolean crafting, RecipePreferences prefs) {
        var target = outputs.get(0).get(0);
        var node = new RecipeNode(target, null, null, prefs); node.loaded = true;
        node.selected = new RecipeCandidate("fixture", crafting ? "minecraft:crafting" : "fixture:machine", "fixture", inputs, outputs, List.of());
        var slots = new ArrayList<IRecipeSlotView>();
        for (var input : inputs) slots.add(new Slot(RecipeIngredientRole.INPUT, input.stream().map(TreeIngredient::typed).toList()));
        for (var output : outputs) slots.add(new Slot(RecipeIngredientRole.OUTPUT, output.stream().map(TreeIngredient::typed).toList()));
        Object recipe = crafting ? new ShapedRecipe(new ResourceLocation("fixture", "grid"), "", 3, 3,
                NonNullList.withSize(9, Ingredient.EMPTY), new ItemStack(Items.ANDESITE, 2)) : new Object();
        var layout = (IRecipeLayoutDrawable<?>)Proxy.newProxyInstance(PreviewDataCheck.class.getClassLoader(), new Class[]{IRecipeLayoutDrawable.class},
                (proxy, method, args) -> switch(method.getName()) { case "getRecipe" -> recipe; case "getRecipeSlotsView" -> new Slots(slots); default -> null; });
        return new PatternEncoder.Entry(node, layout, prefs);
    }
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        appeng.api.integrations.jei.IngredientConverters.register(new appeng.integration.modules.jei.ItemIngredientConverter());
        var f = Unsafe.class.getDeclaredField("theUnsafe"); f.setAccessible(true); unsafe = (Unsafe)f.get(null);
        var prefs = preferences();
        var process = entry(List.of(List.of(item(Items.STONE, 1), item(Items.GRANITE, 1)), List.of(item(Items.COBBLESTONE, 3)), List.of(item(Items.COBBLESTONE, 2))),
                List.of(List.of(item(Items.ANDESITE, 2)), List.of(item(Items.DIAMOND, 1))), false, prefs);
        process.amounts.put(0, 7L);
        var inputs = process.inputs();
        System.out.println("selected inputs=" + inputs);
        check(inputs.size() == 2 && inputs.get(0).amount() == 7 && inputs.get(1).amount() == 5, "manual amount and merged identical inputs");
        check(inputs.get(0).what().equals(AEItemKey.of(Items.STONE)), "selected candidate only");
        check(process.slots().getSlotViews(RecipeIngredientRole.OUTPUT).size() == 1, "ignored byproduct absent");
        check(process.output().amount() == 2, "original target amount fixed");
        var grid = new ArrayList<List<TreeIngredient>>(); for (int i = 0; i < 9; i++) grid.add(List.of());
        grid.set(0, List.of(item(Items.COBBLESTONE, 1))); grid.set(8, List.of(item(Items.DIORITE, 1)));
        var craft = entry(grid, List.of(List.of(item(Items.ANDESITE, 2))), true, prefs);
        var actual = craft.slots().getSlotViews(RecipeIngredientRole.INPUT);
        check(actual.size() == 9 && actual.get(0).getItemStacks().findFirst().orElseThrow().is(Items.COBBLESTONE)
                && actual.get(8).getItemStacks().findFirst().orElseThrow().is(Items.DIORITE)
                && actual.subList(1, 8).stream().allMatch(IRecipeSlotView::isEmpty), "nine slots preserve exact positions");
        var old = ItemIdentity.of(new ItemStack(Items.STONE));
        var nbt = new ItemStack(Items.STONE); nbt.getOrCreateTag().putInt("value", 1);
        var identity = ItemIdentity.of(nbt); nbt.getTag().putInt("value", 2);
        check(!identity.equals(ItemIdentity.of(nbt)) && !identity.equals(old), "stock identity preserves NBT snapshot");
        check(CompactUi.hit(10, 10, 10, 10, 13, 13) && !CompactUi.hit(23, 10, 10, 10, 13, 13), "button edge excluded");
        var screen = (PatternPreviewScreen)unsafe.allocateInstance(PatternPreviewScreen.class); screen.width = 320; screen.height = 240;
        var height = PatternPreviewScreen.class.getDeclaredMethod("rowHeight", PatternEncoder.Entry.class); height.setAccessible(true);
        check((Integer)height.invoke(screen, craft) >= 74 && (Integer)height.invoke(screen, process) >= 52, "grid and all merged inputs fit row");
        System.out.println("PASS actual slot selection, manual quantities, merged inputs, ignored outputs, 3x3 positions, NBT identity, UI bounds");
    }
}

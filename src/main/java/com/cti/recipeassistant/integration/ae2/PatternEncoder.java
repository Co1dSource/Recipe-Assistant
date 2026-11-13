package com.cti.recipeassistant.integration.ae2;

import com.cti.recipeassistant.client.operation.*;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.*;
import appeng.core.definitions.AEItems;
import appeng.core.sync.network.NetworkHandler;
import appeng.core.sync.packets.InventoryActionPacket;
import appeng.crafting.pattern.*;
import appeng.helpers.InventoryAction;
import appeng.integration.modules.jei.GenericEntryStackHelper;
import appeng.integration.modules.jei.transfer.EncodePatternTransferHandler;
import appeng.menu.AutoCraftingMenu;
import appeng.menu.SlotSemantics;
import appeng.menu.me.items.PatternEncodingTermMenu;
import appeng.parts.encoding.EncodingMode;
import com.cti.recipeassistant.RecipeAssistant;
import com.cti.recipeassistant.client.gui.RecipeTreeScreen;
import com.cti.recipeassistant.client.jei.TreeJeiPlugin;
import com.cti.recipeassistant.recipe.*;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.*;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;
import java.util.*;
import java.util.stream.Stream;

public final class PatternEncoder {
    record Substitutions(boolean items, boolean fluids) {
        boolean matches(PatternEncodingTermMenu menu) {
            return items == menu.isSubstitute() && fluids == menu.isSubstituteFluids();
        }
        void request(PatternEncodingTermMenu menu) {
            if (items != menu.isSubstitute()) menu.setSubstitute(items);
            if (fluids != menu.isSubstituteFluids()) menu.setSubstituteFluids(fluids);
        }
        boolean matches(AECraftingPattern pattern) {
            return items == pattern.canSubstitute() && fluids == pattern.canSubstituteFluids();
        }
    }

    public static final class Entry {
        public final RecipeNode node;
        public final IRecipeLayoutDrawable<?> layout;
        public final boolean crafting;
        public final Map<Integer, Long> amounts = new HashMap<>();
        private final Map<Integer, ItemStack> edits;
        private final List<GenericStack> defaultInputs;
        boolean editable;
        Substitutions substitutions;
        public String problem;
        Entry(RecipeNode node, IRecipeLayoutDrawable<?> layout, RecipePreferences preferences) {
            this(node, layout, preferences, Map.of());
        }
        Entry(RecipeNode node, IRecipeLayoutDrawable<?> layout, RecipePreferences preferences, Map<Integer, ItemStack> edits) {
            this.node = node; this.layout = layout;
            this.edits = Map.copyOf(edits);
            crafting = layout != null && layout.getRecipe() instanceof CraftingRecipe;
            if (!crafting) for (int i = 0; i < node.selected.inputs().size(); i++) {
                String saved = preferences.get(amountKey(i));
                if (saved.matches("[1-9][0-9]{0,9}")) {
                    long value = Long.parseLong(saved);
                    if (value <= Integer.MAX_VALUE) amounts.put(i, value);
                }
            }
            defaultInputs = crafting || layout == null ? List.of() : defaultInputs();
        }
        public String amountKey(int input) { return node.amountKey(input); }
        String patternKey() { return PatternEncoder.patternKey(node); }
        public PatternEncodingSlots slots() { return new PatternEncodingSlots(layout.getRecipeSlotsView(), node, amounts); }
        public TreeIngredient output() { return node.selected.output(node.target.key()); }
        public List<GenericStack> inputs() {
            if (!edited()) return defaultInputs();
            var inputs = new ArrayList<GenericStack>();
            for (int i = 0, size = processingSize(); i < size; i++) {
                var input = processingInput(i);
                if (input != null) inputs.add(input);
            }
            return inputs;
        }
        private List<GenericStack> defaultInputs() {
            return GenericEntryStackHelper.ofInputs(slots()).stream().filter(candidates -> !candidates.isEmpty())
                    .map(candidates -> candidates.get(0)).toList();
        }
        boolean edited() { return !edits.isEmpty(); }
        int processingSize() {
            return Math.max(defaultInputs.size(), edits.keySet().stream().filter(i -> i >= 0).mapToInt(i -> i + 1).max().orElse(0));
        }
        int processingOutputSize() {
            return Math.max(1, edits.keySet().stream().filter(i -> i < 0).mapToInt(i -> -i).max().orElse(0));
        }
        static int outputEditKey(int slot) { return ~slot; }
        GenericStack processingOutput(int slot) {
            var edit = edits.get(outputEditKey(slot));
            return edit != null ? GenericStack.fromItemStack(edit)
                    : slot == 0 ? GenericEntryStackHelper.ingredientToStack(output().typed()) : null;
        }
        int recipeInputSlot(int cell) {
            for (int slot = 0; slot < node.selected.inputs().size(); slot++)
                if (!node.selected.inputs().get(slot).isEmpty() && cell-- == 0) return slot;
            return -1;
        }
        GenericStack defaultInput(int slot) { return slot < defaultInputs.size() ? defaultInputs.get(slot) : null; }
        GenericStack processingInput(int slot) {
            return edits.containsKey(slot) ? GenericStack.fromItemStack(edits.get(slot)) : defaultInput(slot);
        }
        ItemStack craftingInput(int slot) {
            var edited = edits.get(slot);
            if (edited != null) return edited;
            return node.selected.inputs().get(slot).isEmpty() ? ItemStack.EMPTY
                    : (ItemStack) node.selectedInput(slot).typed().getIngredient();
        }
        GenericStack input(int slot) {
            return crafting ? GenericStack.fromItemStack(craftingInput(slot)) : processingInput(slot);
        }
    }
    public record Preview(PatternEncodingTermMenu menu, Screen terminal, TreeIngredient target,
                          List<Entry> entries, List<PatternPlanner.Issue> issues, RecipePreferences preferences,
                          RecipeNode root, JeiRecipeIndex index) {
        public List<String> problems() { return issues.stream().map(PatternPlanner.Issue::message).toList(); }
    }
    private enum State { WAIT_SUBSTITUTION, NEXT, REFILL, TRANSFER, ENCODE, PICKUP, DEPOSIT, PAUSED }
    private static Preview task;
    private static State state, resume;
    private static int completed, ticks, stateId, destination, beforeBlank, beforeSupply;
    private static Slot blank, output;
    private static List<Slot> playerSlots;
    private static ItemStack encoded = ItemStack.EMPTY, beforeDestination = ItemStack.EMPTY;
    private static boolean cancelling;
    private static String message = "";
    private PatternEncoder() {}
    public static boolean busy() { return task != null; }

    public static void preview(ClientRecipeOperation<?> operation) {
        if (busy() || PatternDistributor.busy() || CraftExecutor.busy() || SingleRecipeTransfer.busy()) return;
        Minecraft.getInstance().setScreen(new PatternPreviewScreen(operation));
    }

    public static Preview prepare(ClientRecipeOperation<?> operation, RecipeNode root, JeiRecipeIndex index,
                                   RecipePreferences preferences, Set<String> removed) {
        return prepare(operation, root, index, preferences, removed, Map.of());
    }
    static Preview prepare(ClientRecipeOperation<?> operation, RecipeNode root, JeiRecipeIndex index,
                           RecipePreferences preferences, Set<String> removed, Map<String, Map<Integer, ItemStack>> edits) {
        var screen = (AbstractContainerScreen<?>) operation.parentScreen();
        var menu = (PatternEncodingTermMenu) screen.getMenu();
        var plan = PatternPlanner.planEncoding(root);
        var problems = new ArrayList<>(plan.issues());
        var entries = new ArrayList<Entry>();
        var substitutions = new Substitutions(menu.isSubstitute(), menu.isSubstituteFluids());
        for (var node : plan.steps()) {
            if (removed.contains(patternKey(node))) continue;
            var entry = new Entry(node, index.layout(node.selected.id()), preferences, edits.getOrDefault(patternKey(node), Map.of()));
            entry.substitutions = substitutions;
            entry.problem = validate(entry, menu);
            if (entry.problem != null) problems.add(new PatternPlanner.Issue(node, PatternPlanner.ProblemType.UNSUPPORTED,
                    node.target.name() + "：" + entry.problem, node.target.name()));
            entries.add(entry);
        }
        return new Preview(menu, screen, root.target, List.copyOf(entries), List.copyOf(problems), preferences, root, index);
    }

    static String patternKey(RecipeNode node) {
        return node.selected.id() + "|" + node.target.key();
    }

    static boolean canChooseRecipe(RecipeNode trial, IRecipeLayoutDrawable<?> layout, PatternEncodingTermMenu menu) {
        return trial.depth < 64 && !trial.cycle && layout != null && handler(layout, menu) != null
                && (!(layout.getRecipe() instanceof CraftingRecipe recipe) || recipe.canCraftInDimensions(3, 3));
    }

    private static String validate(Entry entry, PatternEncodingTermMenu menu) {
        if (entry.layout == null || handler(entry.layout, menu) == null) return "没有 AE2 编码处理器，不支持此配方";
        var node = entry.node;
        for (int i = 0; i < node.selected.inputs().size(); i++) if (!node.selected.inputs().get(i).isEmpty()) {
            var selected = node.selectedInput(i);
            if (GenericEntryStackHelper.ingredientToStack(selected.typed()) == null) return "AE2 不支持输入类型：" + selected.name();
            if (entry.amounts.containsKey(i) && !(selected.typed().getIngredient() instanceof ItemStack)
                    && !(selected.typed().getIngredient() instanceof net.minecraftforge.fluids.FluidStack))
                return "此输入类型没有可用的手动数量接口：" + selected.name();
            if (entry.crafting && (!(selected.typed().getIngredient() instanceof ItemStack) || selected.amount() != 1))
                return "工作台输入必须为单件物品";
        }
        if (GenericEntryStackHelper.ingredientToStack(entry.output().typed()) == null) return "AE2 不支持此目标类型";
        if (entry.crafting) {
            if (node.selected.inputs().size() != 9 || !((CraftingRecipe) entry.layout.getRecipe()).canCraftInDimensions(3, 3))
                return "工作台布局不是普通 3×3 配方";
            entry.editable = true;
            var grid = new TransientCraftingContainer(new AutoCraftingMenu(), 3, 3);
            for (int i = 0; i < 9; i++) grid.setItem(i, entry.craftingInput(i).copy());
            var recipe = (CraftingRecipe) entry.layout.getRecipe();
            if (!recipe.matches(grid, Minecraft.getInstance().level)
                    || !(entry.output().typed().getIngredient() instanceof ItemStack expected)
                    || !ItemStack.matches(recipe.assemble(grid, Minecraft.getInstance().level.registryAccess()), expected)) return "所选工作台原料与目标输出不匹配";
        } else {
            entry.editable = entry.processingSize() <= menu.getProcessingInputSlots().length
                    && entry.processingOutputSize() <= menu.getProcessingOutputSlots().length;
            if (!entry.editable || entry.inputs().isEmpty() || entry.processingOutput(0) == null)
                return "处理样板缺少输入或主要产物，或超过终端槽位上限";
        }
        return null;
    }

    @SuppressWarnings("unchecked") // The actual AE2 handler accepts Object, including machine recipe displays.
    private static IRecipeTransferHandler<PatternEncodingTermMenu, Object> handler(IRecipeLayoutDrawable<?> layout, PatternEncodingTermMenu menu) {
        var found = TreeJeiPlugin.runtime.getRecipeTransferManager().getRecipeTransferHandler(menu, layout.getRecipeCategory());
        return found.isPresent() && found.get() instanceof EncodePatternTransferHandler<?>
                ? (IRecipeTransferHandler<PatternEncodingTermMenu, Object>) (IRecipeTransferHandler<?, ?>) found.get() : null;
    }

    public static String start(Preview preview) {
        var mc = Minecraft.getInstance();
        if (busy() || PatternDistributor.busy() || CraftExecutor.busy() || SingleRecipeTransfer.busy()) return "已有物品操作正在执行";
        if (mc.player == null || mc.player.containerMenu != preview.menu || !preview.menu.isValidMenu()
                || !preview.menu.isPowered() || preview.menu.getClientRepo() == null) return "编码终端已失效或尚未同步";
        if (!preview.issues.isEmpty()) return "请先解决预览中的问题";
        if (!preview.menu.getCarried().isEmpty()) return "请先放下光标物品";
        for (var entry : preview.entries) {
            var problem = validate(entry, preview.menu);
            if (problem != null) return entry.node.target.name() + "：" + problem;
        }
        if (preview.entries.isEmpty()) return "没有需要编码的配方";
        blank = preview.menu.getSlots(SlotSemantics.BLANK_PATTERN).get(0);
        output = preview.menu.getSlots(SlotSemantics.ENCODED_PATTERN).get(0);
        if (output.hasItem()) return "请先取走编码输出槽中的已有物品";
        playerSlots = Stream.concat(preview.menu.getSlots(SlotSemantics.PLAYER_INVENTORY).stream(),
                preview.menu.getSlots(SlotSemantics.PLAYER_HOTBAR).stream())
                .filter(s -> !preview.menu.isPlayerInventorySlotLocked(s.getContainerSlot())).toList();
        task = preview; completed = 0; cancelling = false; encoded = ItemStack.EMPTY; ticks = 0;
        state = State.NEXT;
        mc.setScreen(preview.terminal);
        return null;
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !busy()) return;
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null || mc.player.containerMenu != task.menu
                || !task.menu.isValidMenu() || !task.menu.isPowered() || TreeJeiPlugin.runtime == null) {
            finish("终端已关闭、断网或失效，保留实际物品状态"); return;
        }
        if (state == State.WAIT_SUBSTITUTION) {
            if (cancelling) finish("已取消，未编码当前样板");
            else if (current().substitutions.matches(task.menu)) state = State.NEXT;
            else if (++ticks >= 100) finish("等待 AE2 替换设置同步超时，未编码当前样板");
            return;
        }
        if (state == State.PAUSED) {
            if (cancelling && task.menu.getCarried().isEmpty()) {
                if (resume == State.PICKUP && ItemStack.matches(output.getItem(), encoded)) {
                    finish("已取消，当前已编码样板保留在输出槽");
                } else if (encoded.isEmpty()) finish("已取消，保留已生成样板");
            }
            return;
        }
        if (state == State.NEXT) { next(); return; }
        if (state == State.REFILL) {
            if (task.menu.getStateId() != stateId && blank.getItem().getCount() > beforeBlank
                    && task.menu.getSlot(destination).getItem().getCount() < beforeSupply) { state = State.NEXT; return; }
        } else if (state == State.TRANSFER) {
            if (ready(current())) {
                if (cancelling) { finish("已取消，未编码当前配方"); return; }
                if (output.hasItem() || blank.getItem().isEmpty() || !task.menu.getCarried().isEmpty()) {
                    finish("编码前槽位变化，已停止"); return;
                }
                beforeBlank = blank.getItem().getCount(); stateId = task.menu.getStateId();
                task.menu.encode(); state = State.ENCODE; ticks = 0; return;
            }
        } else if (state == State.ENCODE) {
            if (output.hasItem() && task.menu.getStateId() != stateId && blank.getItem().getCount() == beforeBlank - 1) {
                encoded = output.getItem().copy();
                if (!validEncoded(current(), encoded)) { finish("实际样板与计划不符，保留输出槽供检查"); return; }
                PatternRecordStore.save(task.preferences, current(), encoded);
                if (!canReceive(encoded)) { pause(State.PICKUP, "请清理物品栏后继续，当前样板保留在输出槽"); return; }
                pickup(); return;
            }
        } else if (state == State.PICKUP) {
            if (output.getItem().isEmpty() && ItemStack.matches(task.menu.getCarried(), encoded)) { deposit(); return; }
        } else if (state == State.DEPOSIT) {
            var slot = task.menu.getSlot(destination).getItem();
            if (task.menu.getStateId() != stateId && task.menu.getCarried().isEmpty()
                    && ItemStack.isSameItemSameTags(slot, encoded) && slot.getCount() == beforeDestination.getCount() + 1) {
                completed++; encoded = ItemStack.EMPTY;
                if (cancelling || completed == task.entries.size()) finish(cancelling ? "已取消，已生成样板保留" : "全部样板已进入玩家物品栏");
                else state = State.NEXT;
                return;
            }
        }
        if (++ticks >= 100) finish("等待服务器同步超时，保留当前样板及光标，请手动检查");
    }

    private static Entry current() { return task.entries.get(completed); }
    private static void next() {
        if (cancelling) { finish("已取消，已生成样板保留"); return; }
        if (!output.getItem().isEmpty() || !task.menu.getCarried().isEmpty()) { finish("输出槽或光标被占用，已停止"); return; }
        if (blank.getItem().isEmpty()) {
            for (var slot : playerSlots) if (AEItems.BLANK_PATTERN.isSameAs(slot.getItem()) && slot.mayPickup(Minecraft.getInstance().player)) {
                destination = slot.index; beforeBlank = 0; beforeSupply = slot.getItem().getCount();
                click(slot.index, ClickType.QUICK_MOVE); state = State.REFILL; return;
            }
            pause(State.NEXT, "空白样板不足，还需 " + (task.entries.size() - completed) + " 张；补充后继续"); return;
        }
        if (!AEItems.BLANK_PATTERN.isSameAs(blank.getItem())) { finish("空白样板槽内容异常"); return; }
        var entry = current();
        if (entry.crafting && !entry.substitutions.matches(task.menu)) {
            entry.substitutions.request(task.menu);
            state = State.WAIT_SUBSTITUTION; ticks = 0; return;
        }
        // A pre-existing exact layout needs no transfer and no artificial sync delay.
        if (ready(entry)) { state = State.TRANSFER; ticks = 0; return; }
        if (entry.edited() || !entry.crafting) {
            fillEdited(entry);
            state = State.TRANSFER; ticks = 0; return;
        }
        var transfer = handler(entry.layout, task.menu);
        if (transfer == null) { finish("AE2 JEI 编码处理器已失效"); return; }
        Object recipe = entry.layout.getRecipe();
        mezz.jei.api.recipe.transfer.IRecipeTransferError error;
        try {
            error = transfer.transferRecipe(task.menu, recipe, entry.slots(), Minecraft.getInstance().player, false, true);
        } catch (RuntimeException e) {
            RecipeAssistant.LOGGER.warn("AE2 JEI encoding transfer failed for {}", entry.node.selected.id(), e);
            finish("AE2 填入失败：" + e.getClass().getSimpleName() + "：" + e.getMessage()); return;
        }
        if (error != null && !error.getType().allowsTransfer) { finish("AE2 拒绝填入当前配方，请检查原 JEI 提示"); return; }
        if (entry.crafting) {
            for (int i = 0; i < 9; i++) {
                setFilter(task.menu.getCraftingGridSlots()[i], entry.craftingInput(i));
            }
        }
        state = State.TRANSFER; ticks = 0;
    }
    private static void setFilter(Slot slot, ItemStack stack) {
        NetworkHandler.instance().sendToServer(new InventoryActionPacket(InventoryAction.SET_FILTER, slot.index, stack.copy()));
    }
    private static void fillEdited(Entry entry) {
        var menu = task.menu;
        menu.setMode(entry.crafting ? EncodingMode.CRAFTING : EncodingMode.PROCESSING);
        if (entry.crafting) {
            for (int i = 0; i < 9; i++) setFilter(menu.getCraftingGridSlots()[i], entry.craftingInput(i));
        } else {
            var inputs = menu.getProcessingInputSlots();
            var outputs = menu.getProcessingOutputSlots();
            for (int i = 0; i < inputs.length; i++) setFilter(inputs[i], GenericStack.wrapInItemStack(entry.processingInput(i)));
            for (int i = 0; i < outputs.length; i++) setFilter(outputs[i], GenericStack.wrapInItemStack(entry.processingOutput(i)));
        }
    }

    private static boolean ready(Entry entry) {
        var menu = task.menu;
        if (entry.crafting && !entry.substitutions.matches(menu)) return false;
        if (menu.getMode() != (entry.crafting ? EncodingMode.CRAFTING : EncodingMode.PROCESSING)) return false;
        if (entry.crafting) {
            for (int i = 0; i < 9; i++) {
                var expected = entry.craftingInput(i);
                if (!ItemStack.matches(menu.getCraftingGridSlots()[i].getItem(), expected)) return false;
            }
            return ItemStack.matches(menu.getSlots(SlotSemantics.CRAFTING_RESULT).get(0).getItem(),
                    (ItemStack) entry.output().typed().getIngredient());
        }
        var slots = menu.getProcessingInputSlots();
        for (int i = 0; i < slots.length; i++)
            if (!Objects.equals(GenericStack.fromItemStack(slots[i].getItem()), entry.processingInput(i))) return false;
        var outputs = menu.getProcessingOutputSlots();
        for (int i = 0; i < outputs.length; i++)
            if (!Objects.equals(GenericStack.fromItemStack(outputs[i].getItem()), entry.processingOutput(i))) return false;
        return true;
    }
    private static boolean validEncoded(Entry entry, ItemStack stack) {
        var decoded = PatternDetailsHelper.decodePattern(stack, Minecraft.getInstance().level);
        if (entry.crafting) {
            var target = GenericEntryStackHelper.ingredientToStack(entry.output().typed());
            if (!(decoded instanceof AECraftingPattern pattern) || !Objects.equals(decoded.getPrimaryOutput(), target)
                    || !entry.substitutions.matches(pattern)) return false;
            var actual = pattern.getSparseInputs();
            for (int i = 0; i < 9; i++) {
                var expected = entry.input(i);
                if (!Objects.equals(actual[i], expected)) return false;
            }
            return true;
        }
        if (!(decoded instanceof AEProcessingPattern processing)) return false;
        var actual = processing.getSparseInputs();
        for (int i = 0; i < Math.max(actual.length, entry.processingSize()); i++)
            if (!Objects.equals(i < actual.length ? actual[i] : null, entry.processingInput(i))) return false;
        var outputs = processing.getSparseOutputs();
        for (int i = 0; i < Math.max(outputs.length, entry.processingOutputSize()); i++)
            if (!Objects.equals(i < outputs.length ? outputs[i] : null, entry.processingOutput(i))) return false;
        return true;
    }


    private static void click(int slot, ClickType type) {
        stateId = task.menu.getStateId(); ticks = 0;
        Minecraft.getInstance().getConnection().send(new ServerboundContainerClickPacket(task.menu.containerId,
                stateId, slot, 0, type, task.menu.getCarried().copy(), new Int2ObjectOpenHashMap<>()));
    }
    private static void pickup() {
        if (!ItemStack.matches(output.getItem(), encoded) || !task.menu.getCarried().isEmpty()) { finish("待取样板或光标已变化"); return; }
        click(output.index, ClickType.PICKUP); state = State.PICKUP;
    }
    private static void deposit() {
        for (var slot : playerSlots) if (slot.mayPlace(encoded) && (slot.getItem().isEmpty()
                || ItemStack.isSameItemSameTags(slot.getItem(), encoded) && slot.getItem().getCount()
                < Math.min(slot.getMaxStackSize(encoded), encoded.getMaxStackSize()))) {
            destination = slot.index; beforeDestination = slot.getItem().copy();
            click(slot.index, ClickType.PICKUP); state = State.DEPOSIT; return;
        }
        pause(State.DEPOSIT, "请先腾出物品栏空间，当前样板保留在光标");
    }
    private static boolean canReceive(ItemStack stack) {
        return playerSlots.stream().anyMatch(s -> s.mayPlace(stack) && (s.getItem().isEmpty()
                || ItemStack.isSameItemSameTags(s.getItem(), stack) && s.getItem().getCount()
                < Math.min(s.getMaxStackSize(stack), stack.getMaxStackSize())));
    }
    private static void pause(State next, String reason) { resume = next; message = reason; state = State.PAUSED; }
    private static void continueTask() {
        if (resume == State.NEXT) { if (!task.menu.getCarried().isEmpty()) return; state = State.NEXT; }
        else if (resume == State.PICKUP && canReceive(encoded) && task.menu.getCarried().isEmpty()) pickup();
        else if (resume == State.DEPOSIT && ItemStack.matches(task.menu.getCarried(), encoded)) deposit();
    }
    private static void finish(String reason) {
        var mc = Minecraft.getInstance();
        String saveError = task.preferences.error;
        mc.gui.getChat().addMessage(Component.literal("批量编码：" + reason + "（" + completed + "/" + task.entries.size() + "）"
                + (saveError.isEmpty() ? "" : "；本地记录保存失败：" + saveError)));
        task = null;
    }

    @SubscribeEvent
    public static void render(ScreenEvent.Render.Post event) {
        if (!busy()) return;
        var screen = event.getScreen(); var mc = Minecraft.getInstance(); var graphics = event.getGuiGraphics();
        graphics.pose().pushPose(); graphics.pose().translate(0, 0, 700);
        graphics.fill(screen.width - 55, 0, screen.width, 21, 0xEE303030);
        graphics.drawString(mc.font, cancelling ? "正在取消" : "取消", screen.width - 48, 5, 0xFFE0A0);
        if (state == State.PAUSED) {
            graphics.fill(0, 21, screen.width, 42, 0xEE303030);
            graphics.drawString(mc.font, mc.font.plainSubstrByWidth(message, Math.max(20, screen.width - 65)), 5, 24, 0xFFFFFF);
            graphics.drawString(mc.font, "继续", screen.width - 48, 24, 0xFFE0A0);
        }
        graphics.pose().popPose();
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void mouse(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!busy()) return;
        if (event.getMouseY() < 42 && event.getMouseX() >= event.getScreen().width - 60) {
            if (event.getMouseY() < 21) cancelling = true;
            else if (state == State.PAUSED) continueTask();
            event.setCanceled(true); return;
        }
        if (state != State.PAUSED || resume == State.DEPOSIT) event.setCanceled(true);
        else if (resume == State.PICKUP && event.getScreen() instanceof AbstractContainerScreen<?> screen) {
            double x = event.getMouseX() - screen.getGuiLeft(), y = event.getMouseY() - screen.getGuiTop();
            // Only inventory cleanup is allowed while a generated pattern waits in the output.
            // This also prevents the terminal's native encode button from overwriting it.
            if (playerSlots.stream().noneMatch(s -> x >= s.x - 1 && x < s.x + 17 && y >= s.y - 1 && y < s.y + 17)) event.setCanceled(true);
        }
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void key(ScreenEvent.KeyPressed.Pre event) {
        if (!busy()) return;
        if (event.getKeyCode() == GLFW.GLFW_KEY_BACKSPACE) { cancelling = true; event.setCanceled(true); }
        else if (event.getKeyCode() != GLFW.GLFW_KEY_ESCAPE && (state != State.PAUSED || resume != State.NEXT)) event.setCanceled(true);
    }
}

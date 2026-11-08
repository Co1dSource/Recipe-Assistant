package com.cti.ctirecipeassistant.integration.ae2;

import com.cti.ctirecipeassistant.client.operation.*;
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
import com.cti.ctirecipeassistant.CtiRecipeAssistant;
import com.cti.ctirecipeassistant.client.gui.RecipeTreeScreen;
import com.cti.ctirecipeassistant.client.jei.TreeJeiPlugin;
import com.cti.ctirecipeassistant.recipe.*;
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
        Substitutions substitutions;
        public String problem;
        Entry(RecipeNode node, IRecipeLayoutDrawable<?> layout, RecipePreferences preferences) {
            this.node = node; this.layout = layout;
            crafting = layout != null && layout.getRecipe() instanceof CraftingRecipe;
            if (!crafting) for (int i = 0; i < node.selected.inputs().size(); i++) {
                String saved = preferences.get(amountKey(i));
                if (saved.matches("[1-9][0-9]{0,9}")) {
                    long value = Long.parseLong(saved);
                    if (value <= Integer.MAX_VALUE) amounts.put(i, value);
                }
            }
        }
        public String amountKey(int input) { return node.amountKey(input); }
        String patternKey() { return PatternEncoder.patternKey(node); }
        RecipeNode inputNode(AEKey key) {
            for (var child : node.children) {
                var stack = GenericEntryStackHelper.ingredientToStack(child.target.typed());
                if (stack != null && stack.what().equals(key)) return child;
            }
            return null;
        }
        public PatternEncodingSlots slots() { return new PatternEncodingSlots(layout.getRecipeSlotsView(), node, amounts); }
        public TreeIngredient output() { return node.selected.output(node.target.key()); }
        public List<GenericStack> inputs() {
            var merged = new LinkedHashMap<AEKey, Long>();
            for (var candidates : GenericEntryStackHelper.ofInputs(slots())) if (!candidates.isEmpty()) {
                var stack = candidates.get(0); merged.merge(stack.what(), stack.amount(), Long::sum);
            }
            return merged.entrySet().stream().map(e -> new GenericStack(e.getKey(), e.getValue())).toList();
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
        var screen = (AbstractContainerScreen<?>) operation.parentScreen();
        var menu = (PatternEncodingTermMenu) screen.getMenu();
        var plan = PatternPlanner.planEncoding(root);
        var problems = new ArrayList<>(plan.issues());
        var entries = new ArrayList<Entry>();
        var substitutions = new Substitutions(menu.isSubstitute(), menu.isSubstituteFluids());
        for (var node : plan.steps()) {
            if (removed.contains(patternKey(node))) continue;
            var entry = new Entry(node, index.layout(node.selected.id()), preferences);
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
            var grid = new CraftingContainer(new AutoCraftingMenu(), 3, 3);
            for (int i = 0; i < 9; i++) if (!node.selected.inputs().get(i).isEmpty())
                grid.setItem(i, ((ItemStack) node.selectedInput(i).typed().getIngredient()).copy());
            var recipe = (CraftingRecipe) entry.layout.getRecipe();
            if (!recipe.matches(grid, Minecraft.getInstance().level)
                    || !(entry.output().typed().getIngredient() instanceof ItemStack expected)
                    || !ItemStack.matches(recipe.assemble(grid), expected)) return "所选工作台原料与目标输出不匹配";
        } else if (entry.inputs().isEmpty() || entry.inputs().size() > menu.getProcessingInputSlots().length) {
            return "处理样板输入为空或超过终端槽位上限";
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
        var transfer = handler(entry.layout, task.menu);
        if (transfer == null) { finish("AE2 JEI 编码处理器已失效"); return; }
        Object recipe = entry.crafting ? entry.layout.getRecipe() : entry.slots();
        // Non-crafting displays intentionally use AE2's processing path, including stonecutting/smithing categories.
        mezz.jei.api.recipe.transfer.IRecipeTransferError error;
        try {
            error = transfer.transferRecipe(task.menu, recipe, entry.slots(), Minecraft.getInstance().player, false, true);
        } catch (RuntimeException e) {
            CtiRecipeAssistant.LOGGER.warn("AE2 JEI encoding transfer failed for {}", entry.node.selected.id(), e);
            finish("AE2 填入失败：" + e.getClass().getSimpleName() + "：" + e.getMessage()); return;
        }
        if (error != null && !error.getType().allowsTransfer) { finish("AE2 拒绝填入当前配方，请检查原 JEI 提示"); return; }
        if (entry.crafting) {
            for (int i = 0; i < 9; i++) {
                var stack = entry.node.selected.inputs().get(i).isEmpty() ? ItemStack.EMPTY
                        : ((ItemStack) entry.node.selectedInput(i).typed().getIngredient()).copy();
                NetworkHandler.instance().sendToServer(new InventoryActionPacket(InventoryAction.SET_FILTER,
                        task.menu.getCraftingGridSlots()[i].index, stack));
            }
        }
        state = State.TRANSFER; ticks = 0;
    }

    private static boolean ready(Entry entry) {
        var menu = task.menu;
        if (entry.crafting && !entry.substitutions.matches(menu)) return false;
        if (menu.getMode() != (entry.crafting ? EncodingMode.CRAFTING : EncodingMode.PROCESSING)) return false;
        if (entry.crafting) {
            for (int i = 0; i < 9; i++) {
                var expected = entry.node.selected.inputs().get(i).isEmpty() ? ItemStack.EMPTY
                        : (ItemStack) entry.node.selectedInput(i).typed().getIngredient();
                if (!ItemStack.matches(menu.getCraftingGridSlots()[i].getItem(), expected)) return false;
            }
            return ItemStack.matches(menu.getSlots(SlotSemantics.CRAFTING_RESULT).get(0).getItem(),
                    (ItemStack) entry.output().typed().getIngredient());
        }
        return match(menu.getProcessingInputSlots(), entry.inputs())
                && match(menu.getProcessingOutputSlots(), List.of(GenericEntryStackHelper.ingredientToStack(entry.output().typed())));
    }
    private static boolean match(Slot[] slots, List<GenericStack> expected) {
        for (int i = 0; i < slots.length; i++) if (!Objects.equals(GenericStack.fromItemStack(slots[i].getItem()),
                i < expected.size() ? expected.get(i) : null)) return false;
        return true;
    }
    private static boolean validEncoded(Entry entry, ItemStack stack) {
        var decoded = PatternDetailsHelper.decodePattern(stack, Minecraft.getInstance().level);
        var target = GenericEntryStackHelper.ingredientToStack(entry.output().typed());
        if (decoded == null || !Objects.equals(decoded.getPrimaryOutput(), target)) return false;
        if (entry.crafting) {
            if (!(decoded instanceof AECraftingPattern pattern) || !entry.substitutions.matches(pattern)) return false;
            var actual = pattern.getSparseInputs();
            for (int i = 0; i < 9; i++) {
                var expected = entry.node.selected.inputs().get(i).isEmpty() ? null
                        : GenericEntryStackHelper.ingredientToStack(entry.node.selectedInput(i).typed());
                if (!Objects.equals(actual[i], expected)) return false;
            }
            return true;
        }
        if (!(decoded instanceof AEProcessingPattern processing)) return false;
        var inputs = Arrays.stream(processing.getSparseInputs()).filter(Objects::nonNull).toList();
        return inputs.equals(entry.inputs()) && decoded.getOutputs().length == 1;
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
        var screen = event.getScreen(); var mc = Minecraft.getInstance(); var pose = event.getPoseStack();
        pose.pushPose(); pose.translate(0, 0, 700);
        Screen.fill(pose, 0, 0, screen.width, 42, 0xEE303030);
        String progress = current().node.target.name() + "（" + current().node.selected.machine() + "） 已完成 "
                + completed + "/" + task.entries.size() + " 剩余 " + (task.entries.size() - completed);
        Screen.drawString(pose, mc.font, mc.font.plainSubstrByWidth(progress, Math.max(20, screen.width - 65)), 5, 5, 0xFFFFFF);
        Screen.drawString(pose, mc.font, cancelling ? "正在取消" : "取消", screen.width - 48, 5, 0xFFE0A0);
        String status = state == State.PAUSED ? message : "等待同步";
        Screen.drawString(pose, mc.font, mc.font.plainSubstrByWidth(status, Math.max(20, screen.width - 65)), 5, 24, 0xFFFFFF);
        if (state == State.PAUSED) Screen.drawString(pose, mc.font, "继续", screen.width - 48, 24, 0xFFE0A0);
        pose.popPose();
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

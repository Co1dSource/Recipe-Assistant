package com.cti.ctirecipeassistant.integration.ae2;

import com.cti.ctirecipeassistant.client.operation.*;
import appeng.client.gui.me.patternaccess.PatternAccessTermScreen;
import appeng.client.gui.me.patternaccess.PatternContainerRecord;
import appeng.core.definitions.AEItems;
import appeng.menu.SlotSemantics;
import com.cti.ctirecipeassistant.integration.ae2.mixin.PatternAccessTermAccessor;
import com.cti.ctirecipeassistant.recipe.RecipePreferences;
import com.google.gson.JsonObject;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Stream;

/** Read-only planning from the terminal's synchronized inventories and local encoding records. */
public final class PatternDistribution {
    public record Provider(PatternContainerRecord record, String key, String name, String type, int free) {
        public long id() { return record.getServerId(); }
    }
    public static final class Row {
        public final ItemStack pattern;
        public final List<JsonObject> records;
        public final int quantity;
        public final String content;
        public int category;
        public Provider provider;
        public String reason = "";
        public int upload, duplicate, existing;
        public String confirmedDuplicate = "";
        Row(ItemStack pattern, List<JsonObject> records, int quantity) {
            this.pattern = pattern.copy(); this.pattern.setCount(1);
            this.records = records; this.quantity = quantity; content = PatternRecordStore.content(pattern);
            category = records.size() == 1 ? 0 : -1;
        }
        public JsonObject metadata() { return category < 0 ? null : records.get(category); }
        public String machine() { return category < 0 ? "请选择配方类别" : metadata().get("machine").getAsString(); }
        public String route() { return metadata().get("categoryUid").getAsString() + "|" + metadata().get("type").getAsString(); }
    }
    private PatternDistribution() {}
    public static Provider provider(PatternContainerRecord record) {
        var group = record.getGroup();
        String identity = (group.icon() == null ? "" : PatternRecordStore.content(group.icon().toStack()))
                + "|" + group.name().getString() + "|" + group.tooltip().stream().map(c -> c.getString()).toList();
        String key = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
        int free = 0, size = record.getInventory().size();
        for (int i = 0; i < size; i++) if (record.getInventory().getStackInSlot(i).isEmpty()) free++;
        return new Provider(record, key, group.name().getString(),
                size == 36 ? "扩展供应器 36槽" : size == 9 ? "普通供应器 9槽" : "供应器 " + size + "槽", free);
    }
    public static List<Provider> providers(PatternAccessTermScreen<?> screen) {
        return ((PatternAccessTermAccessor) screen).cti$getProviders().values().stream()
                .filter(record -> Ae2Integration.supportsProvider(record.getInventory().size()))
                .map(PatternDistribution::provider).sorted(Comparator.comparing(Provider::name).thenComparingLong(Provider::id)).toList();
    }
    public static List<Slot> playerSlots(PatternAccessTermScreen<?> screen) {
        var menu = screen.getMenu();
        return Stream.concat(menu.getSlots(SlotSemantics.PLAYER_INVENTORY).stream(),
                        menu.getSlots(SlotSemantics.PLAYER_HOTBAR).stream())
                .filter(s -> !menu.isPlayerInventorySlotLocked(s.getContainerSlot())).toList();
    }
    public static List<Row> rows(PatternAccessTermScreen<?> screen, RecipePreferences preferences) {
        var stacks = new LinkedHashMap<String, ItemStack>();
        var counts = new LinkedHashMap<String, Integer>();
        for (var slot : playerSlots(screen)) {
            var stack = slot.getItem();
            if (!AEItems.CRAFTING_PATTERN.isSameAs(stack) && !AEItems.PROCESSING_PATTERN.isSameAs(stack)) continue;
            String key = PatternRecordStore.content(stack);
            stacks.putIfAbsent(key, stack); counts.merge(key, stack.getCount(), Integer::sum);
        }
        var records = PatternRecordStore.records(preferences);
        var result = new ArrayList<Row>();
        for (var entry : stacks.entrySet()) {
            var unique = new LinkedHashMap<String, JsonObject>();
            for (var metadata : records) if (entry.getKey().equals(metadata.get("patternContent").getAsString()))
                unique.putIfAbsent(metadata.get("categoryUid").getAsString() + "|" + metadata.get("type").getAsString(), metadata);
            result.add(new Row(entry.getValue(), List.copyOf(unique.values()), counts.get(entry.getKey())));
        }
        return result;
    }
    public static boolean matches(JsonObject metadata, Provider provider) {
        var group = provider.record.getGroup();
        if (metadata.get("type").getAsString().equals("Crafting") && group.icon() != null
                && group.icon().getId().toString().equals("ae2:molecular_assembler")) return true;
        String machine = metadata.get("machine").getAsString();
        return machine.equalsIgnoreCase(provider.name)
                || group.tooltip().stream().anyMatch(line -> line.getString().equalsIgnoreCase(machine));
    }
    public static boolean contains(Provider provider, ItemStack pattern) {
        var inv = provider.record.getInventory();
        for (int i = 0; i < inv.size(); i++) if (ItemStack.isSameItemSameTags(inv.getStackInSlot(i), pattern)) return true;
        return false;
    }
    public static void plan(List<Row> rows, List<Provider> providers, RecipePreferences preferences,
                            Map<String, Long> confirmed) {
        var reserved = new HashMap<Long, Integer>();
        var seen = new HashSet<String>();
        for (var row : rows) {
            row.provider = null; row.upload = 0; row.duplicate = 0; row.existing = 0;
            if (row.records.isEmpty()) { row.reason = "没有本地编码记录，未分配"; continue; }
            if (row.category < 0) { row.reason = "同内容对应多个配方类别，请选择"; continue; }
            var metadata = row.metadata();
            String binding = preferences.get("provider-group|" + row.route());
            var candidates = providers.stream().filter(p -> binding.isEmpty() ? matches(metadata, p) : binding.equals(p.key)).toList();
            Long chosen = confirmed.get(row.route());
            if (chosen != null) row.provider = providers.stream().filter(p -> p.id() == chosen && p.key.equals(binding)).findFirst().orElse(null);
            else if (candidates.size() == 1) row.provider = candidates.get(0);
            if (row.provider == null) { row.reason = candidates.isEmpty() ? "无匹配供应器，未分配" : "多个供应器，请选择"; continue; }
            String unique = row.route() + "|" + row.provider.id() + "|" + row.content;
            if (contains(row.provider, row.pattern)) {
                if (row.records.size() > 1 && !row.confirmedDuplicate.equals(row.route() + "|" + row.provider.id())) {
                    row.reason = "已有相同内容，但类别不明，请确认重复类别"; continue;
                }
                row.existing = 1;
                row.duplicate = row.quantity; row.reason = "目标已有相同类别的完整样板，跳过"; continue;
            }
            if (!seen.add(unique)) {
                row.duplicate = row.quantity; row.reason = "当前队列已包含相同类别样板，跳过"; continue;
            }
            int available = row.provider.free - reserved.getOrDefault(row.provider.id(), 0);
            if (available < 1) { row.reason = "需要 1 槽，剩余 0；保留 " + row.quantity + " 张"; continue; }
            row.upload = 1; row.duplicate = row.quantity - 1;
            reserved.merge(row.provider.id(), 1, Integer::sum);
            row.reason = row.duplicate > 0 ? "上传 1 张，多余 " + row.duplicate + " 张留在背包" : "待上传";
        }
    }
    public static int emptySlot(Provider provider) {
        var inv = provider.record.getInventory();
        for (int i = 0; i < inv.size(); i++) if (inv.getStackInSlot(i).isEmpty()) return i;
        return -1;
    }
}

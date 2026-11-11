package com.cti.recipeassistant.integration.ae2;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;
import appeng.client.gui.me.patternaccess.PatternAccessTermScreen;
import appeng.client.gui.me.patternaccess.PatternContainerRecord;
import appeng.core.definitions.AEBlocks;
import appeng.menu.SlotSemantics;
import com.cti.recipeassistant.RecipeAssistant;
import com.cti.recipeassistant.client.jei.TreeJeiPlugin;
import com.cti.recipeassistant.integration.ae2.mixin.PatternAccessTermAccessor;
import com.cti.recipeassistant.recipe.JeiRecipeIndex;
import com.cti.recipeassistant.recipe.RecipePreferences;
import com.google.gson.*;
import mezz.jei.api.constants.VanillaTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Read-only contents and current native terminal records; no dependency on encoding history. */
public final class PatternDistribution {
    private static final Route CRAFTING_ROUTE = new Route("minecraft:crafting", "分子装配室");
    public record Provider(PatternContainerRecord record, String key, String name, String type, int free) {
        public long id() { return record.getServerId(); }
    }
    public record Route(String category, String machine) {}
    public record Choice(Provider provider, Route route) {}
    public static final class Row {
        public final ItemStack pattern;
        public final PatternContents contents;
        public final int quantity;
        public final String content;
        public List<Route> routes;
        public Route route;
        public String savedGroup = "", reason = "";
        public Choice choice;
        Row(ItemStack pattern, PatternContents contents, List<Route> routes, int quantity) {
            this.pattern = pattern.copy(); this.pattern.setCount(1); this.contents = contents;
            this.routes = routes; this.quantity = quantity; content = PatternRecordStore.content(pattern);
        }
        public String machine() { return route != null ? route.machine() : routes.size() == 1 ? routes.get(0).machine() : ""; }
        public String name() { return contents == null ? pattern.getHoverName().getString() : contents.name(); }
        public Provider provider() { return choice.provider(); }
        public String routeKey(Route route) { return contents.kind() + "|" + route.category(); }
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
        var stacks = new LinkedHashMap<String, ItemStack>(); var counts = new HashMap<String, Integer>();
        for (var slot : playerSlots(screen)) {
            var stack = slot.getItem();
            if (!slot.mayPickup(net.minecraft.client.Minecraft.getInstance().player) || !PatternDetailsHelper.isEncodedPattern(stack)) continue;
            String key = PatternRecordStore.content(stack); stacks.putIfAbsent(key, stack); counts.merge(key, stack.getCount(), Integer::sum);
        }
        List<JsonObject> records;
        try { records = PatternRecordStore.records(preferences); }
        catch (JsonParseException error) {
            RecipeAssistant.LOGGER.warn("Invalid local pattern records; matching decoded contents instead", error); records = List.of();
        }
        var index = new JeiRecipeIndex(TreeJeiPlugin.runtime);
        var result = new ArrayList<Row>();
        for (var entry : stacks.entrySet()) {
            var contents = PatternContents.decode(entry.getValue()); var routes = new LinkedHashMap<String, Route>();
            if (contents != null) for (var record : records) if (entry.getKey().equals(record.get("patternContent").getAsString())
                    && record.get("type").getAsString().equals(contents.kind() == PatternContents.Kind.CRAFTING ? "Crafting" : "Processing")) {
                var route = new Route(record.get("categoryUid").getAsString(), record.get("machine").getAsString()); routes.put(route.category(), route);
            }
            var row = new Row(entry.getValue(), contents, List.copyOf(routes.values()), counts.get(entry.getKey()));
            if (contents == null) row.reason = "样板无法解码";
            else {
                if (contents.kind() == PatternContents.Kind.CRAFTING) row.routes = List.of(CRAFTING_ROUTE);
                else {
                    loadChoice(row, preferences);
                    if (row.route == null && row.routes.isEmpty()) row.routes = PatternRecipeMatcher.find(contents, index);
                }
            }
            append(result, row);
        }
        return result;
    }
    static void append(List<Row> result, Row row) {
        // Identical physical copies may belong to distinct recorded machines. Ask per copy
        // while their category is ambiguous, rather than treating every extra copy as a duplicate.
        if (row.routes.size() > 1 && row.quantity > 1) {
            for (int i = 0; i < row.quantity; i++) result.add(new Row(row.pattern, row.contents, row.routes, 1));
        } else result.add(row);
    }
    private static void loadChoice(Row row, RecipePreferences preferences) {
        String saved = preferences.get("distribution-choice|" + row.contents.identity());
        if (saved.isEmpty()) return;
        try {
            var value = JsonParser.parseString(saved);
            if (!value.isJsonObject()) throw new JsonParseException("Choice is not an object");
            var data = value.getAsJsonObject();
            for (String key : List.of("category", "machine", "group")) if (!data.has(key)
                    || !data.get(key).isJsonPrimitive() || !data.getAsJsonPrimitive(key).isString()) throw new JsonParseException("Invalid choice " + key);
            row.route = new Route(data.get("category").getAsString(), data.get("machine").getAsString()); row.savedGroup = data.get("group").getAsString();
        } catch (JsonParseException error) { RecipeAssistant.LOGGER.warn("Invalid saved distribution choice; matching current pattern instead", error); }
    }
    static boolean assembler(Provider provider) {
        return AEBlocks.MOLECULAR_ASSEMBLER.isSameAs(provider.record().getGroup().icon());
    }
    private static Set<Item> machineItems(Route route) {
        var runtime = TreeJeiPlugin.runtime;
        var category = ResourceLocation.tryParse(route.category());
        if (runtime == null || category == null) return Set.of();
        var manager = runtime.getRecipeManager();
        return manager.getRecipeType(category)
                .map(type -> manager.createRecipeCatalystLookup(type).get(VanillaTypes.ITEM_STACK)
                        .map(ItemStack::getItem).collect(Collectors.toSet())).orElseGet(Set::of);
    }
    private static void chooseAssembler(Row row, List<Provider> providers) {
        Provider free = null;
        boolean found = false;
        for (var provider : providers) {
            if (!assembler(provider)) continue;
            found = true;
            if (contains(provider, row.contents)) {
                row.choice = new Choice(provider, CRAFTING_ROUTE); row.reason = ""; return;
            }
            if (free == null && provider.free() > 0) free = provider;
        }
        if (free != null) row.choice = new Choice(free, CRAFTING_ROUTE);
        row.reason = free != null ? "" : found ? "分子装配室没有空位" : "未找到分子装配室";
    }
    public static boolean contains(Provider provider, PatternContents contents) {
        var inventory = provider.record().getInventory();
        for (int i = 0; i < inventory.size(); i++) {
            var stack = inventory.getStackInSlot(i); if (stack.isEmpty()) continue;
            var other = PatternContents.decode(stack);
            if (other != null && contents.identity().equals(other.identity())) return true;
        }
        return false;
    }
    private static boolean usable(Row row, Provider provider) { return provider.free() > 0 || contains(provider, row.contents); }
    /** Returns only a decision menu when it is actually needed; row.choice is the automatic result. */
    public static List<Choice> resolve(Row row, List<Provider> providers, RecipePreferences preferences,
                                       Map<String, Choice> batch, boolean forceManual) {
        row.choice = null;
        if (row.contents == null) return List.of();
        if (row.contents.kind() == PatternContents.Kind.CRAFTING) {
            chooseAssembler(row, providers);
            return List.of();
        }
        var routes = row.route != null ? List.of(row.route) : row.routes;
        if (routes.isEmpty()) routes = List.of(new Route("manual:" + row.contents.identity(), "自定义样板"));
        var candidates = new ArrayList<Choice>();
        for (var route : routes) {
            boolean manual = route.category().startsWith("manual:");
            var machines = manual ? Set.<Item>of() : machineItems(route);
            for (var provider : providers) {
                var icon = provider.record().getGroup().icon();
                if (icon != null && (!assembler(provider) || row.contents.details() instanceof IMolecularAssemblerSupportedPattern)
                        && (manual || machines.contains(icon.getItem())))
                    candidates.add(new Choice(provider, route));
            }
        }
        var single = routes.size() == 1 ? routes.get(0) : null;
        var remembered = single == null ? null : batch.get("pattern|" + row.contents.identity());
        if (remembered == null && single != null) remembered = batch.get(row.routeKey(single));
        if (remembered != null) {
            var previous = remembered;
            var live = candidates.stream().map(Choice::provider)
                    .filter(p -> p.id() == previous.provider().id() && p.key().equals(previous.provider().key())).findFirst();
            if (live.isPresent() && usable(row, live.get()) && !forceManual) {
                row.choice = new Choice(live.get(), previous.route()); row.reason = ""; return List.of();
            }
            forceManual = true; row.reason = live.isEmpty() ? "原供应器已失效" : "供应器空间不足";
        }
        if (single != null && remembered == null) {
            String group = row.savedGroup.isEmpty() ? preferences.get("provider-group|" + single.category() + "|Processing") : row.savedGroup;
            if (!group.isEmpty()) {
                var saved = candidates.stream().map(Choice::provider).filter(p -> p.key().equals(group)).toList();
                if (saved.size() == 1 && usable(row, saved.get(0)) && !forceManual) {
                    row.choice = new Choice(saved.get(0), single); row.reason = ""; return List.of();
                }
                forceManual = true;
                if (row.reason.isEmpty()) row.reason = saved.size() == 1 ? "供应器空间不足" : "原分组需要重新选择";
            }
        }
        var available = candidates.stream().filter(c -> usable(row, c.provider())).toList();
        if (!forceManual && single != null && !single.category().startsWith("manual:") && available.size() == 1) {
            row.choice = available.get(0); row.reason = ""; return List.of();
        }
        if (row.reason.isEmpty()) row.reason = candidates.isEmpty() ? "未找到兼容的供应器" : available.isEmpty()
                ? "供应器空间不足" : "";
        return List.copyOf(candidates);
    }
    public static void remember(Row row, Choice choice, RecipePreferences preferences, Map<String, Choice> batch, boolean persist) {
        row.route = choice.route(); row.choice = choice;
        batch.put("pattern|" + row.contents.identity(), choice); batch.put(row.routeKey(choice.route()), choice);
        if (persist) {
            var data = new JsonObject(); data.addProperty("category", choice.route().category()); data.addProperty("machine", choice.route().machine());
            data.addProperty("group", choice.provider().key()); preferences.put("distribution-choice|" + row.contents.identity(), data.toString());
            row.savedGroup = choice.provider().key();
        }
    }
    public static int emptySlot(Provider provider) {
        var inventory = provider.record().getInventory();
        for (int i = 0; i < inventory.size(); i++) if (inventory.getStackInSlot(i).isEmpty()) return i;
        return -1;
    }
}

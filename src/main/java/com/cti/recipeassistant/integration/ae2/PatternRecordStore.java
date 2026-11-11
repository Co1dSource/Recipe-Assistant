package com.cti.recipeassistant.integration.ae2;

import com.cti.recipeassistant.client.operation.*;
import com.cti.recipeassistant.recipe.RecipePreferences;
import com.google.gson.*;
import net.minecraft.nbt.*;
import net.minecraft.world.item.ItemStack;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Local, world/server-scoped metadata. Never writes to the physical pattern. */
public final class PatternRecordStore {
    private PatternRecordStore() {}
    public static void save(RecipePreferences preferences, PatternEncoder.Entry entry, ItemStack pattern) {
        var data = new JsonObject();
        String content = content(pattern);
        String fingerprint = UUID.nameUUIDFromBytes(content.getBytes(StandardCharsets.UTF_8)).toString();
        data.addProperty("fingerprint", fingerprint);
        data.addProperty("patternContent", content);
        data.addProperty("categoryUid", entry.node.selected.category());
        data.addProperty("machine", entry.node.selected.machine());
        var primary = entry.crafting ? null : entry.processingOutput(0);
        data.addProperty("target", primary == null ? entry.node.target.key() : canonical(primary.what().toTagGeneric()));
        data.addProperty("targetName", primary == null ? entry.output().name() : primary.what().getDisplayName().getString());
        data.addProperty("outputAmount", primary == null ? entry.output().amount() : primary.amount());
        data.addProperty("type", entry.crafting ? "Crafting" : "Processing");
        var inputs = new JsonArray();
        if (entry.edited() || !entry.crafting) {
            for (int i = 0; i < (entry.crafting ? 9 : entry.processingSize()); i++) {
                var stack = entry.input(i);
                if (stack == null) continue;
                var input = new JsonObject();
                input.addProperty("slot", i);
                input.addProperty("ingredient", canonical(stack.what().toTagGeneric()));
                input.addProperty("name", stack.what().getDisplayName().getString());
                input.addProperty("amount", stack.amount());
                inputs.add(input);
            }
        } else for (int i = 0; i < entry.node.selected.inputs().size(); i++) if (!entry.node.selected.inputs().get(i).isEmpty()) {
            var ingredient = entry.node.selectedInput(i);
            var input = new JsonObject();
            input.addProperty("slot", i); input.addProperty("ingredient", ingredient.key());
            input.addProperty("name", ingredient.name());
            input.addProperty("amount", entry.amounts.getOrDefault(i, ingredient.amount()));
            inputs.add(input);
        }
        data.add("inputs", inputs);
        // Same content used by two machine categories remains two records.
        preferences.put("pattern-record|" + entry.node.selected.category() + "|" + fingerprint, data.toString());
    }
    public static String content(ItemStack pattern) {
        var single = pattern.copy(); single.setCount(1);
        return canonical(single.save(new CompoundTag()));
    }
    public static java.util.List<JsonObject> records(RecipePreferences preferences) {
        return preferences.values("pattern-record|").stream().map(value -> {
            var parsed = JsonParser.parseString(value);
            if (!parsed.isJsonObject()) throw new JsonParseException("样板记录不是对象");
            var data = parsed.getAsJsonObject();
            for (String key : java.util.List.of("patternContent", "categoryUid", "machine", "type"))
                if (!data.has(key) || !data.get(key).isJsonPrimitive() || !data.getAsJsonPrimitive(key).isString())
                    throw new JsonParseException("样板记录缺少字段：" + key);
            return data;
        }).toList();
    }
    static String canonical(Tag tag) {
        if (tag instanceof CompoundTag compound) return compound.getAllKeys().stream().sorted()
                .map(key -> new JsonPrimitive(key) + ":" + canonical(compound.get(key))).toList().toString();
        if (tag instanceof ListTag list) return list.stream().map(PatternRecordStore::canonical).toList().toString();
        return tag.toString();
    }
}

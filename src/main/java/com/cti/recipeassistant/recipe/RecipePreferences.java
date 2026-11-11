package com.cti.recipeassistant.recipe;

import com.cti.recipeassistant.RecipeAssistant;
import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.fml.loading.FMLPaths;
import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

public final class RecipePreferences {
    private static long revision;
    public static long revision() { return revision; }
    public static void treeChanged() { revision++; }
    private final Path file;
    private JsonObject data = new JsonObject();
    private boolean writable = true;
    private long readRevision = revision;
    public String error = "";

    public RecipePreferences() {
        Minecraft mc = Minecraft.getInstance();
        String scope = mc.getCurrentServer() != null ? "server:" + mc.getCurrentServer().ip
                : "world:" + mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        String suffix = "-preferences-" + UUID.nameUUIDFromBytes(scope.getBytes(StandardCharsets.UTF_8)) + ".json";
        Path config = FMLPaths.CONFIGDIR.get();
        file = config.resolve(RecipeAssistant.MOD_ID + suffix);
        // The previous mod ID is only used to read existing preferences until they are saved under the new ID.
        read(Files.notExists(file) ? config.resolve("ctirecipeassistant" + suffix) : file);
    }
    private void read(Path source) {
        if (Files.exists(source)) {
            try {
                var parsed = JsonParser.parseString(Files.readString(source));
                if (!parsed.isJsonObject()) throw new JsonParseException("Expected object");
                data = parsed.getAsJsonObject();
            } catch (IOException | JsonParseException e) {
                writable = false;
                error = "偏好读取失败，未覆盖原文件";
                RecipeAssistant.LOGGER.warn("Cannot read recipe preferences {}", source, e);
            }
        }
    }
    public String get(String key) {
        var value = data.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : "";
    }
    public java.util.List<String> values(String prefix) {
        return data.keySet().stream().filter(key -> key.startsWith(prefix)).map(this::get).toList();
    }
    public void put(String key, String value) {
        putAll(Map.of(key, value));
    }
    void putAll(Map<String, String> values) {
        if (values.isEmpty()) return;
        // Another tree or preview can save while this screen is retained.
        if (writable && readRevision != revision) read(file);
        readRevision = ++revision;
        values.forEach(data::addProperty);
        if (!writable) return;
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, new GsonBuilder().setPrettyPrinting().create().toJson(data));
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            error = "";
        } catch (IOException e) {
            error = "偏好保存失败，详见日志";
            RecipeAssistant.LOGGER.warn("Cannot save recipe preferences {}", file, e);
        }
    }
}

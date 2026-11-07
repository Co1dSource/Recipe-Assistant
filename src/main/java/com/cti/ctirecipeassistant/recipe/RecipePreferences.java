package com.cti.ctirecipeassistant.recipe;

import com.cti.ctirecipeassistant.CtiRecipeAssistant;
import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.fml.loading.FMLPaths;
import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class RecipePreferences {
    private static long revision;
    public static long revision() { return revision; }
    public static void treeChanged() { revision++; }
    private final Path file;
    private JsonObject data = new JsonObject();
    private boolean writable = true;
    public String error = "";

    public RecipePreferences() {
        Minecraft mc = Minecraft.getInstance();
        String scope = mc.getCurrentServer() != null ? "server:" + mc.getCurrentServer().ip
                : "world:" + mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        file = FMLPaths.CONFIGDIR.get().resolve("ctirecipeassistant-preferences-"
                + UUID.nameUUIDFromBytes(scope.getBytes(StandardCharsets.UTF_8)) + ".json");
        if (Files.exists(file)) {
            try {
                var parsed = JsonParser.parseString(Files.readString(file));
                if (!parsed.isJsonObject()) throw new JsonParseException("Expected object");
                data = parsed.getAsJsonObject();
            } catch (IOException | JsonParseException e) {
                writable = false;
                error = "偏好读取失败，未覆盖原文件";
                CtiRecipeAssistant.LOGGER.warn("Cannot read recipe preferences {}", file, e);
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
        revision++;
        data.addProperty(key, value);
        if (!writable) return;
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, new GsonBuilder().setPrettyPrinting().create().toJson(data));
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            error = "";
        } catch (IOException e) {
            error = "偏好保存失败，详见日志";
            CtiRecipeAssistant.LOGGER.warn("Cannot save recipe preferences {}", file, e);
        }
    }
}

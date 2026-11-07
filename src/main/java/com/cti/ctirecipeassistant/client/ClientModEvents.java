package com.cti.ctirecipeassistant.client;

import com.cti.ctirecipeassistant.CtiRecipeAssistant;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.common.util.Lazy;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = CtiRecipeAssistant.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD,
        value = Dist.CLIENT)
public final class ClientModEvents {
    public static final Lazy<KeyMapping> OPEN_RECIPE_TREE = Lazy.of(() -> new KeyMapping(
            "key.ctirecipeassistant.open_recipe_tree", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, "key.categories.ctirecipeassistant"));

    private ClientModEvents() {}

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(com.cti.ctirecipeassistant.integration.OptionalIntegrations::initialize);
    }

    @SubscribeEvent
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_RECIPE_TREE.get());
    }
}

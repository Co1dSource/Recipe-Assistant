package com.cti.ctirecipeassistant.client.jei;

import com.cti.ctirecipeassistant.CtiRecipeAssistant;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;

@JeiPlugin
public final class TreeJeiPlugin implements IModPlugin {
    public static IJeiRuntime runtime;

    @Override public ResourceLocation getPluginUid() {
        return new ResourceLocation("ctirecipeassistant", "tree");
    }

    @Override public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        if (net.minecraftforge.fml.ModList.get().isLoaded("ae2"))
            com.cti.ctirecipeassistant.integration.ae2.PatternPreviewScreen.registerGuiHandlers(registration);
    }
    @Override public void onRuntimeAvailable(IJeiRuntime value) {
        runtime = value;
        CtiRecipeAssistant.LOGGER.info("Recipe Assistant: JEI runtime available; recipe tree ready");
    }
    @Override public void onRuntimeUnavailable() {
        runtime = null;
        com.cti.ctirecipeassistant.client.operation.RecipeOperationDispatcher.clear();
    }
}

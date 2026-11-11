package com.cti.recipeassistant.integration.ae2.mixin;

import appeng.client.gui.me.crafting.CraftAmountScreen;
import appeng.client.gui.widgets.NumberEntryWidget;
import com.cti.recipeassistant.integration.ae2.Ae2Refill;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = CraftAmountScreen.class, remap = false)
public abstract class CraftAmountScreenMixin {
    @Shadow @Final private NumberEntryWidget amountToCraft;

    @Inject(method = "updateBeforeRender", at = @At("TAIL"))
    private void cti$prefill(CallbackInfo ci) {
        Ae2Refill.prefill((CraftAmountScreen) (Object) this, amountToCraft);
    }
}

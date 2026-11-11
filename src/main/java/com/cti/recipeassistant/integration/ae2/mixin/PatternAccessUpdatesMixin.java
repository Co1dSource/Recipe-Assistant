package com.cti.recipeassistant.integration.ae2.mixin;

import appeng.client.gui.me.patternaccess.PatternAccessTermScreen;
import com.cti.recipeassistant.integration.ae2.DistributionPreview;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Read-only notification after AE2 applies actual server updates. */
@Mixin(value = PatternAccessTermScreen.class, remap = false)
public abstract class PatternAccessUpdatesMixin {
    @Inject(method = {"postFullUpdate", "postIncrementalUpdate", "clear"}, at = @At("TAIL"))
    private void cti$updated(CallbackInfo ci) {
        DistributionPreview.updated((PatternAccessTermScreen<?>) (Object) this);
    }
}

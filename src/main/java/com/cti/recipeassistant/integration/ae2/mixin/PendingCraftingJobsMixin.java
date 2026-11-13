package com.cti.recipeassistant.integration.ae2.mixin;

import appeng.api.stacks.AEKey;
import appeng.client.gui.me.common.PendingCraftingJobs;
import appeng.core.sync.packets.CraftingJobStatusPacket.Status;
import com.cti.recipeassistant.integration.ae2.Ae2Refill;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

@Mixin(value = PendingCraftingJobs.class, remap = false)
public abstract class PendingCraftingJobsMixin {
    @Inject(method = "jobStatus(Ljava/util/UUID;Lappeng/api/stacks/AEKey;JJLappeng/core/sync/packets/CraftingJobStatusPacket$Status;)V", at = @At("TAIL"))
    private static void cti$trackMaterialOrder(UUID id, AEKey key, long amount, long remaining, Status status, CallbackInfo ci) {
        Ae2Refill.jobStatus(id, key, amount, status);
    }
}

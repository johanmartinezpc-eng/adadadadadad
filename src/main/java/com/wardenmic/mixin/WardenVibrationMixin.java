package com.wardenmic.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** El Warden ignora todas las vibraciones (pasos, bloques, saltos...). */
@Mixin(targets = "net.minecraft.world.entity.monster.warden.Warden$VibrationUser")
public abstract class WardenVibrationMixin {
    @Inject(method = "canReceiveVibration", at = @At("HEAD"), cancellable = true)
    private void wardenmic$ignoreVibrations(CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(false);
    }
}

package com.wardenmic.mixin;

import com.wardenmic.WardenMic;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * El Warden solo puede tener a un jugador como objetivo mientras ese jugador esté
 * "en persecución" por la barra de alerta. Esto anula olfato, cercanía, golpes, etc.
 */
@Mixin(Warden.class)
public abstract class WardenTargetMixin {
    @Inject(method = "canTargetEntity", at = @At("HEAD"), cancellable = true)
    private void wardenmic$onlyWhenChasing(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (entity instanceof Player player && !WardenMic.isChasing(player.getUUID())) {
            cir.setReturnValue(false);
        }
    }
}

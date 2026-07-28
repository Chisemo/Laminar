package com.izumi.laminar.mixin;

import com.izumi.laminar.FlowFieldHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.sensing.Sensing;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Sensing.class)
public abstract class SensingMixin {
   @Shadow
   @Final
   private Mob mob;

   @Inject(method = "hasLineOfSight", at = @At("HEAD"), cancellable = true)
   private void onHasLineOfSight(Entity entity, CallbackInfoReturnable<Boolean> cir) {
      if (FlowFieldHelper.isSupportedMob(this.mob)) {
         if (entity instanceof Player targetPlayer) {
            Boolean visible = FlowFieldHelper.getGpuVisibility(targetPlayer.getUUID(), this.mob.getUUID());
            if (Boolean.TRUE.equals(visible)) {
               cir.setReturnValue(true);
            }
         }
      }
   }
}

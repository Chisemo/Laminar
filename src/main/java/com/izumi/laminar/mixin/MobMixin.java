package com.izumi.laminar.mixin;

import com.izumi.laminar.FlowFieldHelper;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Mob.class, priority = 9999)
public abstract class MobMixin {
   @Inject(method = "setTarget", at = @At("HEAD"))
   private void onSetTarget(LivingEntity target, CallbackInfo ci) {
      Mob self = (Mob)(Object)this;
      if (FlowFieldHelper.isSupportedMob(self)) {
         if (target instanceof Player player) {
            FlowFieldHelper.registerActiveTarget(player.getUUID(), self.getUUID());
         } else {
            FlowFieldHelper.unregisterActiveTarget(self.getUUID());
         }
      }
   }

   @Inject(method = "setAggressive", at = @At("HEAD"), cancellable = true)
   private void onSetAggressive(boolean aggressive, CallbackInfo ci) {
      Mob self = (Mob)(Object)this;
      if (aggressive && self.getTarget() == null && self instanceof Zombie) {
         ci.cancel();
      }
   }
}

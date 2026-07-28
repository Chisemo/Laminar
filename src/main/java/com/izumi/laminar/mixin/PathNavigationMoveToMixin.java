package com.izumi.laminar.mixin;

import com.izumi.laminar.FlowFieldHelper;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.pathfinder.Path;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PathNavigation.class)
public abstract class PathNavigationMoveToMixin {
   @Shadow
   protected Mob mob;
   @Shadow
   protected double speedModifier;
   @Shadow
   protected Path path;

   @Shadow
   public abstract boolean moveTo(double var1, double var3, double var5, double var7);

   @Shadow
   public abstract boolean moveTo(Entity var1, double var2);

   @Inject(method = "tick", at = @At("HEAD"))
   private void onTick(CallbackInfo ci) {
      if (FlowFieldHelper.isSupportedMob(this.mob)) {
         if (this.mob.getTarget() instanceof Player targetPlayer) {
            boolean pathAlmostDone = false;
            if (this.path != null && !this.path.isDone()) {
               int remainingNodes = this.path.getNodeCount() - this.path.getNextNodeIndex();
               if (remainingNodes <= 2) {
                  pathAlmostDone = true;
               }
            } else {
               pathAlmostDone = true;
            }

            if (pathAlmostDone) {
               this.moveTo(targetPlayer, this.speedModifier);
            }
         }
      }
   }

   @Inject(method = "moveTo(Lnet/minecraft/world/entity/Entity;D)Z", at = @At("HEAD"), cancellable = true)
   private void onMoveToEntity(Entity entityTarget, double speed, CallbackInfoReturnable<Boolean> cir) {
      if (FlowFieldHelper.isSupportedMob(this.mob)) {
         if (entityTarget instanceof Player targetPlayer) {
            if ((!this.mob.isInLava() || this.mob.fireImmune()) && !this.mob.isHolding(Items.TRIDENT) && !this.mob.isHolding(Items.FISHING_ROD)) {
               BlockPos currentPos = this.mob.blockPosition();
               BlockPos targetPos = targetPlayer.blockPosition();
               if (!currentPos.closerThan(targetPos, 2.5)) {
                  UUID playerUUID = targetPlayer.getUUID();
                  if (FlowFieldHelper.isNearActiveFieldLenient(playerUUID, currentPos)) {
                     BlockPos waypoint = currentPos;
                     boolean foundGpuRoute = false;

                     for (int step = 0; step < 20; step++) {
                        int[] vec = FlowFieldHelper.getVectorAt(playerUUID, this.mob.getUUID(), waypoint);
                        if (vec == null) {
                           if (step == 0) {
                              vec = FlowFieldHelper.getVectorAt(playerUUID, this.mob.getUUID(), waypoint.above());
                           }

                           if (vec == null) {
                              break;
                           }
                        }

                        waypoint = waypoint.offset(vec[0], vec[1], vec[2]);
                        foundGpuRoute = true;
                        if (waypoint.closerThan(targetPos, 1.5)) {
                           break;
                        }
                     }

                     if (foundGpuRoute && !waypoint.closerThan(currentPos, 2.0)) {
                        boolean result = this.moveTo(waypoint.getX() + 0.5, waypoint.getY(), waypoint.getZ() + 0.5, speed);
                        cir.setReturnValue(result);
                     }
                  }
               }
            }
         }
      }
   }
}

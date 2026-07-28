package com.izumi.laminar.client.mixin;

import com.izumi.laminar.client.AcousticEngine;
import com.izumi.laminar.client.ChannelHandleAccessor;
import com.izumi.laminar.client.GhostSoundInstance;
import com.mojang.blaze3d.audio.Channel;
import java.util.Map;
import java.util.Map.Entry;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.ChannelAccess.ChannelHandle;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = SoundEngine.class, priority = 9999)
public abstract class SoundEngineMixin {
   @Shadow
   @Final
   private Map<SoundInstance, ChannelHandle> instanceToChannel;

   @Shadow
   protected abstract float calculateVolume(SoundInstance var1);

   @Inject(method = "play", at = @At("TAIL"))
   private void onPlay(SoundInstance sound, CallbackInfoReturnable<?> cir) {
      if (!(sound instanceof GhostSoundInstance)) {
         AcousticEngine.registerSound(sound);
         AcousticEngine.tryScheduleEcho(sound);
      }
   }

   @Inject(method = "stop(Lnet/minecraft/client/resources/sounds/SoundInstance;)V", at = @At("HEAD"))
   private void onStop(SoundInstance sound, CallbackInfo ci) {
      if (!(sound instanceof GhostSoundInstance)) {
         AcousticEngine.removeSound(sound);
      }
   }

   @Inject(method = "tick", at = @At("TAIL"))
   private void onTick(boolean paused, CallbackInfo ci) {
      if (!paused) {
         for (Entry<SoundInstance, ChannelHandle> entry : this.instanceToChannel.entrySet()) {
            SoundInstance sound = entry.getKey();
            ChannelHandle handle = entry.getValue();
            Channel channel = ((ChannelHandleAccessor)handle).laminar$getChannel();
            if (channel != null) {
               AcousticEngine.registerChannel(sound, channel);
            }

            float dynamicVolume = this.calculateVolume(sound);
            handle.execute(channelInstance -> channelInstance.setVolume(dynamicVolume));
         }
      }

      AcousticEngine.cleanupInactiveSounds(this.instanceToChannel.keySet());
      AcousticEngine.tick();
   }

   @Inject(method = "calculateVolume(Lnet/minecraft/client/resources/sounds/SoundInstance;)F", at = @At("RETURN"), cancellable = true)
   private void onCalculateVolume(SoundInstance sound, CallbackInfoReturnable<Float> cir) {
      if (!(sound instanceof GhostSoundInstance)) {
         float originalVolume = (Float)cir.getReturnValue();
         float occlusion = AcousticEngine.getSmoothedOcclusion(sound);
         if (AcousticEngine.isEfxAvailable()) {
            cir.setReturnValue(originalVolume);
         } else {
            float finalVolume = originalVolume * occlusion;
            cir.setReturnValue(finalVolume < 0.01F ? 0.0F : finalVolume);
         }
      }
   }
}

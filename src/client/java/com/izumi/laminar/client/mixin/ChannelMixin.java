package com.izumi.laminar.client.mixin;

import com.izumi.laminar.client.ChannelAccessor;
import com.mojang.blaze3d.audio.Channel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(Channel.class)
public class ChannelMixin implements ChannelAccessor {
   @Shadow
   private int source;

   @Override
   public int laminar$getSourceId() {
      return this.source;
   }
}

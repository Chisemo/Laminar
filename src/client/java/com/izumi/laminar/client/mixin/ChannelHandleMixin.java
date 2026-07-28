package com.izumi.laminar.client.mixin;

import com.izumi.laminar.client.ChannelHandleAccessor;
import com.mojang.blaze3d.audio.Channel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(targets = "net.minecraft.client.sounds.ChannelAccess$ChannelHandle")
public class ChannelHandleMixin implements ChannelHandleAccessor {
   @Shadow
   @Final
   Channel channel;

   @Override
   public Channel laminar$getChannel() {
      return this.channel;
   }
}

package com.izumi.laminar.client;

import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;

public class GhostSoundInstance extends AbstractSoundInstance {
   public GhostSoundInstance(SoundInstance parent, float volume, float pitch) {
      super(parent.getIdentifier(), parent.getSource(), SoundInstance.createUnseededRandom());
      this.volume = volume;
      this.pitch = pitch;
      this.x = parent.getX();
      this.y = parent.getY();
      this.z = parent.getZ();
      this.attenuation = parent.getAttenuation();
      this.relative = parent.isRelative();
      this.delay = 0;
      this.looping = false;
   }
}

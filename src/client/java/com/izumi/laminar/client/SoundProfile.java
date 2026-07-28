package com.izumi.laminar.client;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;

public final class SoundProfile {
   public final float rangeMultiplier;
   public final float airDecay;
   public final float materialPenetration;
   public static final SoundProfile DEFAULT = new SoundProfile(1.0F, 1.0F, 1.0F);
   public static final SoundProfile JUKEBOX = new SoundProfile(1.0F, 0.75F, 2.8F);
   public static final SoundProfile THUNDER = new SoundProfile(1.0F, 0.45F, 3.5F);
   public static final SoundProfile FOOTSTEP = new SoundProfile(1.0F, 1.3F, 0.6F);
   public static final SoundProfile EXPLOSION = new SoundProfile(1.0F, 0.65F, 1.8F);
   public static final SoundProfile ENTITY = new SoundProfile(1.0F, 1.0F, 1.2F);
   public static final SoundProfile BLOCKS = new SoundProfile(1.0F, 0.95F, 1.0F);
   private static final Map<Identifier, SoundProfile> REGISTRY = new HashMap<>();

   private SoundProfile(float rangeMultiplier, float airDecay, float materialPenetration) {
      this.rangeMultiplier = rangeMultiplier;
      this.airDecay = airDecay;
      this.materialPenetration = materialPenetration;
   }

   public static SoundProfile get(SoundInstance sound) {
      Identifier id = sound.getIdentifier();
      String path = id.getPath();
      SoundSource source = sound.getSource();
      if (path.startsWith("music_disc.") || path.contains("jukebox") || source == SoundSource.RECORDS) {
         return JUKEBOX;
      }

      if (path.contains("thunder") || path.contains("lightning") || source == SoundSource.WEATHER) {
         return THUNDER;
      }

      if (path.contains("step")) {
         return FOOTSTEP;
      }

      if (!path.contains("explode") && !path.contains("explosion")) {
         switch (source) {
            case HOSTILE:
            case NEUTRAL:
            case PLAYERS:
               return ENTITY;
            case BLOCKS:
               return BLOCKS;
            default:
               return REGISTRY.getOrDefault(id, DEFAULT);
         }
      } else {
         return EXPLOSION;
      }
   }
}

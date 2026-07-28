package com.izumi.laminar.client;

import com.izumi.laminar.FlowFieldHelper;
import com.izumi.laminar.LaminarConfig;
import com.izumi.laminar.opencl.LaminarOpenCLManager;
import com.mojang.blaze3d.audio.Channel;
import com.mojang.logging.LogUtils;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerArray;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance.Attenuation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.EXTEfx;
import org.slf4j.Logger;

public class AcousticEngine {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final ConcurrentHashMap<SoundInstance, Integer> soundSlots = new ConcurrentHashMap<>();
   private static final ConcurrentHashMap<SoundInstance, Float> smoothedOcclusions = new ConcurrentHashMap<>();
   private static final ConcurrentHashMap<SoundInstance, Channel> soundChannels = new ConcurrentHashMap<>();
   private static final AtomicIntegerArray occupiedSlots = new AtomicIntegerArray(256);
   private static final AtomicBoolean isCalculating = new AtomicBoolean(false);
   private static final SoundProfile[] slotProfiles = new SoundProfile[256];
   private static final byte[] ECHO_CPU_SCAN_BUFFER = new byte[262144];
   private static final int MAX_ACTIVE_CALCULATIONS = 256;
   private static final float[] CPU_ACOUSTIC_CACHE = new float[256];
   private static final float[] CPU_REVERB_CACHE = new float[256];
   private static final List<AcousticEngine.PendingEcho> pendingEchoes = new ArrayList<>();
   private static boolean efxInitialized = false;
   private static boolean efxAvailable = false;
   private static int globalReverbSlot = 0;
   private static int globalReverbEffect = 0;
   private static final int[] sourceFilters = new int[256];
   private static final ExecutorService ECHO_GPU_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "Laminar-Echo-Worker");
      t.setDaemon(true);
      t.setPriority(4);
      return t;
   });
   private static int tickCounter = 0;
   private static final float AIR_BASE_COST = 0.25F;
   private static final float LIQUID_COST = 1.2F;
   private static final float LAVA_COST = 3.5F;
   private static final float SOLID_COST = 5.0F;

   public static boolean isEfxAvailable() {
      return efxAvailable;
   }

   public static float calculateMaxRange(SoundInstance sound, SoundProfile profile) {
      if (sound.getSource() == SoundSource.WEATHER) {
         return 128.0F;
      }

      Sound mcSound = sound.getSound();
      float baseRange = mcSound != null ? mcSound.getAttenuationDistance() : 16.0F;
      float volume = sound.getVolume();
      return (volume > 1.0F ? volume * baseRange : baseRange) * profile.rangeMultiplier;
   }

   public static void initializeEFX() {
      try {
         long device = ALC10.alcGetContextsDevice(ALC10.alcGetCurrentContext());
         efxAvailable = ALC10.alcIsExtensionPresent(device, "ALC_EXT_EFX");
         if (efxAvailable) {
            globalReverbSlot = EXTEfx.alGenAuxiliaryEffectSlots();
            globalReverbEffect = EXTEfx.alGenEffects();
            EXTEfx.alEffecti(globalReverbEffect, 32769, 1);
            EXTEfx.alAuxiliaryEffectSloti(globalReverbSlot, 1, globalReverbEffect);

            for (int i = 0; i < 256; i++) {
               sourceFilters[i] = EXTEfx.alGenFilters();
               EXTEfx.alFilteri(sourceFilters[i], 32769, 1);
            }

            LOGGER.info("[Laminar Audio] OpenAL EFX initialization successful! Physical echo enabled.");
         } else {
            LOGGER.warn("[Laminar Audio] OpenAL EFX is not supported by the system device. Switch to fallback volume.");
         }
      } catch (Throwable t) {
         efxAvailable = false;
         LOGGER.error("[Laminar Audio] Failed to initialize OpenAL EFX! Using fallback volume.", t);
      }
   }

   public static void registerSound(SoundInstance sound) {
      if (!LaminarConfig.isAcousticOcclusionEnabled()) {
         if (!sound.isRelative() && sound.getAttenuation() != Attenuation.NONE || sound.getSource() == SoundSource.WEATHER) {
            if (!soundSlots.containsKey(sound)) {
               if (!(sound instanceof GhostSoundInstance)) {
                  double x = sound.getX();
                  double y = sound.getY();
                  double z = sound.getZ();
                  if (x != 0.0 || y != 0.0 || z != 0.0) {
                     if (!(sound.getVolume() <= 0.0F)) {
                        int freeSlot = -1;

                        for (int i = 0; i < 256; i++) {
                           if (occupiedSlots.compareAndSet(i, 0, 1)) {
                              freeSlot = i;
                              break;
                           }
                        }

                        if (freeSlot != -1) {
                           soundSlots.put(sound, freeSlot);
                           smoothedOcclusions.put(sound, 1.0F);
                           slotProfiles[freeSlot] = SoundProfile.get(sound);
                        }
                     }
                  }
               }
            }
         }
      }
   }

   public static void registerChannel(SoundInstance sound, Channel channel) {
      soundChannels.put(sound, channel);
   }

   public static void tryScheduleEcho(SoundInstance sound) {
      if (!LaminarConfig.isAcousticOcclusionEnabled()) {
         if (!(sound instanceof GhostSoundInstance)) {
            if (sound.getSource() != SoundSource.WEATHER) {
               pendingEchoes.add(new AcousticEngine.PendingEcho(sound, 2));
            }
         }
      }
   }

   public static void removeSound(SoundInstance sound) {
      Integer slot = soundSlots.remove(sound);
      if (slot != null) {
         occupiedSlots.set(slot, 0);
         smoothedOcclusions.remove(sound);
         slotProfiles[slot] = null;
         soundChannels.remove(sound);
         if (efxAvailable) {
            Channel channel = soundChannels.get(sound);
            if (channel != null) {
               int sourceId = ((ChannelAccessor)channel).laminar$getSourceId();
               if (sourceId != 0) {
                  AL11.alSourcei(sourceId, 131077, 0);
                  AL11.alSource3i(sourceId, 131078, 0, 0, 0);
               }
            }
         }
      }
   }

   public static void cleanupInactiveSounds(Set<SoundInstance> activeMinecraftSounds) {
      Iterator<Entry<SoundInstance, Integer>> iterator = soundSlots.entrySet().iterator();

      while (iterator.hasNext()) {
         Entry<SoundInstance, Integer> entry = iterator.next();
         SoundInstance sound = entry.getKey();
         if (!activeMinecraftSounds.contains(sound)) {
            int slot = entry.getValue();
            iterator.remove();
            occupiedSlots.set(slot, 0);
            smoothedOcclusions.remove(sound);
            slotProfiles[slot] = null;
            soundChannels.remove(sound);
         }
      }
   }

   public static float getOcclusionRaw(SoundInstance sound) {
      Integer slot = soundSlots.get(sound);
      if (slot == null) {
         return 1.0F;
      }

      float raw = CPU_ACOUSTIC_CACHE[slot];
      return !Float.isNaN(raw) && !Float.isInfinite(raw) && !(raw < 0.0F) ? Math.min(raw, 1.0F) : 0.15F;
   }

   public static float getReverbRaw(SoundInstance sound) {
      Integer slot = soundSlots.get(sound);
      return slot == null ? 0.0F : CPU_REVERB_CACHE[slot];
   }

   public static float getSmoothedOcclusion(SoundInstance sound) {
      return smoothedOcclusions.getOrDefault(sound, 1.0F);
   }

   private static void updateSmoothers() {
      for (SoundInstance sound : soundSlots.keySet()) {
         int slot = soundSlots.get(sound);
         float target = getOcclusionRaw(sound);
         float current = smoothedOcclusions.getOrDefault(sound, 1.0F);
         float newOcclusion = current + (target - current) * 0.15F;
         smoothedOcclusions.put(sound, newOcclusion);
         if (efxAvailable) {
            Channel channel = soundChannels.get(sound);
            if (channel != null) {
               int sourceId = ((ChannelAccessor)channel).laminar$getSourceId();
               if (sourceId != 0) {
                  int filterId = sourceFilters[slot];
                  EXTEfx.alFilterf(filterId, 1, newOcclusion);
                  EXTEfx.alFilterf(filterId, 2, newOcclusion * newOcclusion);
                  AL11.alSourcei(sourceId, 131077, filterId);
                  float reverb = getReverbRaw(sound);
                  if (reverb > 0.05F) {
                     AL11.alSource3i(sourceId, 131078, globalReverbSlot, 0, 0);
                     EXTEfx.alEffectf(globalReverbEffect, 5, reverb * 3.5F);
                     EXTEfx.alAuxiliaryEffectSloti(globalReverbSlot, 1, globalReverbEffect);
                  } else {
                     AL11.alSource3i(sourceId, 131078, 0, 0, 0);
                  }
               }
            }
         }
      }
   }

   private static float computeFarFieldOcclusion(
      Map<Long, LevelChunk> chunkCache, double srcX, double srcY, double srcZ, double playX, double playY, double playZ, SoundProfile profile, float maxRange
   ) {
      double dx = srcX - playX;
      double dy = srcY - playY;
      double dz = srcZ - playZ;
      double maxDist = Math.sqrt(dx * dx + dy * dy + dz * dz);
      if (maxDist <= 0.1) {
         return 1.0F;
      }

      double dirX = dx / maxDist;
      double dirY = dy / maxDist;
      double dirZ = dz / maxDist;
      float penetration = Math.max(profile.materialPenetration, 0.05F);
      double pathCost = maxDist * profile.airDecay * 0.25;
      double stepSize = 1.5;
      MutableBlockPos mutPos = new MutableBlockPos();
      int lastX = -2147483648;
      int lastY = -2147483648;
      int lastZ = -2147483648;

      for (double dist = stepSize; dist < maxDist; dist += stepSize) {
         int bx = (int)Math.floor(playX + dirX * dist);
         int by = (int)Math.floor(playY + dirY * dist);
         int bz = (int)Math.floor(playZ + dirZ * dist);
         if (bx != lastX || by != lastY || bz != lastZ) {
            lastX = bx;
            lastY = by;
            lastZ = bz;
            long chunkKey = (long)(bx >> 4) << 32 | bz >> 4 & 4294967295L;
            LevelChunk chunk = chunkCache.get(chunkKey);
            if (chunk != null) {
               mutPos.set(bx, by, bz);
               BlockState state = chunk.getBlockState(mutPos);
               Block block = state.getBlock();
               if (block == Blocks.LAVA || block == Blocks.MAGMA_BLOCK) {
                  pathCost += stepSize * (3.5F / penetration);
               } else if (block == Blocks.WATER
                  || block == Blocks.KELP
                  || block == Blocks.KELP_PLANT
                  || block == Blocks.SEAGRASS
                  || block == Blocks.TALL_SEAGRASS) {
                  pathCost += stepSize * (1.2F / penetration);
               } else if (!state.isAir() && !state.getCollisionShape(chunk, mutPos).isEmpty()) {
                  pathCost += stepSize * (5.0F / penetration);
               }

               if (pathCost >= maxRange) {
                  return 0.0F;
               }
            }
         }
      }

      float t = (float)Math.min(Math.max(pathCost / maxRange, 0.0), 1.0);
      return 1.0F - t * t * (3.0F - 2.0F * t);
   }

   private static void tickPendingEchoes(Minecraft mc) {
      if (!pendingEchoes.isEmpty()) {
         for (int i = pendingEchoes.size() - 1; i >= 0; i--) {
            AcousticEngine.PendingEcho pending = pendingEchoes.get(i);
            pending.ticksRemaining--;
            if (pending.ticksRemaining <= 0) {
               pendingEchoes.remove(i);
               float reverb = getReverbRaw(pending.parent);
               if (reverb > 0.05F) {
                  float occlusion = getOcclusionRaw(pending.parent);
                  float echoVolume = pending.parent.getVolume() * reverb * occlusion * 0.28F;
                  if (echoVolume > 0.01F) {
                     float avgRoomSize = reverb * 24.0F;
                     int dynamicDelayTicks = Math.max(1, Math.round(avgRoomSize * 0.15F));
                     float echoPitch = pending.parent.getPitch() * (1.0F - reverb * 0.12F);
                     GhostSoundInstance ghost = new GhostSoundInstance(pending.parent, echoVolume, echoPitch);
                     mc.getSoundManager().playDelayed(ghost, dynamicDelayTicks);
                  }
               }
            }
         }
      }
   }

   public static void tick() {
      if (LaminarConfig.isAcousticOcclusionEnabled()) {
         soundSlots.clear();
         smoothedOcclusions.clear();
         pendingEchoes.clear();

         for (int i = 0; i < 256; i++) {
            occupiedSlots.set(i, 0);
            slotProfiles[i] = null;
         }

         FlowFieldHelper.forceVoxelScan = false;
      } else {
         Minecraft mc = Minecraft.getInstance();
         if (mc.player != null && mc.level != null) {
            if (!efxInitialized) {
               efxInitialized = true;
               initializeEFX();
            }

            updateSmoothers();
            tickPendingEchoes(mc);
            tickCounter++;
            if (soundSlots.isEmpty()) {
               FlowFieldHelper.forceVoxelScan = false;
            } else {
               FlowFieldHelper.forceVoxelScan = true;
               if (tickCounter % 6 == 0) {
                  if (isCalculating.compareAndSet(false, true)) {
                     BlockPos finalCenterPos = mc.player.blockPosition();
                     float pX = (float)mc.player.getX();
                     float pY = (float)mc.player.getEyeY();
                     float pZ = (float)mc.player.getZ();
                     List<Entry<SoundInstance, Integer>> snapshot = new ArrayList<>(soundSlots.entrySet());
                     snapshot.sort((e1, e2) -> {
                        double d1 = finalCenterPos.distSqr(new BlockPos((int)e1.getKey().getX(), (int)e1.getKey().getY(), (int)e1.getKey().getZ()));
                        double d2 = finalCenterPos.distSqr(new BlockPos((int)e2.getKey().getX(), (int)e2.getKey().getY(), (int)e2.getKey().getZ()));
                        return Double.compare(d1, d2);
                     });
                     int activeSounds = Math.min(snapshot.size(), 256);
                     List<Entry<SoundInstance, Integer>> finalSnapshot = snapshot.subList(0, activeSounds);
                     int radius = 32;
                     int chunkMinX = finalCenterPos.getX() - radius >> 4;
                     int chunkMinZ = finalCenterPos.getZ() - radius >> 4;
                     int chunkMaxX = finalCenterPos.getX() + radius >> 4;
                     int chunkMaxZ = finalCenterPos.getZ() + radius >> 4;
                     int cntX = chunkMaxX - chunkMinX + 1;
                     int cntZ = chunkMaxZ - chunkMinZ + 1;
                     LevelChunk[][] chunkCache = new LevelChunk[cntX][cntZ];
                     boolean chunksReady = true;

                     label99:
                     for (int i = 0; i < cntX; i++) {
                        for (int j = 0; j < cntZ; j++) {
                           LevelChunk chunk = mc.level.getChunkSource().getChunkNow(chunkMinX + i, chunkMinZ + j);
                           if (chunk == null) {
                              chunksReady = false;
                              break label99;
                           }

                           chunkCache[i][j] = chunk;
                        }
                     }

                     if (!chunksReady) {
                        isCalculating.set(false);
                     } else {
                        LevelChunk[][] finalChunkCache = chunkCache;
                        int finalChunkMinX = chunkMinX;
                        int finalChunkMinZ = chunkMinZ;
                        long startTime = System.nanoTime();
                        HashMap<Long, LevelChunk> farFieldChunkCache = new HashMap<>();

                        for (Entry<SoundInstance, Integer> entry : finalSnapshot) {
                           SoundInstance sound = entry.getKey();
                           SoundProfile profile = slotProfiles[entry.getValue()];
                           if (profile != null) {
                              float maxRange = calculateMaxRange(sound, profile);
                              if (!(maxRange <= radius)) {
                                 double dx = sound.getX() - pX;
                                 double dy = sound.getY() - pY;
                                 double dz = sound.getZ() - pZ;
                                 double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                                 if (!(dist <= radius)) {
                                    int steps = Math.max(1, (int)Math.ceil(dist / 8.0));

                                    for (int s = 0; s <= steps; s++) {
                                       double t = (double)s / steps;
                                       int cx = (int)Math.floor(pX + dx * t) >> 4;
                                       int cz = (int)Math.floor(pZ + dz * t) >> 4;
                                       long key = (long)cx << 32 | cz & 4294967295L;
                                       if (!farFieldChunkCache.containsKey(key)) {
                                          LevelChunk c = mc.level.getChunkSource().getChunkNow(cx, cz);
                                          if (c != null) {
                                             farFieldChunkCache.put(key, c);
                                          }
                                       }
                                    }
                                 }
                              }
                           }
                        }

                        CompletableFuture.runAsync(
                           () -> {
                              try {
                                 LaminarOpenCLManager clManager = LaminarOpenCLManager.getInstance();
                                 int cxLocal = finalCenterPos.getX();
                                 int cyLocal = finalCenterPos.getY();
                                 int czLocal = finalCenterPos.getZ();
                                 int lastChunkX = -2147483648;
                                 int lastChunkZ = -2147483648;
                                 LevelChunk lastChunk = null;
                                 int writeIdx = 0;
                                 MutableBlockPos mutPos = new MutableBlockPos();

                                 for (int x = -radius; x < radius; x++) {
                                    int worldX = cxLocal + x;
                                    int chunkIdxX = (worldX >> 4) - finalChunkMinX;

                                    for (int y = -radius; y < radius; y++) {
                                       int worldY = cyLocal + y;

                                       for (int z = -radius; z < radius; z++) {
                                          int worldZ = czLocal + z;
                                          int chunkIdxZ = (worldZ >> 4) - finalChunkMinZ;
                                          LevelChunk chunk;
                                          if (chunkIdxX == lastChunkX && chunkIdxZ == lastChunkZ) {
                                             chunk = lastChunk;
                                          } else {
                                             chunk = finalChunkCache[chunkIdxX][chunkIdxZ];
                                             lastChunk = chunk;
                                             lastChunkX = chunkIdxX;
                                             lastChunkZ = chunkIdxZ;
                                          }

                                          mutPos.set(worldX, worldY, worldZ);
                                          BlockState state = chunk.getBlockState(mutPos);
                                          Block block = state.getBlock();
                                          byte cost;
                                          if (state.isAir()) {
                                             cost = 1;
                                          } else if (block == Blocks.LAVA || block == Blocks.MAGMA_BLOCK) {
                                             cost = -2;
                                          } else if (block == Blocks.WATER
                                             || block == Blocks.KELP
                                             || block == Blocks.KELP_PLANT
                                             || block == Blocks.SEAGRASS
                                             || block == Blocks.TALL_SEAGRASS) {
                                             cost = 25;
                                          } else if (!state.getCollisionShape(chunk, mutPos).isEmpty()) {
                                             cost = -1;
                                          } else {
                                             cost = 1;
                                          }

                                          ECHO_CPU_SCAN_BUFFER[writeIdx++] = cost;
                                       }
                                    }
                                 }

                                 ByteBuffer echoCostBuffer = clManager.getMappedEchoCostBuffer();
                                 if (echoCostBuffer != null) {
                                    echoCostBuffer.clear();
                                    echoCostBuffer.put(ECHO_CPU_SCAN_BUFFER, 0, writeIdx);
                                 }

                                 float diameter = 64.0F;
                                 List<Integer> dispatchedSlots = new ArrayList<>(finalSnapshot.size());

                                 for (Entry<SoundInstance, Integer> entryx : finalSnapshot) {
                                    SoundInstance soundx = entryx.getKey();
                                    int slot = entryx.getValue();
                                    SoundProfile profilex = slotProfiles[slot];
                                    if (profilex == null) {
                                       profilex = SoundProfile.DEFAULT;
                                    }

                                    double dxx = soundx.getX() - pX;
                                    double dyx = soundx.getY() - pY;
                                    double dzx = soundx.getZ() - pZ;
                                    double distSq = dxx * dxx + dyx * dyx + dzx * dzx;
                                    float maxRangex = calculateMaxRange(soundx, profilex);
                                    if (distSq > (double)maxRangex * maxRangex) {
                                       CPU_ACOUSTIC_CACHE[slot] = 0.0F;
                                       CPU_REVERB_CACHE[slot] = 0.0F;
                                    } else {
                                       float srcX = (float)(soundx.getX() - finalCenterPos.getX()) + 32.0F;
                                       float srcY = (float)(soundx.getY() - finalCenterPos.getY()) + 32.0F;
                                       float srcZ = (float)(soundx.getZ() - finalCenterPos.getZ()) + 32.0F;
                                       float playX = pX - finalCenterPos.getX() + 32.0F;
                                       float playY = pY - finalCenterPos.getY() + 32.0F;
                                       float playZ = pZ - finalCenterPos.getZ() + 32.0F;
                                       boolean srcInGrid = srcX >= 0.0F
                                          && srcX < diameter
                                          && srcY >= 0.0F
                                          && srcY < diameter
                                          && srcZ >= 0.0F
                                          && srcZ < diameter;
                                       boolean playInGrid = playX >= 0.0F
                                          && playX < diameter
                                          && playY >= 0.0F
                                          && playY < diameter
                                          && playZ >= 0.0F
                                          && playZ < diameter;
                                       if (srcInGrid && playInGrid) {
                                          clManager.runAcousticsCalculation(
                                             slot, srcX, srcY, srcZ, playX, playY, playZ, maxRangex, profilex.airDecay, profilex.materialPenetration
                                          );
                                          dispatchedSlots.add(slot);
                                       } else {
                                          CPU_ACOUSTIC_CACHE[slot] = computeFarFieldOcclusion(
                                             farFieldChunkCache, soundx.getX(), soundx.getY(), soundx.getZ(), pX, pY, pZ, profilex, maxRangex
                                          );
                                          CPU_REVERB_CACHE[slot] = 0.0F;
                                       }
                                    }
                                 }

                                 if (!dispatchedSlots.isEmpty()) {
                                    clManager.syncEcho();
                                    ByteBuffer acousticOutput = clManager.getMappedAcousticBuffer();
                                    if (acousticOutput != null) {
                                       for (int slot : dispatchedSlots) {
                                          CPU_ACOUSTIC_CACHE[slot] = acousticOutput.getFloat(slot * 8);
                                          CPU_REVERB_CACHE[slot] = acousticOutput.getFloat(slot * 8 + 4);
                                       }
                                    }
                                 }

                                 if (tickCounter % 100 == 0) {
                                    long endTime = System.nanoTime();
                                    double durationMs = (endTime - startTime) / 1000000.0;
                                    System.out
                                       .printf(
                                          "[Laminar Audio Engine] Processing %d sounds (%d GPU dispatch) | Time: %.3f ms\n",
                                          activeSounds,
                                          dispatchedSlots.size(),
                                          durationMs
                                       );
                                 }
                              } catch (Exception e) {
                                 LOGGER.error("[Laminar Audio Engine] Fatal error during iGPU asynchronous occlusion computation!", e);
                              } finally {
                                 isCalculating.set(false);
                              }
                           },
                           ECHO_GPU_EXECUTOR
                        );
                     }
                  }
               }
            }
         } else {
            soundSlots.clear();
            smoothedOcclusions.clear();
            pendingEchoes.clear();

            for (int i = 0; i < 256; i++) {
               occupiedSlots.set(i, 0);
               slotProfiles[i] = null;
            }

            FlowFieldHelper.forceVoxelScan = false;
         }
      }
   }

   public static int getOcclusionRawCount() {
      return soundSlots.size();
   }

   static {
      Arrays.fill(CPU_ACOUSTIC_CACHE, 1.0F);
      Arrays.fill(CPU_REVERB_CACHE, 0.0F);
   }

   private static class PendingEcho {
      final SoundInstance parent;
      int ticksRemaining;

      public PendingEcho(SoundInstance parent, int ticksRemaining) {
         this.parent = parent;
         this.ticksRemaining = ticksRemaining;
      }
   }
}

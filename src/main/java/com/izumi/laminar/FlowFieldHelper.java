package com.izumi.laminar;

import com.izumi.laminar.opencl.LaminarOpenCLManager;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Endermite;
import net.minecraft.world.entity.monster.Silverfish;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.monster.illager.Vindicator;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.piglin.PiglinBrute;
import net.minecraft.world.entity.monster.skeleton.WitherSkeleton;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.system.MemoryUtil;

public class FlowFieldHelper {
   private static final ConcurrentHashMap<UUID, byte[]> activeVectorFields = new ConcurrentHashMap<>();
   private static final ConcurrentHashMap<UUID, BlockPos> fieldCenterPositions = new ConcurrentHashMap<>();
   private static final ConcurrentHashMap<UUID, BlockPos> lastCalculatedPositions = new ConcurrentHashMap<>();
   private static final ConcurrentHashMap<UUID, Float> lastPlayerYaws = new ConcurrentHashMap<>();
   private static final ConcurrentHashMap<UUID, Integer> ticksSinceLastUpdate = new ConcurrentHashMap<>();
   private static final ConcurrentHashMap<UUID, Integer> unreachableZombieTicks = new ConcurrentHashMap<>();
   private static final ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, Boolean>> gpuVisibilityCache = new ConcurrentHashMap<>();
   private static final ConcurrentHashMap<UUID, Set<UUID>> activeTargetMap = new ConcurrentHashMap<>();
   private static final Set<UUID> activeZombiesThisLog = ConcurrentHashMap.newKeySet();
   private static int tickCounter = 0;
   public static volatile boolean forceVoxelScan = false;
   public static volatile boolean forceRecalculate = false;
   public static final ConcurrentHashMap<UUID, BlockPos> latestGridCenters = new ConcurrentHashMap<>();
   private static final AtomicBoolean isCalculating = new AtomicBoolean(false);
   private static int stuckTicks = 0;
   private static final int DIAMETER = 64;
   private static final int RADIUS = 32;
   private static final int CENTER_LOCAL_IDX = 133152;
   private static final ExecutorService GPU_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "Laminar-iGPU-Worker");
      t.setDaemon(true);
      t.setPriority(4);
      return t;
   });

   private static void forceCleanup(Level level, Player player) {
      AABB cleanupBox = player.getBoundingBox().inflate(64.0);

      for (Mob monster : level.getEntitiesOfClass(Mob.class, cleanupBox, FlowFieldHelper::isSupportedMob)) {
         monster.setTarget(null);
         monster.setAggressive(false);
         monster.getNavigation().stop();
         monster.getNavigation().moveTo(monster.getX(), monster.getY(), monster.getZ(), 0.0);
         if (monster instanceof NeutralMob nm) {
            nm.stopBeingAngry();
         }
      }
   }

   public static void tick(Level level) {
      LaminarOpenCLManager clManager = LaminarOpenCLManager.getInstance();
      tickCounter++;
      if (isCalculating.get()) {
         stuckTicks++;
         if (stuckTicks % 100 == 0) {
            System.err.println("[Laminar Debug] WARNING: iGPU stuck! Locked up for " + stuckTicks + " tick.");
         }
      } else {
         stuckTicks = 0;
         int cpuWriteIndex = clManager.getCpuWriteIndex();
         ByteBuffer costBuffer = clManager.getMappedCostBuffer(cpuWriteIndex);
         if (costBuffer != null) {
            for (Entity playerEntity : level.players()) {
               if (playerEntity instanceof Player player) {
                  UUID playerId = player.getUUID();
                  BlockPos centerPos = player.blockPosition();
                  Set<UUID> activePursuers = activeTargetMap.get(playerId);
                  boolean playerSpotted = activePursuers != null && !activePursuers.isEmpty();
                  boolean playerInvulnerable = player.isCreative() || player.isSpectator() || !player.isAlive();
                  if (playerInvulnerable && activeVectorFields.containsKey(playerId)) {
                     forceCleanup(level, player);
                     forceVoxelScan = false;
                     forceRecalculate = false;
                     activeVectorFields.remove(playerId);
                     fieldCenterPositions.remove(playerId);
                     lastCalculatedPositions.remove(playerId);
                     lastPlayerYaws.remove(playerId);
                     ticksSinceLastUpdate.remove(playerId);
                     latestGridCenters.remove(playerId);
                     gpuVisibilityCache.remove(playerId);
                  } else if (!playerSpotted) {
                     if (activeVectorFields.containsKey(playerId)) {
                        forceCleanup(level, player);
                     }

                     forceVoxelScan = false;
                     forceRecalculate = false;
                     activeVectorFields.remove(playerId);
                     fieldCenterPositions.remove(playerId);
                     lastCalculatedPositions.remove(playerId);
                     lastPlayerYaws.remove(playerId);
                     ticksSinceLastUpdate.remove(playerId);
                     latestGridCenters.remove(playerId);
                     gpuVisibilityCache.remove(playerId);
                     activeTargetMap.remove(playerId);
                  } else {
                     if (LaminarConfig.isSwarmAlertEnabled()) {
                        AABB searchBox = player.getBoundingBox().inflate(32.0);
                        List<Mob> nearbyMonsters = level.getEntitiesOfClass(Mob.class, searchBox, FlowFieldHelper::isSupportedMob);

                        for (Mob detector : nearbyMonsters) {
                           if (detector.getTarget() == player) {
                              for (Mob other : nearbyMonsters) {
                                 if (other.getTarget() != player
                                    && (!(other instanceof Spider) || other.level().getMaxLocalRawBrightness(other.blockPosition()) <= 11)
                                    && other.distanceToSqr(detector) <= 144.0) {
                                    other.setTarget(player);
                                 }
                              }
                           }
                        }
                     }

                     for (int searchLimit = 4; searchLimit > 0 && centerPos.getY() > level.getMinY() && level.getBlockState(centerPos).isAir(); searchLimit--) {
                        centerPos = centerPos.below();
                     }

                     centerPos = centerPos.above();
                     float currentYaw = player.getYRot();
                     BlockPos lastPos = lastCalculatedPositions.get(playerId);
                     Float lastYaw = lastPlayerYaws.get(playerId);
                     int ticks = ticksSinceLastUpdate.getOrDefault(playerId, 0) + 1;
                     ticksSinceLastUpdate.put(playerId, ticks);
                     boolean shouldRecalculate = false;
                     if (ticks >= 5 || forceRecalculate) {
                        if (lastPos != null && lastYaw != null) {
                           int dx = centerPos.getX() - lastPos.getX();
                           int dy = centerPos.getY() - lastPos.getY();
                           int dz = centerPos.getZ() - lastPos.getZ();
                           double distSq = dx * dx + dy * dy + dz * dz;
                           double yawDiff = Math.abs(currentYaw - lastYaw);
                           if (distSq > 1.0 || yawDiff > 45.0 || ticks >= 20 || forceRecalculate) {
                              shouldRecalculate = true;
                              forceRecalculate = false;
                           }
                        } else {
                           shouldRecalculate = true;
                        }
                     }

                     if (shouldRecalculate) {
                        ticksSinceLastUpdate.put(playerId, 0);
                        AABB searchBox = player.getBoundingBox().inflate(32.0);
                        List<Mob> nearbyMonsters = level.getEntitiesOfClass(Mob.class, searchBox, FlowFieldHelper::isSupportedMob);
                        int cxBlock = centerPos.getX();
                        int czBlock = centerPos.getZ();
                        int chunkMinX = cxBlock - 32 >> 4;
                        int chunkMaxX = cxBlock + 32 >> 4;
                        int chunkMinZ = czBlock - 32 >> 4;
                        int chunkMaxZ = czBlock + 32 >> 4;
                        int chunkCountX = chunkMaxX - chunkMinX + 1;
                        int chunkCountZ = chunkMaxZ - chunkMinZ + 1;
                        LevelChunk[][] chunkCache = new LevelChunk[chunkCountX][chunkCountZ];
                        boolean allChunksLoaded = true;

                        for (int i = 0; i < chunkCountX; i++) {
                           for (int j = 0; j < chunkCountZ; j++) {
                              LevelChunk chunk = level.getChunkSource().getChunkNow(chunkMinX + i, chunkMinZ + j);
                              if (chunk == null) {
                                 allChunksLoaded = false;
                                 break;
                              }

                              chunkCache[i][j] = chunk;
                           }

                           if (!allChunksLoaded) {
                              break;
                           }
                        }

                        if (allChunksLoaded) {
                           List<FlowFieldHelper.MobAuraRecord> auraRecords = new ArrayList<>();
                           Set<UUID> currentActiveUuids = new HashSet<>();

                           for (int i = 0; i < Math.min(nearbyMonsters.size(), 1024); i++) {
                              Mob mob = nearbyMonsters.get(i);
                              Vec3 eyePos = mob.getEyePosition();
                              auraRecords.add(new FlowFieldHelper.MobAuraRecord(mob.getUUID(), eyePos.x, eyePos.y, eyePos.z));
                              currentActiveUuids.add(mob.getUUID());
                           }

                           double pEyeX = player.getX();
                           double pEyeY = player.getEyeY();
                           double pEyeZ = player.getZ();
                           if (!isCalculating.compareAndSet(false, true)) {
                              return;
                           }

                           LevelChunk[][] finalChunkCache = chunkCache;
                           int finalChunkMinX = chunkMinX;
                           int finalChunkMinZ = chunkMinZ;
                           BlockPos finalCenterPos = centerPos;
                           UUID finalPlayerId = playerId;
                           float finalCurrentYaw = currentYaw;
                           long startTime = System.nanoTime();
                           CompletableFuture.runAsync(
                              () -> {
                                 try {
                                    costBuffer.clear();
                                    MutableBlockPos currentMutablePos = new MutableBlockPos();
                                    MutableBlockPos belowMutablePos = new MutableBlockPos();
                                    int cx = finalCenterPos.getX();
                                    int cy = finalCenterPos.getY();
                                    int cz = finalCenterPos.getZ();
                                    int lastChunkX = -2147483648;
                                    int lastChunkZ = -2147483648;
                                    LevelChunk lastChunk = null;
                                    long currentAddress = MemoryUtil.memAddress(costBuffer);

                                    for (int x = -32; x < 32; x++) {
                                       int worldX = cx + x;
                                       int chunkIdxX = (worldX >> 4) - finalChunkMinX;

                                       for (int y = -32; y < 32; y++) {
                                          int worldY = cy + y;

                                          for (int z = -32; z < 32; z++) {
                                             int worldZ = cz + z;
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

                                             currentMutablePos.set(worldX, worldY, worldZ);
                                             BlockState state = chunk.getBlockState(currentMutablePos);
                                             Block block = state.getBlock();
                                             byte cost;
                                             if (state.isAir()) {
                                                boolean hasFloor = false;

                                                for (int i = 1; i <= 3; i++) {
                                                   belowMutablePos.set(worldX, worldY - i, worldZ);
                                                   BlockState belowState = chunk.getBlockState(belowMutablePos);
                                                   Block belowBlock = belowState.getBlock();
                                                   if (belowBlock == Blocks.LAVA
                                                      || belowBlock == Blocks.MAGMA_BLOCK
                                                      || belowBlock == Blocks.FIRE
                                                      || belowBlock == Blocks.SOUL_FIRE
                                                      || belowBlock == Blocks.CACTUS
                                                      || belowBlock == Blocks.SWEET_BERRY_BUSH) {
                                                      break;
                                                   }

                                                   if (!belowState.getCollisionShape(chunk, belowMutablePos).isEmpty()) {
                                                      hasFloor = true;
                                                      break;
                                                   }
                                                }

                                                cost = (byte)(hasFloor ? 1 : -1);
                                             } else if (block == Blocks.WATER
                                                || block == Blocks.KELP
                                                || block == Blocks.KELP_PLANT
                                                || block == Blocks.SEAGRASS
                                                || block == Blocks.TALL_SEAGRASS) {
                                                cost = 25;
                                             } else if (block == Blocks.LAVA
                                                || block == Blocks.MAGMA_BLOCK
                                                || block == Blocks.FIRE
                                                || block == Blocks.SOUL_FIRE
                                                || block == Blocks.CACTUS
                                                || block == Blocks.SWEET_BERRY_BUSH) {
                                                cost = -2;
                                             } else if (!state.getCollisionShape(chunk, currentMutablePos).isEmpty()) {
                                                cost = -1;
                                             } else {
                                                boolean hasFloor = false;

                                                for (int i = 1; i <= 3; i++) {
                                                   belowMutablePos.set(worldX, worldY - i, worldZ);
                                                   BlockState belowState = chunk.getBlockState(belowMutablePos);
                                                   Block blockBelow = belowState.getBlock();
                                                   if (blockBelow == Blocks.LAVA
                                                      || blockBelow == Blocks.MAGMA_BLOCK
                                                      || blockBelow == Blocks.FIRE
                                                      || blockBelow == Blocks.SOUL_FIRE
                                                      || blockBelow == Blocks.CACTUS
                                                      || blockBelow == Blocks.SWEET_BERRY_BUSH) {
                                                      break;
                                                   }

                                                   if (!belowState.getCollisionShape(chunk, belowMutablePos).isEmpty()) {
                                                      hasFloor = true;
                                                      break;
                                                   }
                                                }

                                                cost = (byte)(hasFloor ? 1 : -1);
                                             }

                                             MemoryUtil.memPutByte(currentAddress++, cost);
                                          }
                                       }
                                    }

                                    int gpuProcessIndex;
                                    synchronized (clManager) {
                                       clManager.swapPingPongBuffers();
                                       gpuProcessIndex = clManager.getGpuReadIndex();
                                    }

                                    if (!auraRecords.isEmpty()) {
                                       synchronized (clManager) {
                                          clManager.runFlowFieldCalculation(133152, gpuProcessIndex);
                                          ByteBuffer auraInput = clManager.getMappedAuraInputBuffer();
                                          if (auraInput != null) {
                                             auraInput.position(0);
                                             int cx2 = finalCenterPos.getX();
                                             int cy2 = finalCenterPos.getY();
                                             int cz2 = finalCenterPos.getZ();

                                             for (FlowFieldHelper.MobAuraRecord rec : auraRecords) {
                                                float relX = (float)(rec.x() - cx2) + 32.0F;
                                                float relEyeY = (float)(rec.eyeY() - cy2) + 32.0F;
                                                float relZ = (float)(rec.z() - cz2) + 32.0F;
                                                auraInput.putFloat(relX);
                                                auraInput.putFloat(relEyeY);
                                                auraInput.putFloat(relZ);
                                                auraInput.putFloat(0.0F);
                                             }

                                             float relPlayerEyeX = (float)(pEyeX - cx2) + 32.0F;
                                             float relPlayerEyeY = (float)(pEyeY - cy2) + 32.0F;
                                             float relPlayerEyeZ = (float)(pEyeZ - cz2) + 32.0F;
                                             clManager.runAuraCalculation(auraRecords.size(), relPlayerEyeX, relPlayerEyeY, relPlayerEyeZ, gpuProcessIndex);
                                          }
                                       }

                                       clManager.syncHive();
                                       clManager.syncAura();
                                       synchronized (clManager) {
                                          ByteBuffer vectorBuffer = clManager.getMappedVectorBuffer(gpuProcessIndex);
                                          byte[] playerVectors = new byte[786432];
                                          if (vectorBuffer != null) {
                                             vectorBuffer.position(0);
                                             vectorBuffer.get(playerVectors);
                                             activeVectorFields.put(finalPlayerId, playerVectors);
                                             fieldCenterPositions.put(finalPlayerId, finalCenterPos);
                                          }

                                          ByteBuffer auraOutput = clManager.getMappedAuraOutputBuffer();
                                          if (auraOutput != null) {
                                             ConcurrentHashMap<UUID, Boolean> playerVisibilityMap = gpuVisibilityCache.computeIfAbsent(
                                                finalPlayerId, unused -> new ConcurrentHashMap<>()
                                             );

                                             for (int i = 0; i < auraRecords.size(); i++) {
                                                byte result = auraOutput.get(i);
                                                if (result == 0) {
                                                   playerVisibilityMap.put(auraRecords.get(i).uuid(), false);
                                                } else if (result == 1) {
                                                   playerVisibilityMap.put(auraRecords.get(i).uuid(), true);
                                                }
                                             }

                                             playerVisibilityMap.keySet().retainAll(currentActiveUuids);
                                          }
                                       }
                                    }

                                    latestGridCenters.put(finalPlayerId, finalCenterPos);
                                    if (tickCounter % 100 == 0) {
                                       long endTime = System.nanoTime();
                                       double durationMs = (endTime - startTime) / 1000000.0;
                                       int activeZombies = activeZombiesThisLog.size();
                                       activeZombiesThisLog.clear();
                                       System.out.printf("[Laminar GPU Engine] Processing %d mobs & Audio | Time: %.3f ms\n", activeZombies, durationMs);
                                    }

                                    lastCalculatedPositions.put(finalPlayerId, finalCenterPos);
                                    lastPlayerYaws.put(finalPlayerId, finalCurrentYaw);
                                 } catch (Throwable t) {
                                    System.err.println("[Laminar Debug] CRUCIAL ERROR IN BACKGROUND THREAD: " + t.getMessage());
                                 } finally {
                                    isCalculating.set(false);
                                 }
                              },
                              GPU_EXECUTOR
                           );
                           break;
                        }
                     }
                  }
               }
            }
         }
      }
   }

   public static Boolean getGpuVisibility(UUID playerId, UUID mobId) {
      ConcurrentHashMap<UUID, Boolean> playerMap = gpuVisibilityCache.get(playerId);
      return playerMap != null ? playerMap.get(mobId) : null;
   }

   public static boolean isNearActiveFieldLenient(UUID playerId, BlockPos targetPos) {
      BlockPos center = fieldCenterPositions.get(playerId);
      return center != null && targetPos.closerThan(center, 36.0) ? activeVectorFields.containsKey(playerId) : false;
   }

   public static int[] getVectorAt(UUID targetPlayerId, UUID mobId, BlockPos mobPos) {
      byte[] vectors = activeVectorFields.get(targetPlayerId);
      BlockPos center = fieldCenterPositions.get(targetPlayerId);
      if (vectors != null && center != null) {
         int relX = mobPos.getX() - center.getX() + 32;
         int relY = mobPos.getY() - center.getY() + 32;
         int relZ = mobPos.getZ() - center.getZ() + 32;
         if (relX >= 0 && relX < 64 && relY >= 0 && relY < 64 && relZ >= 0 && relZ < 64) {
            int index = relX * 64 * 64 + relY * 64 + relZ;
            int baseIdx = index * 3;
            if (baseIdx + 2 >= vectors.length) {
               return null;
            }

            int dx = vectors[baseIdx];
            int dy = vectors[baseIdx + 1];
            int dz = vectors[baseIdx + 2];
            if (dx == 0 && dy == 0 && dz == 0) {
               return null;
            }

            activeZombiesThisLog.add(mobId);
            return new int[]{dx, dy, dz};
         } else {
            return null;
         }
      } else {
         return null;
      }
   }

   public static int incrementUnreachableTicks(UUID mobId) {
      int ticks = unreachableZombieTicks.getOrDefault(mobId, 0) + 1;
      unreachableZombieTicks.put(mobId, ticks);
      return ticks;
   }

   public static void registerActiveTarget(UUID playerId, UUID mobId) {
      activeTargetMap.computeIfAbsent(playerId, unused -> ConcurrentHashMap.newKeySet()).add(mobId);
   }

   public static void unregisterActiveTarget(UUID mobId) {
      for (Set<UUID> mobIds : activeTargetMap.values()) {
         mobIds.remove(mobId);
      }

      unreachableZombieTicks.remove(mobId);
   }

   public static void resetUnreachableTicks(UUID mobId) {
      unreachableZombieTicks.remove(mobId);
   }

   public static boolean isSupportedMob(Entity entity) {
      return entity instanceof Zombie
         || entity instanceof Creeper
         || entity instanceof Spider
         || entity instanceof PiglinBrute
         || entity instanceof Vindicator
         || entity instanceof WitherSkeleton
         || entity instanceof Piglin
         || entity instanceof Hoglin
         || entity instanceof Endermite
         || entity instanceof Silverfish;
   }

   public static int getLatestGridCenterCount() {
      return latestGridCenters.size();
   }

   public static int getActivePursuerCount() {
      int total = 0;

      for (Set<UUID> set : activeTargetMap.values()) {
         total += set.size();
      }

      return total;
   }

   public static void clearAll() {
      activeVectorFields.clear();
      fieldCenterPositions.clear();
      lastCalculatedPositions.clear();
      lastPlayerYaws.clear();
      ticksSinceLastUpdate.clear();
      latestGridCenters.clear();
      gpuVisibilityCache.clear();
      activeTargetMap.clear();
      activeZombiesThisLog.clear();
      isCalculating.set(false);
   }

   private record MobAuraRecord(UUID uuid, double x, double eyeY, double z) {
   }
}

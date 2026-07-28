package com.izumi.laminar.opencl;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.stream.Collectors;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.CL10;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

public class LaminarOpenCLManager {
   private static LaminarOpenCLManager instance;
   private long deviceId;
   private long context;
   private long commandQueueHive;
   private long commandQueueEcho;
   private long commandQueueAura;
   private long initializeKernel;
   private long solveIntegrationKernel;
   private long generateFlowKernel;
   private long echoKernel;
   private long auraKernel;
   public static final int GRID_RADIUS = 32;
   public static final int GRID_DIAMETER = 64;
   public static final int GRID_SIZE = 262144;
   public static final int MAX_SOUNDS = 256;
   public static final int MAX_AURA_MOBS = 1024;
   private final long[] clCostMemObj = new long[2];
   private final long[] clIntegrationMemObj = new long[2];
   private final long[] clVectorMemObj = new long[2];
   private final ByteBuffer[] pMappedCostBuffer = new ByteBuffer[2];
   private final ByteBuffer[] pMappedVectorBuffer = new ByteBuffer[2];
   private long clConvergenceMemObj;
   private long clEchoCostMemObj;
   private ByteBuffer pMappedEchoCostBuffer;
   private long clAcousticResultMemObj;
   private ByteBuffer pMappedAcousticBuffer;
   private long clAuraInputMemObj;
   private long clAuraOutputMemObj;
   private ByteBuffer pMappedAuraInputBuffer;
   private ByteBuffer pMappedAuraOutputBuffer;
   private int currentPingPongIndex = 0;
   private static String lockedGpuName = "None";

   public static synchronized LaminarOpenCLManager getInstance() {
      if (instance == null) {
         instance = new LaminarOpenCLManager();
      }

      return instance;
   }

   public long getContext() {
      return this.context;
   }

   public long getDeviceId() {
      return this.deviceId;
   }

   public void initialize() {
      System.out.println("[Laminar] Initializing OpenCL — Triple-Queue + Echo Independent Buffer...");

      try {
         MemoryStack stack = MemoryStack.stackPush();

         label163: {
            label164: {
               label165: {
                  label166: {
                     label177: {
                        label167: {
                           try {
                              IntBuffer pi = stack.mallocInt(1);
                              CL10.clGetPlatformIDs(null, pi);
                              if (pi.get(0) == 0) {
                                 System.err.println("[Laminar] No OpenCL Platforms found!");
                                 break label177;
                              }

                              PointerBuffer platforms = stack.mallocPointer(pi.get(0));
                              CL10.clGetPlatformIDs(platforms, (IntBuffer)null);

                              for (int i = 0; i < platforms.capacity(); i++) {
                                 long platform = platforms.get(i);
                                 IntBuffer devCount = stack.mallocInt(1);
                                 CL10.clGetDeviceIDs(platform, 4L, null, devCount);
                                 if (devCount.get(0) != 0) {
                                    PointerBuffer devices = stack.mallocPointer(devCount.get(0));
                                    CL10.clGetDeviceIDs(platform, 4L, devices, (IntBuffer)null);

                                    for (int j = 0; j < devices.capacity(); j++) {
                                       long dev = devices.get(j);
                                       IntBuffer unified = stack.mallocInt(1);
                                       CL10.clGetDeviceInfo(dev, 4149, unified, null);
                                       if (unified.get(0) == 1) {
                                          this.deviceId = dev;
                                          ByteBuffer nameBuf = stack.malloc(256);
                                          PointerBuffer nameLen = stack.mallocPointer(1);
                                          CL10.clGetDeviceInfo(dev, 4139, nameBuf, nameLen);
                                          String gpuName = MemoryUtil.memUTF8(nameBuf, (int)nameLen.get(0) - 1).trim();
                                          lockedGpuName = gpuName;
                                          System.out.println("[Laminar] Successfully locked iGPU: " + gpuName);
                                          PointerBuffer props = (PointerBuffer)stack.mallocPointer(3).put(4228L).put(platform).put(0L).flip();
                                          this.context = CL10.clCreateContext(props, this.deviceId, null, 0L, null);
                                          this.commandQueueHive = CL10.clCreateCommandQueue(this.context, this.deviceId, 0L, (IntBuffer)null);
                                          this.commandQueueEcho = CL10.clCreateCommandQueue(this.context, this.deviceId, 0L, (IntBuffer)null);
                                          this.commandQueueAura = CL10.clCreateCommandQueue(this.context, this.deviceId, 0L, (IntBuffer)null);
                                          break;
                                       }
                                    }

                                    if (this.deviceId != 0L) {
                                       break;
                                    }
                                 }
                              }

                              if (this.deviceId == 0L) {
                                 System.err.println("[Laminar] Failed to find iGPU!");
                                 break label166;
                              }

                              for (int i = 0; i < 2; i++) {
                                 this.clCostMemObj[i] = CL10.clCreateBuffer(this.context, 17L, 262144L, null);
                                 this.clIntegrationMemObj[i] = CL10.clCreateBuffer(this.context, 1L, 1048576L, null);
                                 this.clVectorMemObj[i] = CL10.clCreateBuffer(this.context, 17L, 786432L, null);
                                 this.pMappedCostBuffer[i] = CL10.clEnqueueMapBuffer(
                                    this.commandQueueHive, this.clCostMemObj[i], true, 2L, 0L, 262144L, null, null, (IntBuffer)null, null
                                 );
                                 this.pMappedVectorBuffer[i] = CL10.clEnqueueMapBuffer(
                                    this.commandQueueHive, this.clVectorMemObj[i], true, 1L, 0L, 786432L, null, null, (IntBuffer)null, null
                                 );
                                 if (this.pMappedCostBuffer[i] == null || this.pMappedVectorBuffer[i] == null) {
                                    System.err.println("[Laminar] FATAL: FAILED TO MAP HIVE BUFFER INDEX " + i);
                                    break label165;
                                 }

                                 this.pMappedCostBuffer[i].order(ByteOrder.nativeOrder());
                                 this.pMappedVectorBuffer[i].order(ByteOrder.nativeOrder());
                              }

                              this.clConvergenceMemObj = CL10.clCreateBuffer(this.context, 1L, 4L, null);
                              this.clEchoCostMemObj = CL10.clCreateBuffer(this.context, 17L, 262144L, null);
                              this.pMappedEchoCostBuffer = CL10.clEnqueueMapBuffer(
                                 this.commandQueueEcho, this.clEchoCostMemObj, true, 2L, 0L, 262144L, null, null, (IntBuffer)null, null
                              );
                              if (this.pMappedEchoCostBuffer == null) {
                                 System.err.println("[Laminar] FATAL: FAILED TO MAP ECHO COST BUFFER!");
                                 break label164;
                              }

                              this.pMappedEchoCostBuffer.order(ByteOrder.nativeOrder());
                              this.clAcousticResultMemObj = CL10.clCreateBuffer(this.context, 17L, 2048L, null);
                              this.pMappedAcousticBuffer = CL10.clEnqueueMapBuffer(
                                 this.commandQueueEcho, this.clAcousticResultMemObj, true, 1L, 0L, 2048L, null, null, (IntBuffer)null, null
                              );
                              if (this.pMappedAcousticBuffer == null) {
                                 System.err.println("[Laminar] FATAL: FAILED TO MAP ACOUSTIC RESULT BUFFER!");
                                 break label163;
                              }

                              this.pMappedAcousticBuffer.order(ByteOrder.nativeOrder());
                              this.clAuraInputMemObj = CL10.clCreateBuffer(this.context, 20L, 16384L, null);
                              this.clAuraOutputMemObj = CL10.clCreateBuffer(this.context, 17L, 1024L, null);
                              this.pMappedAuraInputBuffer = CL10.clEnqueueMapBuffer(
                                 this.commandQueueAura, this.clAuraInputMemObj, true, 2L, 0L, 16384L, null, null, (IntBuffer)null, null
                              );
                              this.pMappedAuraOutputBuffer = CL10.clEnqueueMapBuffer(
                                 this.commandQueueAura, this.clAuraOutputMemObj, true, 1L, 0L, 1024L, null, null, (IntBuffer)null, null
                              );
                              if (this.pMappedAuraInputBuffer != null && this.pMappedAuraOutputBuffer != null) {
                                 this.pMappedAuraInputBuffer.order(ByteOrder.nativeOrder());
                                 this.pMappedAuraOutputBuffer.order(ByteOrder.nativeOrder());
                                 this.initializeKernel = this.compileProgramAndCreateKernel("/assets/laminar/kernels/hive.cl", "initialize_integration_field");
                                 this.solveIntegrationKernel = this.compileProgramAndCreateKernel("/assets/laminar/kernels/hive.cl", "solve_integration_field");
                                 this.generateFlowKernel = this.compileProgramAndCreateKernel("/assets/laminar/kernels/hive.cl", "generate_flow_field");
                                 this.echoKernel = this.compileProgramAndCreateKernel("/assets/laminar/kernels/echo.cl", "calculate_acoustics");
                                 this.auraKernel = this.compileProgramAndCreateKernel("/assets/laminar/kernels/aura.cl", "calculate_aura");
                                 System.out.println("[Laminar] ALL BUFFER & TRIPLE-QUEUE SUCCESSFULLY MAPPED!");
                                 break label167;
                              }

                              System.err.println("[Laminar] FATAL: FAILED TO MAP AURA BUFFER!");
                           } catch (Throwable var18) {
                              if (stack != null) {
                                 try {
                                    stack.close();
                                 } catch (Throwable var17) {
                                    var18.addSuppressed(var17);
                                 }
                              }

                              throw var18;
                           }

                           if (stack != null) {
                              stack.close();
                           }

                           return;
                        }

                        if (stack != null) {
                           stack.close();
                        }

                        return;
                     }

                     if (stack != null) {
                        stack.close();
                     }

                     return;
                  }

                  if (stack != null) {
                     stack.close();
                  }

                  return;
               }

               if (stack != null) {
                  stack.close();
               }

               return;
            }

            if (stack != null) {
               stack.close();
            }

            return;
         }

         if (stack != null) {
            stack.close();
         }
      } catch (Throwable t) {
         this.context = 0L;
         System.err.println("[Laminar] WARNING: Failed to initialize OpenCL on this operating system. Switching to CPU Fallback!");
         System.err.println("[Laminar] System error details: " + t.getMessage());
      }
   }

   public static String getLockedGpuName() {
      return lockedGpuName;
   }

   private long compileProgramAndCreateKernel(String path, String kernelName) {
      InputStream stream = this.getClass().getResourceAsStream(path);
      if (stream == null) {
         System.err.println("[Laminar] Kernel not found: " + path);
         return 0L;
      }

      String source = new BufferedReader(new InputStreamReader(stream)).lines().collect(Collectors.joining("\n"));
      long prog = CL10.clCreateProgramWithSource(this.context, source, null);
      int buildResult = CL10.clBuildProgram(prog, this.deviceId, "", null, 0L);
      if (buildResult != 0) {
         MemoryStack stack = MemoryStack.stackPush();

         try {
            PointerBuffer logSize = stack.mallocPointer(1);
            CL10.clGetProgramBuildInfo(prog, this.deviceId, 4483, (ByteBuffer)null, logSize);
            ByteBuffer logBuf = stack.malloc((int)logSize.get(0));
            CL10.clGetProgramBuildInfo(prog, this.deviceId, 4483, logBuf, null);
            System.err.println("[Laminar] FAILED TO COMPILE KERNEL: " + path);
            System.err.println("[Laminar] BUILD LOG:\n" + MemoryUtil.memUTF8(logBuf).trim());
         } catch (Throwable var12) {
            if (stack != null) {
               try {
                  stack.close();
               } catch (Throwable var11) {
                  var12.addSuppressed(var11);
               }
            }

            throw var12;
         }

         if (stack != null) {
            stack.close();
         }

         return 0L;
      } else {
         return CL10.clCreateKernel(prog, kernelName, (IntBuffer)null);
      }
   }

   public int getCpuWriteIndex() {
      return this.currentPingPongIndex;
   }

   public void swapPingPongBuffers() {
      this.currentPingPongIndex = 1 - this.currentPingPongIndex;
   }

   public int getGpuReadIndex() {
      return 1 - this.currentPingPongIndex;
   }

   public ByteBuffer getMappedCostBuffer(int index) {
      return this.pMappedCostBuffer[index];
   }

   public ByteBuffer getMappedVectorBuffer(int index) {
      return this.pMappedVectorBuffer[index];
   }

   public ByteBuffer getMappedEchoCostBuffer() {
      return this.pMappedEchoCostBuffer;
   }

   public ByteBuffer getMappedAcousticBuffer() {
      return this.pMappedAcousticBuffer;
   }

   public ByteBuffer getMappedAuraInputBuffer() {
      return this.pMappedAuraInputBuffer;
   }

   public ByteBuffer getMappedAuraOutputBuffer() {
      return this.pMappedAuraOutputBuffer;
   }

   public void runFlowFieldCalculation(int targetLocalIdx, int gpuIndex) {
      if (this.deviceId != 0L && this.initializeKernel != 0L && this.solveIntegrationKernel != 0L && this.generateFlowKernel != 0L) {
         try {
            MemoryStack stack = MemoryStack.stackPush();

            try {
               PointerBuffer gws = (PointerBuffer)stack.mallocPointer(1).put(262144L).flip();
               PointerBuffer lws = (PointerBuffer)stack.mallocPointer(1).put(256L).flip();
               CL10.clSetKernelArg1p(this.initializeKernel, 0, this.clIntegrationMemObj[gpuIndex]);
               CL10.clSetKernelArg1i(this.initializeKernel, 1, 64);
               CL10.clEnqueueNDRangeKernel(this.commandQueueHive, this.initializeKernel, 1, null, gws, lws, null, null);
               CL10.clSetKernelArg1p(this.solveIntegrationKernel, 0, this.clCostMemObj[gpuIndex]);
               CL10.clSetKernelArg1p(this.solveIntegrationKernel, 1, this.clIntegrationMemObj[gpuIndex]);
               CL10.clSetKernelArg1i(this.solveIntegrationKernel, 2, 64);
               CL10.clSetKernelArg1i(this.solveIntegrationKernel, 3, targetLocalIdx);
               CL10.clSetKernelArg1p(this.solveIntegrationKernel, 4, this.clConvergenceMemObj);

               for (int step = 0; step < 48; step++) {
                  CL10.clEnqueueNDRangeKernel(this.commandQueueHive, this.solveIntegrationKernel, 1, null, gws, null, null, null);
               }

               CL10.clSetKernelArg1p(this.generateFlowKernel, 0, this.clIntegrationMemObj[gpuIndex]);
               CL10.clSetKernelArg1p(this.generateFlowKernel, 1, this.clVectorMemObj[gpuIndex]);
               CL10.clSetKernelArg1i(this.generateFlowKernel, 2, 64);
               CL10.clEnqueueNDRangeKernel(this.commandQueueHive, this.generateFlowKernel, 1, null, gws, lws, null, null);
               CL10.clFlush(this.commandQueueHive);
            } catch (Throwable var9) {
               if (stack != null) {
                  try {
                     stack.close();
                  } catch (Throwable var8) {
                     var9.addSuppressed(var8);
                  }
               }

               throw var9;
            }

            if (stack != null) {
               stack.close();
            }
         } catch (Exception e) {
            System.err.println("[Laminar OCL] FATAL ERROR in runFlowFieldCalculation: " + e.getMessage());

            for (StackTraceElement element : e.getStackTrace()) {
               System.err.println("    at " + element);
            }
         }
      }
   }

   public void runAcousticsCalculation(
      int soundSlotIdx, float srcX, float srcY, float srcZ, float playX, float playY, float playZ, float maxRange, float airDecay, float materialPenetration
   ) {
      if (this.deviceId != 0L && this.echoKernel != 0L) {
         try {
            MemoryStack stack = MemoryStack.stackPush();

            try {
               CL10.clSetKernelArg1p(this.echoKernel, 0, this.clEchoCostMemObj);
               CL10.clSetKernelArg1p(this.echoKernel, 1, this.clAcousticResultMemObj);
               CL10.clSetKernelArg1i(this.echoKernel, 2, 64);
               CL10.clSetKernelArg1i(this.echoKernel, 3, soundSlotIdx);
               CL10.clSetKernelArg1f(this.echoKernel, 4, srcX);
               CL10.clSetKernelArg1f(this.echoKernel, 5, srcY);
               CL10.clSetKernelArg1f(this.echoKernel, 6, srcZ);
               CL10.clSetKernelArg1f(this.echoKernel, 7, playX);
               CL10.clSetKernelArg1f(this.echoKernel, 8, playY);
               CL10.clSetKernelArg1f(this.echoKernel, 9, playZ);
               CL10.clSetKernelArg1f(this.echoKernel, 10, maxRange);
               CL10.clSetKernelArg1f(this.echoKernel, 11, airDecay);
               CL10.clSetKernelArg1f(this.echoKernel, 12, materialPenetration);
               PointerBuffer gws = (PointerBuffer)stack.mallocPointer(1).put(16L).flip();
               PointerBuffer lws = (PointerBuffer)stack.mallocPointer(1).put(16L).flip();
               CL10.clEnqueueNDRangeKernel(this.commandQueueEcho, this.echoKernel, 1, null, gws, lws, null, null);
               CL10.clFlush(this.commandQueueEcho);
            } catch (Throwable var17) {
               if (stack != null) {
                  try {
                     stack.close();
                  } catch (Throwable var16) {
                     var17.addSuppressed(var16);
                  }
               }

               throw var17;
            }

            if (stack != null) {
               stack.close();
            }
         } catch (Exception e) {
            System.err.println("[Laminar OCL] FATAL ERROR in runAcousticsCalculation: " + e.getMessage());

            for (StackTraceElement element : e.getStackTrace()) {
               System.err.println("    at " + element);
            }
         }
      }
   }

   public void runAuraCalculation(int activeCount, float playerEyeX, float playerEyeY, float playerEyeZ, int gpuIndex) {
      if (this.deviceId != 0L && this.auraKernel != 0L && activeCount > 0) {
         try {
            MemoryStack stack = MemoryStack.stackPush();

            try {
               CL10.clSetKernelArg1p(this.auraKernel, 0, this.clCostMemObj[gpuIndex]);
               CL10.clSetKernelArg1p(this.auraKernel, 1, this.clAuraInputMemObj);
               CL10.clSetKernelArg1p(this.auraKernel, 2, this.clAuraOutputMemObj);
               CL10.clSetKernelArg1f(this.auraKernel, 3, playerEyeX);
               CL10.clSetKernelArg1f(this.auraKernel, 4, playerEyeY);
               CL10.clSetKernelArg1f(this.auraKernel, 5, playerEyeZ);
               CL10.clSetKernelArg1i(this.auraKernel, 6, 64);
               CL10.clSetKernelArg1i(this.auraKernel, 7, activeCount);
               PointerBuffer gws = (PointerBuffer)stack.mallocPointer(1).put(activeCount).flip();
               CL10.clEnqueueNDRangeKernel(this.commandQueueAura, this.auraKernel, 1, null, gws, null, null, null);
               CL10.clFlush(this.commandQueueAura);
            } catch (Throwable var12) {
               if (stack != null) {
                  try {
                     stack.close();
                  } catch (Throwable var11) {
                     var12.addSuppressed(var11);
                  }
               }

               throw var12;
            }

            if (stack != null) {
               stack.close();
            }
         } catch (Exception e) {
            System.err.println("[Laminar OCL] FATAL ERROR in runAuraCalculation: " + e.getMessage());

            for (StackTraceElement element : e.getStackTrace()) {
               System.err.println("    at " + element);
            }
         }
      }
   }

   public void syncHive() {
      if (this.deviceId != 0L) {
         CL10.clFinish(this.commandQueueHive);
      }
   }

   public void syncEcho() {
      if (this.deviceId != 0L) {
         CL10.clFinish(this.commandQueueEcho);
      }
   }

   public void syncAura() {
      if (this.deviceId != 0L) {
         CL10.clFinish(this.commandQueueAura);
      }
   }
}

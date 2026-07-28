package com.izumi.laminar;

import com.izumi.laminar.opencl.LaminarOpenCLManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.ServerStopping;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.EndLevelTick;

public class Laminar implements ModInitializer {
   public static final String MOD_ID = "laminar";

   public void onInitialize() {
      System.out.println("[Laminar] Starting Laminar Engine initialization...");
      LaminarConfig.load();
      LaminarOpenCLManager.getInstance().initialize();
      ServerTickEvents.END_LEVEL_TICK.register((EndLevelTick)world -> {
         if (!world.players().isEmpty()) {
            FlowFieldHelper.tick(world);
         }
      });
      System.out.println("[Laminar] Mod loaded successfully! iGPU now assists CPU.");
      ServerLifecycleEvents.SERVER_STOPPING.register((ServerStopping)server -> FlowFieldHelper.clearAll());
   }
}

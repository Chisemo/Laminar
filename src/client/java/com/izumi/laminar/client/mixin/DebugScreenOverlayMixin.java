package com.izumi.laminar.client.mixin;

import com.izumi.laminar.FlowFieldHelper;
import com.izumi.laminar.client.AcousticEngine;
import com.izumi.laminar.opencl.LaminarOpenCLManager;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DebugScreenOverlay.class)
public abstract class DebugScreenOverlayMixin {
   @Unique
   private static final boolean laminar$isBetterF3Loaded = FabricLoader.getInstance().isModLoaded("betterf3");

   @Shadow
   public abstract boolean showDebugScreen();

   @Inject(method = {"extractLines", "method_1528"}, at = @At("HEAD"))
   private void onExtractLines(GuiGraphicsExtractor graphics, List<String> lines, boolean alignLeft, CallbackInfo ci) {
      if (!laminar$isBetterF3Loaded) {
         if (!alignLeft && this.showDebugScreen()) {
            lines.removeIf(
               s -> s != null
                  && (
                     s.contains("[Laminar Engine")
                        || s.contains(" iGPU :")
                        || s.contains(" Echo Engine:")
                        || s.contains(" Active Sound Slots:")
                        || s.contains(" Active AI Grids:")
                        || s.contains(" Active AI Mobs:")
                  )
            );
            if (!lines.isEmpty() && !lines.get(lines.size() - 1).isEmpty()) {
               lines.add("");
            }

            lines.add("[Laminar Engine v1.3.1]");
            String gpuName = LaminarOpenCLManager.getLockedGpuName();
            if (gpuName != null && !"None".equals(gpuName)) {
               lines.add(" iGPU : Locked on " + gpuName);
            } else {
               lines.add(" iGPU : Offline");
            }

            if (AcousticEngine.isEfxAvailable()) {
               lines.add(" Echo Engine: OpenAL EFX Active (Hardware Reverb)");
            } else {
               lines.add(" Echo Engine: Standard (Muffling Fallback)");
            }

            lines.add(" Active Sound Slots: " + AcousticEngine.getOcclusionRawCount() + " / 256");
            lines.add(" Active AI Grids: " + FlowFieldHelper.getLatestGridCenterCount());
            lines.add(" Active AI Mobs: " + FlowFieldHelper.getActivePursuerCount());
         }
      }
   }
}

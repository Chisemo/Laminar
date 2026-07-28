package com.izumi.laminar.client.mixin;

import com.izumi.laminar.FlowFieldHelper;
import com.izumi.laminar.client.AcousticEngine;
import com.izumi.laminar.opencl.LaminarOpenCLManager;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DebugScreenOverlay.class)
public class DebugScreenOverlayMixin {
   @Inject(method = {"extractLines", "method_1528"}, at = @At("HEAD"))
   private void onExtractLines(GuiGraphicsExtractor graphics, List<String> lines, boolean alignLeft, CallbackInfo ci) {
      if (!alignLeft) {
         if (!lines.isEmpty()) {
            lines.add("");
         }

         lines.add("[Laminar Engine v1.3.0]");
         String gpuName = LaminarOpenCLManager.getLockedGpuName();
         if (!"None".equals(gpuName)) {
            lines.add(" iGPU Co-Processor: " + gpuName);
         } else {
            lines.add(" iGPU Co-Processor: Offline");
         }

         if (AcousticEngine.isEfxAvailable()) {
            lines.add(" Echo Engine: OpenAL EFX Active (Hardware Reverb)");
         } else {
            lines.add(" Echo Engine: Standard (Muffling Fallback)");
         }

         int activeSounds = AcousticEngine.getOcclusionRawCount();
         lines.add(" Active Sound Slots: " + activeSounds + " / 256");
         int activeGrids = FlowFieldHelper.getLatestGridCenterCount();
         lines.add(" Active AI Grids: " + activeGrids);
         int activeMobs = FlowFieldHelper.getActivePursuerCount();
         lines.add(" Active AI Mobs: " + activeMobs);
      }
   }
}

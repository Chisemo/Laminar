package com.izumi.laminar;

import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;

public class LaminarPreLaunch implements PreLaunchEntrypoint {
   public void onPreLaunch() {
      if (FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER) {
         boolean needsLwjgl = false;

         try {
            Class.forName("org.lwjgl.system.MemoryUtil", false, LaminarPreLaunch.class.getClassLoader());
         } catch (Throwable t) {
            needsLwjgl = true;
         }

         if (needsLwjgl) {
            System.out.println("[Laminar] Headless Server terdeteksi. Pustaka LWJGL tidak ditemukan.");
            System.out.println("[Laminar] Mengunduh pustaka native yang dibutuhkan dari Maven Central...");
            this.downloadAndInjectLibraries();
         } else {
            System.out.println("[Laminar] Pustaka LWJGL terdeteksi aman.");
         }
      }
   }

   private void downloadAndInjectLibraries() {
      String baseUrl = "https://repo1.maven.org/maven2/org/lwjgl/";
      String version = "3.3.3";
      String[] libs = new String[]{
         "lwjgl/" + version + "/lwjgl-" + version + ".jar",
         "lwjgl/" + version + "/lwjgl-" + version + "-natives-windows.jar",
         "lwjgl/" + version + "/lwjgl-" + version + "-natives-linux.jar",
         "lwjgl-opencl/" + version + "/lwjgl-opencl-" + version + ".jar"
      };
      Path libsDir = FabricLoader.getInstance().getGameDir().resolve("laminar_libs");

      try {
         if (!Files.exists(libsDir)) {
            Files.createDirectories(libsDir);
         }

         for (String lib : libs) {
            String fileName = lib.substring(lib.lastIndexOf(47) + 1);
            Path targetFile = libsDir.resolve(fileName);
            if (!Files.exists(targetFile)) {
               System.out.println("[Laminar] Mengunduh: " + fileName);

               try (InputStream in = new URL(baseUrl + lib).openStream()) {
                  Files.copy(in, targetFile, StandardCopyOption.REPLACE_EXISTING);
               }
            }

            FabricLauncherBase.getLauncher().addToClassPath(targetFile, new String[0]);
         }

         System.out.println("[Laminar] Injeksi pustaka Native LWJGL berhasil! Melanjutkan startup server...");
      } catch (Exception ex) {
         throw new RuntimeException("[Laminar FATAL] Gagal mengunduh atau menyuntikkan pustaka LWJGL!", ex);
      }
   }
}

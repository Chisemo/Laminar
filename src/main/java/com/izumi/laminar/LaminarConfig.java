package com.izumi.laminar;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import net.fabricmc.loader.api.FabricLoader;

public class LaminarConfig {
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
   private static final File CONFIG_FILE = FabricLoader.getInstance().getConfigDir().resolve("laminar.json").toFile();
   private static LaminarConfig.ConfigData data = new LaminarConfig.ConfigData();

   public static void load() {
      try {
         if (CONFIG_FILE.exists()) {
            try (FileReader reader = new FileReader(CONFIG_FILE)) {
               data = (LaminarConfig.ConfigData)GSON.fromJson(reader, LaminarConfig.ConfigData.class);
               if (data == null) {
                  data = new LaminarConfig.ConfigData();
                  save();
               }
            }
         } else {
            save();
         }

         System.out.println("[Laminar] Configuration loaded successfully.");
      } catch (Exception e) {
         System.err.println("[Laminar] Failed to load configuration file! Using default settings.");
         e.printStackTrace();
      }
   }

   public static void save() {
      try {
         CONFIG_FILE.getParentFile().mkdirs();

         try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
            GSON.toJson(data, writer);
         }
      } catch (Exception e) {
         System.err.println("[Laminar] Failed to save configuration file.");
         e.printStackTrace();
      }
   }

   public static boolean isSwarmAlertEnabled() {
      return data != null && data.enableSwarmAlert;
   }

   public static boolean isAcousticOcclusionEnabled() {
      return data == null || !data.enableAcousticOcclusion;
   }

   public static class ConfigData {
      public boolean enableSwarmAlert = true;
      public boolean enableAcousticOcclusion = true;
   }
}

/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package dev.blocknet.pathing.utils;

import dev.blocknet.pathing.Pathing;
import dev.blocknet.pathing.api.Settings;
import dev.blocknet.pathing.api.utils.SettingsUtil;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SettingsLoader {
   private static final Pattern SETTING_PATTERN = Pattern.compile("^(?<setting>[^ ]+) +(?<value>.+)");
   private static final Path SETTINGS_PATH = DirUtil.getConfigDir().resolve("blocknet_ai").resolve("pathing-settings.txt");

   public static void readAndApply(Settings settings) {
      try {
         Files.lines(SETTINGS_PATH).filter(line -> !line.trim().isEmpty() && !isComment(line)).forEach(line -> {
            Matcher matcher = SETTING_PATTERN.matcher(line);
            if (!matcher.matches()) {
               Pathing.LOGGER.error("Invalid syntax in setting file: " + line);
            } else {
               String settingName = matcher.group("setting").toLowerCase();
               String settingValue = matcher.group("value");

               try {
                  SettingsUtil.parseAndApply(settings, settingName, settingValue);
               } catch (Exception var6) {
                  Pathing.LOGGER.error("Unable to parse line " + line, var6);
               }
            }
         });
      } catch (NoSuchFileException var4) {
         Pathing.LOGGER.info("Automatone settings file not found, resetting.");

         try {
            Files.createDirectories(SETTINGS_PATH.getParent());
            Files.createFile(SETTINGS_PATH);
         } catch (IOException var3) {
         }
      } catch (Exception var5) {
         Pathing.LOGGER.error("Exception while reading Automatone settings, some settings may be reset to default values!", var5);
      }
   }

   private static boolean isComment(String line) {
      return line.startsWith("#") || line.startsWith("//");
   }

   public static synchronized void save(Settings settings) {
      try {
         Files.createDirectories(SETTINGS_PATH.getParent());
      } catch (IOException e) {
         Pathing.LOGGER.error("Could not create Automatone settings directory", e);
      }
      try (BufferedWriter out = Files.newBufferedWriter(SETTINGS_PATH)) {
         for (Settings.Setting<?> setting : SettingsUtil.modifiedSettings(settings)) {
            out.write(SettingsUtil.settingToString(setting) + "\n");
         }
      } catch (Exception var6) {
         Pathing.LOGGER.error("Exception thrown while saving Automatone settings!", var6);
      }
   }
}

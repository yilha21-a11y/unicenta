//    uniCenta oPOS  - Touch Friendly Point Of Sale
//    Copyright (c) 2009-2018 uniCenta
//    https://unicenta.com
//
//    This file is part of uniCenta oPOS
//
//    uniCenta oPOS is free software: you can redistribute it and/or modify
//    it under the terms of the GNU General Public License as published by
//    the Free Software Foundation, either version 3 of the License, or
//    (at your option) any later version.
//
//   uniCenta oPOS is distributed in the hope that it will be useful,
//    but WITHOUT ANY WARRANTY; without even the implied warranty of
//    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//    GNU General Public License for more details.
//
//    You should have received a copy of the GNU General Public License
//    along with uniCenta oPOS.  If not, see <http://www.gnu.org/licenses/>

package com.unicenta.pos.forms;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.intellijthemes.FlatCarbonIJTheme;
import com.formdev.flatlaf.intellijthemes.FlatDraculaIJTheme;
import com.formdev.flatlaf.intellijthemes.FlatMaterialDesignDarkIJTheme;
import com.formdev.flatlaf.intellijthemes.materialthemeuilite.FlatArcDarkIJTheme;
import com.formdev.flatlaf.intellijthemes.materialthemeuilite.FlatAtomOneLightIJTheme;
import com.formdev.flatlaf.intellijthemes.materialthemeuilite.FlatGitHubIJTheme;
import com.formdev.flatlaf.intellijthemes.materialthemeuilite.FlatMaterialLighterIJTheme;
import com.unicenta.format.Formats;
import com.unicenta.pos.instance.InstanceQuery;
import com.unicenta.pos.ticket.TicketInfo;
import lombok.extern.slf4j.Slf4j;
import org.pushingpixels.substance.api.SubstanceLookAndFeel;
import org.pushingpixels.substance.api.SubstanceSkin;

import javax.swing.*;
import javax.swing.plaf.metal.MetalLookAndFeel;
import java.io.IOException;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.util.Locale;

@Slf4j
public class StartPOS {

  private StartPOS() {
  }

  public static boolean registerApp() {

    InstanceQuery i = null;
    try {
      i = new InstanceQuery();
      i.getAppMessage().restoreWindow();
      return false;
    } catch (RemoteException | NotBoundException e) {
      return true;
    }
  }

  /**
   * OLPOS : applique le reglage {@code user.timezone} du fichier de
   * configuration (ex. America/Los_Angeles). Vide ou absent : Java suit Windows.
   * Un identifiant inconnu est ignore.
   */
  public static java.util.TimeZone applyTimeZone(AppProperties config) {
    String id = config.getProperty("user.timezone");
    if (id == null || id.trim().isEmpty()) {
      return null;
    }
    id = id.trim();
    if (!java.time.ZoneId.getAvailableZoneIds().contains(id)) {
      log.warn("Fuseau horaire inconnu dans user.timezone : {} (ignore)", id);
      return null;
    }
    java.util.TimeZone tz = java.util.TimeZone.getTimeZone(id);
    java.util.TimeZone.setDefault(tz);
    return tz;
  }

  public static void main(final String args[]) {

    // OLPOS : journal Derby dans le dossier de donnees (le dossier du programme
    // n'est pas accessible en ecriture), jamais dans le dossier courant.
    if (System.getProperty("derby.stream.error.file") == null) {
      System.setProperty("derby.stream.error.file",
              new java.io.File(AppConfig.getDataDir(), "derby.log").getAbsolutePath());
    }

    SwingUtilities.invokeLater(() -> {
      if (!registerApp()) {
        System.exit(1);
      }

      AppConfig config = new AppConfig(args);
      AppConfig.setActiveConfigFile(config.getConfigFile());   // OLPOS

      config.load();
      applyTimeZone(config);   // OLPOS

      String slang = config.getProperty("user.language");
      String scountry = config.getProperty("user.country");
      String svariant = config.getProperty("user.variant");
      if (slang != null
              && !slang.equals("")
              && scountry != null
              && svariant != null) {
        Locale.setDefault(new Locale(slang, scountry, svariant));
      }

      Formats.setIntegerPattern(config.getProperty("format.integer"));
      Formats.setDoublePattern(config.getProperty("format.double"));
      Formats.setCurrencyPattern(config.getProperty("format.currency"));
      Formats.setPercentPattern(config.getProperty("format.percent"));
      Formats.setDatePattern(config.getProperty("format.date"));
      Formats.setTimePattern(config.getProperty("format.time"));
      Formats.setDateTimePattern(config.getProperty("format.datetime"));

      // Set the look and feel
      try {
        Object laf = Class.forName(config.getProperty("swing.defaultlaf")).newInstance();
        if (!(laf instanceof MetalLookAndFeel) && laf instanceof LookAndFeel) {
          UIManager.setLookAndFeel((LookAndFeel) laf);
        } else {
          UIManager.setLookAndFeel("com.formdev.flatlaf.intellijthemes.FlatGrayIJTheme");
        }
      } catch (Exception e) {
        log.error("Cannot set Look and Feel ${0}", e.getMessage());
      }

      String hostname = config.getProperty("machine.hostname");
      TicketInfo.setHostname(hostname);
      // OLPOS : pas d'envoi du nom du PC et de la version a api.unicenta.io
      // (uniCenta 5.0 le faisait a chaque demarrage, sans le dire)

      String screenmode = config.getProperty("machine.screenmode");

      if ("fullscreen".equals(screenmode)) {
        JRootKiosk rootkiosk = new JRootKiosk();
        try {
          rootkiosk.initFrame(config);
        } catch (IOException ex) {
          log.error(ex.getMessage());
        }
      } else {
        JRootFrame rootframe = new JRootFrame();
        try {
          rootframe.initFrame(config);
        } catch (Exception ex) {
          log.error(ex.getMessage());
        }
      }
    });
  }
}
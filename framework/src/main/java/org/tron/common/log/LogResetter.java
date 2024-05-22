package org.tron.common.log;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import com.google.common.base.Strings;
import java.io.File;
import org.slf4j.LoggerFactory;

public class LogResetter {

  public static void load(String path) {
    if (Strings.isNullOrEmpty(path)) {
      return;
    }

    File file = new File(path);
    if (!file.exists() || !file.isFile() || !file.canRead()) {
      throw new IllegalArgumentException("Logback config file not found or not readable: "
          + path);
    }
    try {
      LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
      JoranConfigurator configurator = new JoranConfigurator();
      configurator.setContext(lc);
      lc.reset();
      configurator.doConfigure(file);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to load logback configuration file: " + path, e);
    }
  }
}

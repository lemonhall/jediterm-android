package com.jediterm;

import org.jetbrains.annotations.NotNull;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * @author traff
 */
public class TestPathsManager {
  public static @NotNull Path getTestDataPath() {
    URL url = TestPathsManager.class.getClassLoader().getResource("testData");
    if (url == null) {
      throw new IllegalStateException("Cannot find testData in test resources");
    }
    try {
      return Paths.get(url.toURI()).toAbsolutePath().normalize();
    }
    catch (URISyntaxException e) {
      throw new IllegalStateException("Invalid testData URL: " + url, e);
    }
  }
}

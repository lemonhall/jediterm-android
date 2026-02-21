package com.jediterm.terminal;

import com.jediterm.core.util.TermSize;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public interface TtyConnector {
  int read(char[] buf, int offset, int length) throws IOException;

  void write(byte[] bytes) throws IOException;

  void write(String string) throws IOException;

  boolean isConnected();

  default void resize(@NotNull TermSize termSize) {
    // no-op by default (Android port must not depend on java.awt.*)
  }

  boolean ready() throws IOException;

  String getName();

  void close();

  /**
   * @deprecated Collect extra information when creating {@link TtyConnector}
   */
  @SuppressWarnings("removal")
  @Deprecated(forRemoval = true)
  default boolean init(Questioner q) {
    return true;
  }
}

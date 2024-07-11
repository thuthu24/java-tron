package org.tron.common.context;

import java.util.Optional;

public class GlobalContext {

  private static final ThreadLocal<Long> HEADER = new ThreadLocal<>();

  private GlobalContext() {
  }

  public static Optional<Long> getHeader() {
    return Optional.ofNullable(HEADER.get());
  }

  public static void setHeader(long header) {
    HEADER.set(header);
  }

  public static void removeHeader() {
    HEADER.remove();
  }
}

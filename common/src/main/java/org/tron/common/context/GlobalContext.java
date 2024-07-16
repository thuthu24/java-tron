package org.tron.common.context;

import java.util.Optional;

public class GlobalContext {

  private static final ThreadLocal<Long> HEADER = new ThreadLocal<>();

  private static final ThreadLocal<Boolean> LOG = ThreadLocal.withInitial(() -> true);

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

  public static boolean isLog() {
    return LOG.get();
  }

  public static void enableLog() {
    LOG.set(true);
  }

  public static void disableLog() {
    LOG.set(false);
  }
}

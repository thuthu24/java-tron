package org.tron.common.logs;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;

@Slf4j(topic = "trx")
public class TrxLog {


  private TrxLog() {
    throw new IllegalStateException("TrxLog");
  }

  public static Logger getLogger() {
    return logger;
  }
}

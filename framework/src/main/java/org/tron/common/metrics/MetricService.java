package org.tron.common.metrics;

import org.tron.common.prometheus.Metrics;

public class MetricService {

  public static void startPrometheus() {
    try {
      Metrics.init();
    } catch (Exception e) {
      throw new IllegalStateException("start Prometheus service failed.", e);
    }
  }
}

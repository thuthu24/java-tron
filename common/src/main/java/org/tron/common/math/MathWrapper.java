package org.tron.common.math;

import lombok.extern.slf4j.Slf4j;

@Slf4j(topic = "math")
public final class MathWrapper {

  private MathWrapper() {
    throw new UnsupportedOperationException("MathWrapper should not be instantiated");
  }

  public static double ceil(double a) {
    double ret = StrictMath.ceil(a);
    double base = Math.ceil(a);
    if (Double.compare(ret, base) != 0) {
      logger.info("ceil\t{}\t{}\t{}", doubleToHex(a), doubleToHex(base), doubleToHex(ret));
    }
    return base;
  }

  public static double pow(double a, double b) {
    double base = Math.pow(a, b);
    double strict = StrictMath.pow(a, b);
    if (Double.compare(base, strict) != 0) {
      logger.info("pow\t{}\t{}\t{}", doubleToHex(a), doubleToHex(base), doubleToHex(strict));
    }
    return base;
  }

  public static String doubleToHex(double input) {
    // Convert the starting value to the equivalent value in a long
    long doubleAsLong = Double.doubleToRawLongBits(input);
    // and then convert the long to a hex string
    return Long.toHexString(doubleAsLong);
  }
}

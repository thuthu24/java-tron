package org.tron.plugins.comparator;

import org.rocksdb.ComparatorOptions;
import org.rocksdb.AbstractComparator;
import org.tron.plugins.utils.MarketUtils;

import java.nio.ByteBuffer;

public  class MarketOrderPriceComparatorForRockDB extends AbstractComparator {

  public MarketOrderPriceComparatorForRockDB(final ComparatorOptions copt) {
    super(copt);
  }

  @Override
  public String name() {
    return "MarketOrderPriceComparator";
  }

  @Override
  public int compare(final ByteBuffer a, final ByteBuffer b) {
    return MarketUtils.comparePriceKey(convertDataToBytes(a), convertDataToBytes(b));
  }

  /**
   * DirectSlice.data().array will throw UnsupportedOperationException.
   * */
  public byte[] convertDataToBytes(ByteBuffer buf) {
    byte[] bytes = new byte[buf.remaining()];
    buf.get(bytes);
    return bytes;
  }

}
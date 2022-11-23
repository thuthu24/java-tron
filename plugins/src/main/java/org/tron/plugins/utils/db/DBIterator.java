package org.tron.plugins.utils.db;

import java.io.Closeable;
import java.util.Iterator;
import java.util.Map;

public interface DBIterator extends Iterator<Map.Entry<byte[], byte[]>>, Closeable {

  void seek(byte[] key);

  void seekToFirst();

  boolean hasNext();

  byte[] getKey();

  byte[] getValue();
}

package org.tron.core.service;

import com.google.common.collect.Streams;
import com.google.common.primitives.Bytes;
import com.google.common.primitives.Ints;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.MerkleRoot;
import org.tron.common.utils.Pair;
import org.tron.common.utils.Sha256Hash;

@Slf4j(topic = "DB")
public class RootHashService {

  private static final byte[] HEADER_KEY = Bytes.concat(simpleEncode("properties"),
    "latest_block_header_number".getBytes());

  public static Pair<Optional<Long>, Sha256Hash> getRootHash(Map<byte[], byte[]> rows) {
    List<Sha256Hash> ids = Collections.synchronizedList(new ArrayList<>());
    AtomicReference<Optional<Long>> height = new AtomicReference<>(Optional.empty());
    Streams.stream(rows.entrySet()).parallel().forEach(entry -> {
      if (Arrays.equals(HEADER_KEY, entry.getKey())) {
        height.set(Optional.of(ByteArray.toLong(entry.getValue())));
      }
      ids.add(getHash(entry));
    });
    Sha256Hash root = MerkleRoot.root(ids);
    logger.info("blockNum: {}, stateRoot: {}",
        height.get().orElseThrow(() -> new IllegalStateException("blockNum is null")), root);
    return new Pair<>(height.get(), root);
  }

  private static Sha256Hash getHash(Map.Entry<byte[], byte[]> entry) {
    return  Sha256Hash.of(true, Bytes.concat(entry.getKey(), entry.getValue()));
  }

  private static byte[] simpleEncode(String s) {
    byte[] bytes = s.getBytes();
    byte[] length = Ints.toByteArray(bytes.length);
    byte[] r = new byte[4 + bytes.length];
    System.arraycopy(length, 0, r, 0, 4);
    System.arraycopy(bytes, 0, r, 4, bytes.length);
    return r;
  }
}

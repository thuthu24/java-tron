package org.tron.core.state.store;

import com.google.common.cache.CacheLoader;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.function.Predicate;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.ethereum.trie.KeyValueMerkleStorage;
import org.hyperledger.besu.ethereum.trie.MerkleTrieException;
import org.hyperledger.besu.storage.KeyValueStorage;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;
import org.tron.common.cache.CacheManager;
import org.tron.common.cache.CacheType;
import org.tron.common.cache.TronCache;
import org.tron.core.state.StateType;
import org.tron.core.state.annotation.NeedWorldStateTrieStoreCondition;

@Component
@Conditional(NeedWorldStateTrieStoreCondition.class)
public class KeyValueMerkleCacheStorage extends KeyValueMerkleStorage {

  private final TronCache<Bytes32, Optional<Bytes>> cache;

  private final Predicate<Bytes> isCached;

  private final byte stateTypeLen = Byte.BYTES << 1;

  @Autowired
  public KeyValueMerkleCacheStorage(@Autowired KeyValueStorage keyValueStorage) {
    super(keyValueStorage);
    cache = CacheManager.allocate(CacheType.worldStateTrie,
        new CacheLoader<Bytes32, Optional<Bytes>>() {
          @Override
          public Optional<Bytes> load(@NotNull Bytes32 key) {
            return get(key);
          }
        }, (key, value) -> Bytes32.SIZE + value.orElse(Bytes.EMPTY).size());
    isCached = location -> {
      if (location.size() < stateTypeLen) {
        return false;
      }
      int type = location.slice(0, stateTypeLen).toInt();
      StateType s = StateType.get((byte) type);
      switch (s) {
        case DelegatedResource:
        case DelegatedResourceAccountIndex:
        case Code:
        case Contract:
        case Votes:
          return true;
        default:
          return false;
      }
    };
  }

  @Override
  public Optional<Bytes> get(final Bytes location, final Bytes32 hash) {
    try {
      if (isCached.test(location)) {
        return cache.get(hash);
      }
      return get(hash);
    } catch (ExecutionException e) {
      throw new MerkleTrieException(e.getMessage(), hash, location);
    }
  }


  private Optional<Bytes> get(final Bytes32 hash) {
    return pendingUpdates.containsKey(hash)
        ? Optional.of(pendingUpdates.get(hash))
        : keyValueStorage.get(hash.toArrayUnsafe()).map(Bytes::wrap);
  }

  @Override
  public void put(final Bytes location, final Bytes32 hash, final Bytes value) {
    super.put(location, hash, value);
    if (isCached.test(location)) {
      cache.put(hash, Optional.of(value));
    }
  }
}

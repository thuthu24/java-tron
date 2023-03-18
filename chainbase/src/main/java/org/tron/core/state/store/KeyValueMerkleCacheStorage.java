package org.tron.core.state.store;

import org.hyperledger.besu.ethereum.trie.KeyValueMerkleStorage;
import org.hyperledger.besu.storage.KeyValueStorage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import org.tron.core.state.annotation.NeedWorldStateTrieStoreCondition;

@Component
@Conditional(NeedWorldStateTrieStoreCondition.class)
public class KeyValueMerkleCacheStorage extends KeyValueMerkleStorage {

  @Autowired
  public KeyValueMerkleCacheStorage(@Autowired KeyValueStorage keyValueStorage) {
    super(keyValueStorage);
  }
}

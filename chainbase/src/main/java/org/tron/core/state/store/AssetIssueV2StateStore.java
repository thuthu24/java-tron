package org.tron.core.state.store;

import lombok.extern.slf4j.Slf4j;
import org.rocksdb.DirectComparator;
import org.tron.core.capsule.AssetIssueCapsule;
import org.tron.core.db2.common.WrappedByteArray;
import org.tron.core.db2.core.Chainbase;
import org.tron.core.state.WorldStateQueryInstance;
import org.tron.core.store.AssetIssueV2Store;

import java.util.Iterator;
import java.util.Map;

@Slf4j(topic = "DB")

public class AssetIssueV2StateStore extends AssetIssueV2Store implements StateStore {

  private WorldStateQueryInstance worldStateQueryInstance;


  public AssetIssueV2StateStore(WorldStateQueryInstance worldStateQueryInstance) {
    this.worldStateQueryInstance = worldStateQueryInstance;
  }

  //****  Override Operation For StateDB

  @Override
  public String getDbName() {
    return worldStateQueryInstance.getRootHash().toHexString();
  }

  @Override
  public AssetIssueCapsule get(byte[] key) {
    return getFromRoot(key);
  }

  @Override
  public AssetIssueCapsule getFromRoot(byte[] key) {
    return getUnchecked(key);

  }

  @Override
  public AssetIssueCapsule getUnchecked(byte[] key) {
    return worldStateQueryInstance.getAssetIssue(key);
  }

  @Override
  public boolean has(byte[] key) {
    return getUnchecked(key) != null;
  }

  @Override
  public void close() {
    this.worldStateQueryInstance = null;
  }

  @Override
  public void reset() {
  }

  //****  Override Operation For StateDB

  //****  Unsupported Operation For StateDB

  protected org.iq80.leveldb.Options getOptionsByDbNameForLevelDB(String dbName) {
    throw new UnsupportedOperationException();
  }

  protected DirectComparator getDirectComparator() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void put(byte[] key, AssetIssueCapsule item) {
    throwIfError();
  }

  @Override
  public void delete(byte[] key) {
    throwIfError();
  }

  @Override
  public AssetIssueCapsule of(byte[] value) {
    throwIfError();
    return null;
  }

  @Override
  public boolean isNotEmpty() {
    throwIfError();
    return false;
  }

  @Override
  public Iterator<Map.Entry<byte[], AssetIssueCapsule>> iterator() {
    throwIfError();
    return null;
  }

  public long size() {
    throwIfError();
    return 0;
  }

  public void setCursor(Chainbase.Cursor cursor) {
    throwIfError();
  }

  public Map<WrappedByteArray, AssetIssueCapsule> prefixQuery(byte[] key) {
    throwIfError();
    return null;
  }

  //****  Unsupported Operation For StateDB

}

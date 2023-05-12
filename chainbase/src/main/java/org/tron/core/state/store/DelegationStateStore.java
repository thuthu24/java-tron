package org.tron.core.state.store;

import org.rocksdb.DirectComparator;
import org.tron.core.capsule.BytesCapsule;
import org.tron.core.db2.common.WrappedByteArray;
import org.tron.core.db2.core.Chainbase;
import org.tron.core.state.WorldStateQueryInstance;
import org.tron.core.store.DelegationStore;

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.Map;

public class DelegationStateStore extends DelegationStore implements StateStore {

  private WorldStateQueryInstance worldStateQueryInstance;

  public DelegationStateStore(WorldStateQueryInstance worldStateQueryInstance) {
    this.worldStateQueryInstance = worldStateQueryInstance;
  }

  //****  Override Operation For StateDB

  @Override
  public String getDbName() {
    return worldStateQueryInstance.getRootHash().toHexString();
  }

  @Override
  public BytesCapsule get(byte[] key) {
    return getFromRoot(key);
  }

  @Override
  public BytesCapsule getFromRoot(byte[] key) {
    return getUnchecked(key);

  }

  @Override
  public BytesCapsule getUnchecked(byte[] key) {
    return worldStateQueryInstance.getDelegation(key);
  }

  @Override
  public long getBeginCycle(byte[] address) {
    return super.getBeginCycle(buildBeginCycleKey(address));
  }

  private byte[] buildBeginCycleKey(byte[] address) {
    return ByteBuffer.allocate(Long.BYTES + WorldStateQueryInstance.ADDRESS_SIZE + Byte.BYTES)
        .putLong(0)
        .put(address)
        .put((byte) 0x0)
        .array();
  }

  @Override
  protected byte[] buildVoteKey(long cycle, byte[] address) {
    return ByteBuffer.allocate(Long.BYTES + WorldStateQueryInstance.ADDRESS_SIZE + Byte.BYTES)
        .putLong(cycle)
        .put(address)
        .put((byte) 0x6)
        .array();
  }

  protected byte[] buildRewardKey(long cycle, byte[] address) {
    return ByteBuffer.allocate(Long.BYTES + WorldStateQueryInstance.ADDRESS_SIZE + Byte.BYTES)
        .putLong(cycle)
        .put(address)
        .put((byte) 0x4)
        .array();
  }

  protected byte[] buildAccountVoteKey(long cycle, byte[] address) {
    return ByteBuffer.allocate(Long.BYTES + WorldStateQueryInstance.ADDRESS_SIZE + Byte.BYTES)
        .putLong(cycle)
        .put(address)
        .put((byte) 0x2)
        .array();
  }

  protected byte[] buildEndCycleKey(byte[] address) {
    return ByteBuffer.allocate(Long.BYTES + WorldStateQueryInstance.ADDRESS_SIZE + Byte.BYTES)
        .putLong(0)
        .put(address)
        .put((byte) 0x1)
        .array();
  }

  protected byte[] buildBrokerageKey(long cycle, byte[] address) {
    return ByteBuffer.allocate(Long.BYTES + WorldStateQueryInstance.ADDRESS_SIZE + Byte.BYTES)
        .putLong(cycle)
        .put(address)
        .put((byte) 0x3)
        .array();
  }

  protected byte[] buildViKey(long cycle, byte[] address) {
    return ByteBuffer.allocate(Long.BYTES + WorldStateQueryInstance.ADDRESS_SIZE + Byte.BYTES)
        .putLong(cycle)
        .put(address)
        .put((byte) 0x5)
        .array();
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
  public void put(byte[] key, BytesCapsule item) {
    throwIfError();
  }

  @Override
  public void delete(byte[] key) {
    throwIfError();
  }

  @Override
  public BytesCapsule of(byte[] value) {
    throwIfError();
    return null;
  }

  @Override
  public boolean isNotEmpty() {
    throwIfError();
    return false;
  }

  @Override
  public Iterator<Map.Entry<byte[], BytesCapsule>> iterator() {
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

  public Map<WrappedByteArray, BytesCapsule> prefixQuery(byte[] key) {
    throwIfError();
    return null;
  }

  //****  Unsupported Operation For StateDB
}

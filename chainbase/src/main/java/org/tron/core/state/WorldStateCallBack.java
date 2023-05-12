package org.tron.core.state;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.primitives.Longs;
import io.prometheus.client.Histogram;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.ethereum.trie.MerkleTrieException;
import org.springframework.stereotype.Component;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.prometheus.MetricKeys;
import org.tron.common.prometheus.Metrics;
import org.tron.common.utils.ByteArray;
import org.tron.core.ChainBaseManager;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.db2.common.Value;
import org.tron.core.state.trie.TrieImpl2;
import org.tron.protos.Protocol;

@Slf4j(topic = "State")
@Component
public class WorldStateCallBack {

  @Setter
  protected volatile boolean execute;
  protected volatile boolean allowGenerateRoot;
  protected Map<Bytes, Bytes> trieEntryList = new HashMap<>();
  @Setter
  protected ChainBaseManager chainBaseManager;

  private BlockCapsule blockCapsule;

  private ThreadLocal<Protocol.Transaction.Contract> contract = new ThreadLocal<>();

  @Getter
  @VisibleForTesting
  private volatile TrieImpl2 trie;

  public WorldStateCallBack() {
    this.execute = true;
    this.allowGenerateRoot = CommonParameter.getInstance().getStorage().isAllowStateRoot();
  }

  public void callBack(StateType type, byte[] key, byte[] value, Value.Operator op) {
    if (!exe() || type == StateType.UNDEFINED) {
      return;
    }
    if (op == Value.Operator.DELETE || ArrayUtils.isEmpty(value)) {
      if (type == StateType.Account && chainBaseManager.getDynamicPropertiesStore()
              .getAllowAccountAssetOptimizationFromRoot() == 1) {
        // @see org.tron.core.db2.core.SnapshotRoot#remove(byte[] key)
        // @see org.tron.core.db2.core.SnapshotRoot#put(byte[] key, byte[] value)
        AccountCapsule accountCapsule = new AccountCapsule(value);
        accountCapsule.getAssetMapV2().keySet().forEach(tokenId -> addFix32(
                StateType.AccountAsset, com.google.common.primitives.Bytes.concat(key,
                        Longs.toByteArray(Long.parseLong(tokenId))),
                WorldStateQueryInstance.DELETE));
      }
      add(type, key, WorldStateQueryInstance.DELETE);
      return;
    }
    if (type == StateType.Account && chainBaseManager.getDynamicPropertiesStore()
            .getAllowAccountAssetOptimizationFromRoot() == 1) {
      // @see org.tron.core.db2.core.SnapshotRoot#put(byte[] key, byte[] value)
      AccountCapsule accountCapsule = new AccountCapsule(value);
      if (accountCapsule.getAssetOptimized()) {
        accountCapsule.getInstance().getAssetV2Map().forEach((tokenId, amount) -> addFix32(
                StateType.AccountAsset, com.google.common.primitives.Bytes.concat(key,
                        Longs.toByteArray(Long.parseLong(tokenId))),
                Longs.toByteArray(amount)));
      } else {
        accountCapsule.getAssetMapV2().forEach((tokenId, amount) -> addFix32(
                StateType.AccountAsset, com.google.common.primitives.Bytes.concat(key,
                        Longs.toByteArray(Long.parseLong(tokenId))),
                Longs.toByteArray(amount)));
        accountCapsule.setAssetOptimized(true);
      }
      value = accountCapsule.getInstance().toBuilder()
              .clearAsset()
              .clearAssetV2()
              .build().toByteArray();

    }
    add(type, key, value);
  }

  private void add(StateType type, byte[] key, byte[] value) {
    if (type == StateType.Delegation) {
      addFix32(type, parseDelegationKey(key), value);
    } else {
      trieEntryList.put(Bytes.of(StateType.encodeKey(type, key)), Bytes.of(value));
    }
  }

  private void addFix32(StateType type, byte[] key, byte[] value) {
    trieEntryList.put(fix32(StateType.encodeKey(type, key)), Bytes.of(value));
  }

  public static Bytes32 fix32(byte[] key) {
    return Bytes32.rightPad(Bytes.wrap(key));
  }

  public static Bytes32 fix32(Bytes key) {
    return Bytes32.rightPad(key);
  }

  private byte[] parseDelegationKey(byte[] key) {
    long num = 0;
    byte type = 0x0;
    byte[] address;
    // beginCycle
    if (key.length == WorldStateQueryInstance.ADDRESS_SIZE) {
      return ByteBuffer.allocate(Long.BYTES + WorldStateQueryInstance.ADDRESS_SIZE + Byte.BYTES)
          .putLong(num)
          .put(key)
          .put(type)
          .array();
    }
    String sk  = new String(key);
    if (sk.charAt(0) == '-') {
      num = -1;
    }
    String[] keys = sk.split("-");

    if (keys[0].equals("end")) {  // endCycle
      type = 0x1;
      address = ByteArray.fromHexString(keys[1]);
    } else { // other
      if (num != -1) {
        num = Long.parseLong(keys[0]);
        address = ByteArray.fromHexString(keys[1]);
      } else { // init
        address = ByteArray.fromHexString(keys[2]);
      }
      String s = keys[keys.length - 1];
      if (s.charAt(0) == 'a') {
        type = 0x2;
      } else if (s.charAt(0) == 'b') {
        type = 0x3;
      } else if (s.charAt(0) == 'r') {
        type = 0x4;
      } else if (s.charAt(0) == 'v' && s.charAt(1) == 'i') {
        type = 0x5;
      } else if (s.charAt(0) == 'v' && s.charAt(1) == 'o') {
        type = 0x6;
      } else {
        throw new IllegalArgumentException("unknown type : " + s);
      }
    }

    return ByteBuffer.allocate(Long.BYTES + WorldStateQueryInstance.ADDRESS_SIZE + Byte.BYTES)
        .putLong(num)
        .put(address)
        .put(type)
        .array();
  }


  protected boolean exe() {
    if (!allowGenerateRoot || !execute) {
      //Agreement same block high to generate archive root
      execute = false;
      return false;
    }
    return true;
  }

  @VisibleForTesting
  public void clear() {
    if (!exe()) {
      return;
    }
    Histogram.Timer timer = trieEntryList.isEmpty() ? null : Metrics.histogramStartTimer(
        MetricKeys.Histogram.TRON_STATE_PUT_PER_TRANS_LATENCY);
    trieEntryList.forEach((key, value) -> {
      Histogram.Timer t =  Metrics.histogramStartTimer(
          MetricKeys.Histogram.TRON_STATE_PUT_LATENCY, StateType.decodeType(key).getName());
      trie.put(key, value);
      Metrics.histogramObserve(t);
    });
    trieEntryList.clear();
    Metrics.histogramObserve(timer);
  }

  public void preExeTrans(Protocol.Transaction.Contract contract) {
    this.contract.set(contract);
    clear();
  }

  public void exeTransFinish() {
    trieEntryList.keySet().stream().map(StateType::decodeType).collect(Collectors.groupingBy(
        StateType::getName, Collectors.counting()))
        .forEach((k, v) -> Metrics.counterInc(MetricKeys.Counter.STATE_KEY_PER_TRAN_SIZE,
            v, this.contract.get().getType().name(), k));
    clear();
    this.contract.remove();
  }

  public void preExecute(BlockCapsule blockCapsule) {
    this.blockCapsule = blockCapsule;
    this.execute = true;
    if (!exe()) {
      return;
    }
    try {
      BlockCapsule parentBlockCapsule =
          chainBaseManager.getBlockById(blockCapsule.getParentBlockId());
      Bytes32 rootHash = parentBlockCapsule.getArchiveRoot();
      trie = new TrieImpl2(chainBaseManager.getMerkleStorage(), rootHash);
    } catch (Exception e) {
      throw new MerkleTrieException(e.getMessage());
    }
  }

  public void executePushFinish() {
    if (!exe()) {
      return;
    }
    clear();
    trie.commit();
    trie.flush();
    Bytes32 newRoot = trie.getRootHashByte32();
    blockCapsule.setArchiveRoot(newRoot.toArray());
    execute = false;
  }

  public void initGenesis(BlockCapsule blockCapsule) {
    if (!exe()) {
      return;
    }
    trie = new TrieImpl2(chainBaseManager.getMerkleStorage());
    clear();
    trie.commit();
    trie.flush();
    Bytes32 newRoot = trie.getRootHashByte32();
    blockCapsule.setArchiveRoot(newRoot.toArray());
    execute = false;
  }

  public void exceptionFinish() {
    execute = false;
  }

}

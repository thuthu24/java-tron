package org.tron.core.state;

import com.google.common.base.Preconditions;
import com.google.common.primitives.Bytes;
import java.nio.ByteBuffer;
import java.util.Arrays;
import lombok.Getter;
import org.tron.common.utils.ByteArray;

public enum StateType {

  UNDEFINED(new byte[]{0x00}, "undefined", false),

  Account(new byte[]{0x01}, "account", true),
  // address + assetid
  AccountAsset(new byte[]{0x02}, "account-asset", true),
  AccountIndex(new byte[]{0x03}, "account-index", false),
  AccountIdIndex(new byte[]{0x04}, "accountid-index", false),
  AssetIssue(new byte[]{0x05}, "asset-issue-v2", false),
  Code(new byte[]{0x07}, "code", true),
  Contract(new byte[]{0x08}, "contract", true),
  // address for begin
  // 'end' + address for end
  // cycle + address + type
  Delegation(new byte[]{0x09}, "delegation", true),
  // address * 2 for v1
  // address * 2 + lock for v2
  DelegatedResource(new byte[]{0x0a}, "DelegatedResource", true),
  // address  for old v1
  // address * 2 + source
  DelegatedResourceAccountIndex(new byte[]{0x0b}, "DelegatedResourceAccountIndex", true),
  Exchange(new byte[]{0x0c}, "exchange", false),
  ExchangeV2(new byte[]{0x0d}, "exchange-v2", false),
  IncrementalMerkleTree(new byte[]{0x0e}, "IncrementalMerkleTree", false),
  MarketAccount(new byte[]{0x0f}, "market_account", false),
  MarketOrder(new byte[]{0x10}, "market_order", false),
  MarketPairPriceToOrder(new byte[]{0x11}, "market_pair_price_to_order", false),
  MarketPairToPrice(new byte[]{0x12}, "market_pair_to_price", false),
  Nullifier(new byte[]{0x13}, "nullifier", false),
  Properties(new byte[]{0x14}, "properties", false),
  Proposal(new byte[]{0x15}, "proposal", false),
  StorageRow(new byte[]{0x16}, "storage-row", false),
  Votes(new byte[]{0x17}, "votes", true),
  Witness(new byte[]{0x18}, "witness", true),
  WitnessSchedule(new byte[]{0x19}, "witness_schedule", false),
  ContractState(new byte[]{0x1a}, "contract-state", true);

  // DO NOT USE THIS TYPE (byte) 0x41 or (byte) 0xa0 for any other purpose, see Constant L17,L19


  private final byte[] value;
  @Getter
  private final String name;

  @Getter
  private final boolean reverse;

  StateType(byte[] value, String name, boolean reverse) {
    this.value = value;
    this.name = name;
    this.reverse = reverse;
  }

  public byte[] value() {
    return this.value;
  }

  public static StateType get(String name) {
    return Arrays.stream(StateType.values()).filter(type -> type.name.equals(name))
        .findFirst().orElse(UNDEFINED);
  }

  public static StateType get(byte value) {
    return Arrays.stream(StateType.values()).filter(type -> type.value[0] == value)
        .findFirst().orElse(UNDEFINED);
  }

  public static byte[] encodeKey(StateType type, byte[] key) {
    if (type == StateType.AccountAsset) {
      return encodeAccountAssetKey(type, key);
    }
    if (type == StateType.Delegation) {
      return encodeDelegationKey(type, key);
    }
    if (type == StateType.DelegatedResource) {
      return encodeDelegatedResourceKey(type, key);
    }
    if (type == StateType.DelegatedResourceAccountIndex) {
      return encodeDelegatedResourceAccountIndexKey(type, key);
    }
    return type.isReverse() ? Bytes.concat(key, type.value) : Bytes.concat(type.value, key);
  }

  public static byte[] encodeAccountAssetKey(StateType type, byte[] key) {
    Preconditions.checkArgument(type == StateType.AccountAsset);
    Preconditions.checkArgument(key.length == WorldStateQueryInstance.ADDRESS_SIZE
        + Long.BYTES);
    byte[] address = Arrays.copyOfRange(key, 0, WorldStateQueryInstance.ADDRESS_SIZE);
    byte[] assetId = Arrays.copyOfRange(key, WorldStateQueryInstance.ADDRESS_SIZE,
        WorldStateQueryInstance.ADDRESS_SIZE + Long.BYTES);
    return ByteBuffer.allocate(WorldStateQueryInstance.ADDRESS_SIZE + Byte.BYTES + Long.BYTES)
        .put(address)
        .put(type.value)
        .put(assetId)
        .array();
  }

  public static byte[] encodeDelegationKey(StateType type, byte[] key) {
    Preconditions.checkArgument(type == StateType.Delegation);
    long num = 0;
    byte types = 0x0;
    byte[] address = key;
    if (key.length != WorldStateQueryInstance.ADDRESS_SIZE) {
      String sk = new String(key);
      if (sk.charAt(0) == '-') {
        num = -1;
      }
      String[] keys = sk.split("-");

      if (keys[0].equals("end")) {  // endCycle
        types = 0x1;
        address = ByteArray.fromHexString(keys[1]);
      } else { // other
        if (num != -1) {
          num = Long.parseLong(keys[0]);
          address = ByteArray.fromHexString(keys[1]);
        } else { // init
          address = ByteArray.fromHexString(keys[2]);
        }
        String s = keys[keys.length - 1];
        if (s.charAt(0) == 'b') {
          types = 0x3; // brokerage
        } else if (s.charAt(0) == 'r') {
          types = 0x4; // reward
        } else if (s.charAt(0) == 'v' && s.charAt(1) == 'i') {
          types = 0x5; // vi
        } else if (s.charAt(0) == 'v' && s.charAt(1) == 'o') {
          types = 0x6; // vote
          if (keys[keys.length - 2].charAt(0) == 'a') {
            types = 0x2; // account-vote
          }
        } else {
          throw new IllegalArgumentException("unknown type : " + s);
        }
      }
    }
    return ByteBuffer.allocate(WorldStateQueryInstance.ADDRESS_SIZE + Byte.BYTES * 2 + Long.BYTES)
        .put(address)
        .put(type.value)
        .put(types)
        .putLong(num)
        .array();
  }

  public static byte[] encodeDelegatedResourceKey(StateType type, byte[] key) {
    Preconditions.checkArgument(type == StateType.DelegatedResource);
    Preconditions.checkArgument(key.length == WorldStateQueryInstance.ADDRESS_SIZE
        * 2 || key.length == WorldStateQueryInstance.ADDRESS_SIZE * 2 + Byte.BYTES);
    byte[] from;
    byte[] to;
    if (key.length == WorldStateQueryInstance.ADDRESS_SIZE * 2 + Byte.BYTES) {
      byte lock = key[0];
      from = Arrays.copyOfRange(key, Byte.BYTES, WorldStateQueryInstance.ADDRESS_SIZE
          + Byte.BYTES);
      to = Arrays.copyOfRange(key, WorldStateQueryInstance.ADDRESS_SIZE + Byte.BYTES,
          WorldStateQueryInstance.ADDRESS_SIZE * 2 + Byte.BYTES);
      return ByteBuffer.allocate(WorldStateQueryInstance.ADDRESS_SIZE * 2 + Byte.BYTES * 2)
          .put(from)
          .put(type.value)
          .put(to)
          .put(lock)
          .array();
    }
    from = Arrays.copyOfRange(key, 0, WorldStateQueryInstance.ADDRESS_SIZE);
    to = Arrays.copyOfRange(key, WorldStateQueryInstance.ADDRESS_SIZE,
        WorldStateQueryInstance.ADDRESS_SIZE * 2);
    return ByteBuffer.allocate(WorldStateQueryInstance.ADDRESS_SIZE * 2 + Byte.BYTES)
        .put(from)
        .put(type.value)
        .put(to)
        .array();
  }

  public static byte[] encodeDelegatedResourceAccountIndexKey(StateType type, byte[] key) {
    Preconditions.checkArgument(type == StateType.DelegatedResourceAccountIndex);
    Preconditions.checkArgument(key.length == WorldStateQueryInstance.ADDRESS_SIZE
        || key.length == WorldStateQueryInstance.ADDRESS_SIZE * 2 + Byte.BYTES);
    int length = key.length;
    if (length == WorldStateQueryInstance.ADDRESS_SIZE) { // old v1
      return ByteBuffer.allocate(WorldStateQueryInstance.ADDRESS_SIZE + Byte.BYTES)
          .put(key)
          .put(type.value)
          .array();
    }
    byte source = key[0];
    byte[] from = Arrays.copyOfRange(key, Byte.BYTES, WorldStateQueryInstance.ADDRESS_SIZE
        + Byte.BYTES);
    byte[] to = Arrays.copyOfRange(key, WorldStateQueryInstance.ADDRESS_SIZE + Byte.BYTES,
        WorldStateQueryInstance.ADDRESS_SIZE * 2 + Byte.BYTES);
    return ByteBuffer.allocate(WorldStateQueryInstance.ADDRESS_SIZE * 2 + Byte.BYTES * 2)
        .put(from)
        .put(type.value)
        .put(to)
        .put(source)
        .array();
  }
}

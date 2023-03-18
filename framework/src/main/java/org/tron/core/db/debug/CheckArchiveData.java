package org.tron.core.db.debug;

import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.common.utils.ByteArray;
import org.tron.core.ChainBaseManager;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.StorageRowCapsule;
import org.tron.core.config.args.Args;
import org.tron.core.exception.HeaderNotFound;
import org.tron.core.exception.JsonRpcInvalidParamsException;
import org.tron.core.services.jsonrpc.TronJsonRpc;
import org.tron.core.state.WorldStateQueryInstance;

import javax.annotation.PostConstruct;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Slf4j(topic = "checker")
public class CheckArchiveData {

  @Autowired
  ChainBaseManager chainBaseManager;
  @Autowired
  TronJsonRpc tronJsonRpc;

  private String hexHeader;
  private static final String LATEST = "latest";

  private WorldStateQueryInstance query;

  @PostConstruct
  private void init() throws HeaderNotFound {
    if (Args.getInstance().isP2pDisable() && Args.getInstance().getStorage().isAllowStateRoot()) {
      BlockCapsule head = chainBaseManager.getHead();
      Bytes32 root = head.getArchiveRoot();
      long header = head.getNum();
      hexHeader = Bytes.ofUnsignedLong(header).toShortHexString();
      query = ChainBaseManager.fetch(root);
      logger.info("check data at {}, root  {}", header, root);
      checkAccount();
      checkStorageRow();
      logger.info("check data done");
      System.exit(0);
    }
  }

  private void checkAccount() {
    logger.info("check account start");
    AtomicLong cnt = new AtomicLong(0);
    chainBaseManager.getAccountStore().iterator().forEachRemaining(e ->
            CompletableFuture.runAsync(() -> {
              byte[] address = e.getKey();
              String add = ByteArray.toHexString(address);
              try {
                String balance = tronJsonRpc.getTrxBalance(add, LATEST);
                String balanceState = tronJsonRpc.getTrxBalance(add, hexHeader);
                if (!Objects.equals(balance, balanceState)) {
                  logger.info("b {},{},{}", add, balance, balanceState);
                }
                List<TronJsonRpc.Token10Result> assets = tronJsonRpc.getToken10(add, LATEST);
                List<TronJsonRpc.Token10Result> assetsState = tronJsonRpc.getToken10(add, hexHeader);
                if (!assets.equals(assetsState)) {
                  logger.info("ss {},{},{}", add, assets, assetsState);
                } else {
                  for (TronJsonRpc.Token10Result asset : assets) {
                    TronJsonRpc.Token10Result r = tronJsonRpc
                            .getToken10ById(add, asset.getKey(), hexHeader);
                    if (!Objects.equals(asset, r)) {
                      logger.info("s {},{},{},{}", add, asset.getKey(), asset, r);
                    }
                  }
                }
                cnt.getAndIncrement();
              } catch (JsonRpcInvalidParamsException ex) {
                logger.error("{}", e);
              }
            }).join());
    logger.info("check account end ,total key: {}", cnt.get());
  }


  private void checkStorageRow() {
    logger.info("check storage-row start");
    AtomicLong cnt = new AtomicLong(0);
    chainBaseManager.getStorageRowStore().iterator().forEachRemaining(e ->
            CompletableFuture.runAsync(() -> {
              byte[] o = e.getValue().getData();
              StorageRowCapsule s = query.getStorageRow(e.getKey());
              byte[] ss = s == null ? null : s.getData();
              if (!Arrays.equals(o, ss)) {
                logger.info("{},{},{}", ByteArray.toHexString(e.getKey()),
                        ByteArray.toHexString(o), ByteArray.toHexString(ss));
              }
              cnt.getAndIncrement();
            }));
    logger.info("check storage-row end ,total key: {}", cnt.get());
  }
}

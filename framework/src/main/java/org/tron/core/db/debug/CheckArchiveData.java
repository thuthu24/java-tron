package org.tron.core.db.debug;

import lombok.extern.slf4j.Slf4j;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.PostConstruct;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.common.utils.ByteArray;
import org.tron.core.ChainBaseManager;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.config.args.Args;
import org.tron.core.exception.HeaderNotFound;
import org.tron.core.exception.JsonRpcInvalidParamsException;
import org.tron.core.services.jsonrpc.TronJsonRpc;

@Component
@Slf4j(topic = "checker")
public class CheckArchiveData {

  @Autowired
  ChainBaseManager chainBaseManager;
  @Autowired
  TronJsonRpc tronJsonRpc;

  private String hexHeader;
  @PostConstruct
  private void init() throws HeaderNotFound, InterruptedException {
    if (Args.getInstance().isP2pDisable() && Args.getInstance().getStorage().isAllowStateRoot()) {
      BlockCapsule head = chainBaseManager.getHead();
      Bytes32 root = head.getArchiveRoot();
      long header = head.getNum();
      hexHeader = Bytes.ofUnsignedLong(header).toShortHexString();
      logger.info("check data at {}, root  {}", header, root);
      checkAccount();
      logger.info("check data done");
      System.exit(0);
    }
  }

  private void checkAccount() throws InterruptedException {
    logger.info("check account start");
    AtomicLong cnt = new AtomicLong(0);
    ScheduledExecutorService es = Executors.newSingleThreadScheduledExecutor();
    es.scheduleAtFixedRate(() -> logger.info("check account {}", cnt.get()),
        10, 10, java.util.concurrent.TimeUnit.SECONDS);
    int nThreads = Runtime.getRuntime().availableProcessors() * 8;
    ThreadPoolExecutor executor = new ThreadPoolExecutor(nThreads, nThreads,
        0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    chainBaseManager.getAccountStore().iterator().forEachRemaining(e ->
            CompletableFuture.runAsync(() -> {
              byte[] address = e.getKey();
              AccountCapsule account = e.getValue();
              String add = ByteArray.toHexString(address);
              try {
                String balanceState = tronJsonRpc.getTrxBalance(add, hexHeader);
                String balance = ByteArray.toJsonHex(account.getBalance());
                if (!Objects.equals(balance, balanceState)) {
                  logger.info("b {},{},{}", add, balance, balanceState);
                }

                List<TronJsonRpc.Token10Result> assets =  new ArrayList<>();
                account.getAssetMapV2().entrySet().stream().filter(
                    asset -> asset.getValue() > 0).map(asset ->
                      new TronJsonRpc.Token10Result(ByteArray.toJsonHex(
                          Long.parseUnsignedLong(asset.getKey())),
                          ByteArray.toJsonHex(asset.getValue()))).forEach(assets::add);
                assets.sort(Comparator.comparing(TronJsonRpc.Token10Result::getKey));
                List<TronJsonRpc.Token10Result> assetsState =
                    tronJsonRpc.getToken10(add, hexHeader);
                if (!assets.equals(assetsState)) {
                  logger.info("ss {},{},{}", add, assets, assetsState);
                } else if (account.getAssetOptimized()) {
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
            }, executor));
    while (executor.getActiveCount() > 0) {
      Thread.sleep(1000);
    }
    logger.info("check account end ,total key: {}", cnt.get());
    es.shutdown();
    executor.shutdown();
    executor.awaitTermination(10, TimeUnit.MINUTES);
    es.awaitTermination(10, TimeUnit.MINUTES);

  }
}

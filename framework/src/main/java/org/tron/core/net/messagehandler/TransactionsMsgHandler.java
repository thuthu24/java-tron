package org.tron.core.net.messagehandler;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.common.es.ExecutorServiceManager;
import org.tron.core.config.args.Args;
import org.tron.core.exception.P2pException;
import org.tron.core.exception.P2pException.TypeEnum;
import org.tron.core.net.TronNetDelegate;
import org.tron.core.net.message.TronMessage;
import org.tron.core.net.message.adv.TransactionMessage;
import org.tron.core.net.message.adv.TransactionsMessage;
import org.tron.core.net.peer.Item;
import org.tron.core.net.peer.PeerConnection;
import org.tron.core.net.service.adv.AdvService;
import org.tron.protos.Protocol.Inventory.InventoryType;
import org.tron.protos.Protocol.ReasonCode;
import org.tron.protos.Protocol.Transaction;
import org.tron.protos.Protocol.Transaction.Contract.ContractType;

@Slf4j(topic = "net")
@Component
public class TransactionsMsgHandler implements TronMsgHandler {

  private static int MAX_TRX_SIZE = 50_000;
  private static int MAX_SMART_CONTRACT_SUBMIT_SIZE = 100;
  @Autowired
  private TronNetDelegate tronNetDelegate;
  @Autowired
  private AdvService advService;

  private BlockingQueue<TrxEvent> smartContractQueue = new LinkedBlockingQueue(MAX_TRX_SIZE);

  private BlockingQueue<Runnable> queue = new LinkedBlockingQueue();

  private int threadNum = Args.getInstance().getValidateSignThreadNum();
  private ExecutorService trxHandlePool = new ThreadPoolExecutor(threadNum, threadNum, 0L,
      TimeUnit.MILLISECONDS, queue,
      new ThreadFactoryBuilder().setNameFormat("trx-Handle-%d").build());

  private final String smartEsName = "transactions-msg-handler-smart-contract";

  private final ScheduledExecutorService smartContractExecutor = ExecutorServiceManager
      .newSingleThreadScheduledExecutor(smartEsName);

  public void init() {
    handleSmartContract();
  }

  public void close() {
    logger.info("Transaction msg handler shutdown...");
    ExecutorServiceManager.shutdownAndAwaitTermination(trxHandlePool, "trx-handle");
    ExecutorServiceManager.shutdownAndAwaitTermination(smartContractExecutor, smartEsName);
    logger.info("Transaction msg handler shutdown complete");
  }

  public boolean isBusy() {
    return queue.size() + smartContractQueue.size() > MAX_TRX_SIZE;
  }

  @Override
  public void processMessage(PeerConnection peer, TronMessage msg) throws P2pException {
    TransactionsMessage transactionsMessage = (TransactionsMessage) msg;
    check(peer, transactionsMessage);
    int smartContractQueueSize = 0;
    int trxHandlePoolQueueSize = 0;
    int dropSmartContractCount = 0;
    for (Transaction trx : transactionsMessage.getTransactions().getTransactionsList()) {
      int type = trx.getRawData().getContract(0).getType().getNumber();
      if (type == ContractType.TriggerSmartContract_VALUE
          || type == ContractType.CreateSmartContract_VALUE) {
        if (!smartContractQueue.offer(new TrxEvent(peer, new TransactionMessage(trx)))) {
          smartContractQueueSize = smartContractQueue.size();
          trxHandlePoolQueueSize = queue.size();
          dropSmartContractCount++;
        }
      } else {
        try {
          trxHandlePool.submit(() -> handleTransaction(peer, new TransactionMessage(trx)));
        } catch (RejectedExecutionException e) {
          logger.warn("Trx from {} process failed, queueSize: {}",
              peer.getInetAddress(), queue.size());
          peer.disconnect(ReasonCode.USER_REASON);
          return;
        }
      }
    }

    if (dropSmartContractCount > 0) {
      logger.warn("Add smart contract failed, drop count: {}, queueSize {}:{}",
          dropSmartContractCount, smartContractQueueSize, trxHandlePoolQueueSize);
    }
  }

  private void check(PeerConnection peer, TransactionsMessage msg) throws P2pException {
    for (Transaction trx : msg.getTransactions().getTransactionsList()) {
      Item item = new Item(new TransactionMessage(trx).getMessageId(), InventoryType.TRX);
      if (!peer.getAdvInvRequest().containsKey(item)) {
        throw new P2pException(TypeEnum.BAD_MESSAGE,
            "trx: " + msg.getMessageId() + " without request.");
      }
      peer.getAdvInvRequest().remove(item);
    }
  }

  private void handleSmartContract() {
    smartContractExecutor.scheduleWithFixedDelay(() -> {
      try {
        while (queue.size() < MAX_SMART_CONTRACT_SUBMIT_SIZE && smartContractQueue.size() > 0) {
          TrxEvent event = smartContractQueue.take();
          trxHandlePool.submit(() -> handleTransaction(event.getPeer(), event.getMsg()));
        }
      } catch (InterruptedException e) {
        logger.warn("Handle smart server interrupted");
        Thread.currentThread().interrupt();
      } catch (Exception e) {
        logger.error("Handle smart contract exception", e);
      }
    }, 1000, 20, TimeUnit.MILLISECONDS);
  }

  private void handleTransaction(PeerConnection peer, TransactionMessage trx) {
    if (peer.isDisconnect()) {
      logger.warn("Drop trx {} from {}, peer is disconnect", trx.getMessageId(),
          peer.getInetAddress());
      return;
    }

    if (advService.getMessage(new Item(trx.getMessageId(), InventoryType.TRX)) != null) {
      return;
    }

    try {
      tronNetDelegate.pushTransaction(trx.getTransactionCapsule());
      advService.broadcast(trx);
    } catch (P2pException e) {
      logger.warn("Trx {} from peer {} process failed. type: {}, reason: {}",
          trx.getMessageId(), peer.getInetAddress(), e.getType(), e.getMessage());
      if (e.getType().equals(TypeEnum.BAD_TRX)) {
        peer.disconnect(ReasonCode.BAD_TX);
      }
    } catch (Exception e) {
      logger.error("Trx {} from peer {} process failed", trx.getMessageId(), peer.getInetAddress(),
          e);
    }
  }

  class TrxEvent {

    @Getter
    private PeerConnection peer;
    @Getter
    private TransactionMessage msg;
    @Getter
    private long time;

    public TrxEvent(PeerConnection peer, TransactionMessage msg) {
      this.peer = peer;
      this.msg = msg;
      this.time = System.currentTimeMillis();
    }
  }
}
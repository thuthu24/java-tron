package org.tron.core.net.message;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.utils.Sha256Hash;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.protos.Protocol;
import org.tron.protos.Protocol.Transaction;

public class TransactionsMessage extends TronMessage {

  private Protocol.Transactions transactions;

  public TransactionsMessage(List<Transaction> trxs) {
    Protocol.Transactions.Builder builder = Protocol.Transactions.newBuilder();
    trxs.forEach(trx -> builder.addTransactions(trx));
    this.transactions = builder.build();
    this.type = MessageTypes.TRXS.asByte();
    this.data = this.transactions.toByteArray();
  }

  public TransactionsMessage(byte[] data) throws Exception {
    super(data);
    this.type = MessageTypes.TRXS.asByte();
    this.transactions = Protocol.Transactions.parseFrom(getCodedInputStream(data));
    if (isFilter()) {
      compareBytes(data, transactions.toByteArray());
      TransactionCapsule.validContractProto(transactions.getTransactionsList());
    }
  }

  public Protocol.Transactions getTransactions() {
    return transactions;
  }

  @Override
  public String toString() {
    List<Sha256Hash> hashes = new ArrayList<>(getHashList());
    StringBuilder builder = new StringBuilder();
    builder.append(super.toString())
        .append(", size: ").append(hashes.size())
        .append(", trxs: ").append(hashes);
    return builder.toString();
  }

  @Override
  public Class<?> getAnswerMessage() {
    return null;
  }

  public List<Sha256Hash> getHashList() {
    return transactions.getTransactionsList().stream().map(
        t -> Sha256Hash.of(CommonParameter.getInstance().isECKeyCryptoEngine(),
            t.getRawData().toByteArray()))
        .collect(Collectors.toList());
  }

}

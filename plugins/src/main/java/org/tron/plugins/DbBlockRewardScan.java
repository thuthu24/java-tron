package org.tron.plugins;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.extern.slf4j.Slf4j;
import me.tongfei.progressbar.ProgressBar;
import org.rocksdb.RocksDBException;
import org.tron.plugins.utils.ByteArray;
import org.tron.plugins.utils.Sha256Hash;
import org.tron.plugins.utils.db.DBInterface;
import org.tron.plugins.utils.db.DBIterator;
import org.tron.plugins.utils.db.DbTool;
import org.tron.protos.Protocol;
import org.tron.protos.contract.BalanceContract;
import org.tron.protos.contract.WitnessContract;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Path;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;


@Slf4j(topic = "block-reward-scan")
@CommandLine.Command(name = "block-reward-scan",
    description = "scan reward data from block.",
    exitCodeListHeading = "Exit Codes:%n",
    exitCodeList = {
        "0:Successful",
        "n:query failed,please check toolkit.log"})
public class DbBlockRewardScan implements Callable<Integer> {

  @CommandLine.Spec
  CommandLine.Model.CommandSpec spec;
  @CommandLine.Parameters(index = "0",
      description = " db path for block")
  private Path db;

  private static final DateFormat DATE_FORMAT_SHORT = new SimpleDateFormat("yyyy-MM-dd");

  @CommandLine.Option(names = {"-s", "--start"},
      defaultValue = "1",
      description = "start block. Default: ${DEFAULT-VALUE}")
  private long start;

  @CommandLine.Option(names = {"-e", "--end"},
      defaultValue = "-1",
      description = "end block. Default: ${DEFAULT-VALUE}")
  private long end;

  @CommandLine.Option(names = {"-h", "--help"}, help = true, description = "display a help message")
  private boolean help;

  private static final  String DB = "block";

  private final AtomicLong cnt = new AtomicLong(0);
  private final AtomicLong scanTotal = new AtomicLong(0);


  @Override
  public Integer call() throws Exception {
    if (help) {
      spec.commandLine().usage(System.out);
      return 0;
    }
    if (!db.toFile().exists()) {
      logger.info(" {} does not exist.", db);
      spec.commandLine().getErr().println(spec.commandLine().getColorScheme()
          .errorText(String.format("%s does not exist.", db)));
      return 404;
    }
    return query();
  }


  private int query() throws RocksDBException, IOException {
    try (DBInterface database  = DbTool.getDB(this.db, DB);
         DBIterator iterator = database.iterator()) {
      long min = start;
      iterator.seek(ByteArray.fromLong(min));
      min = new Sha256Hash.BlockId(Sha256Hash.wrap(iterator.getKey())).getNum();
      if (end > 0) {
        iterator.seek(ByteArray.fromLong(end));
        if (!iterator.valid()) {
          iterator.seekToLast();
        }
      } else {
        iterator.seekToLast();
      }
      if (!iterator.valid()) {
        return 0;
      }
      long max = new Sha256Hash.BlockId(Sha256Hash.wrap(iterator.getKey())).getNum();
      if (max < min) {
        max = max + min;
        min = max - min;
        max = max - min;
      }
      long total = max - min + 1;
      spec.commandLine().getOut().format("scan ret start from  %d to %d ", min, max).println();
      logger.info("scan block start from {} to {}", min, max);
      try (ProgressBar pb = new ProgressBar("block-reward-scan", total)) {
        for (iterator.seek(ByteArray.fromLong(min)); iterator.hasNext() && total > 0;
             iterator.next(), total--) {
          print(iterator.getKey(), iterator.getValue());
          pb.step();
          pb.setExtraMessage("Reading...");
        }
      }
      spec.commandLine().getOut().format("total scan block size: %d", scanTotal.get()).println();
      logger.info("total scan block size: {}", scanTotal.get());
      spec.commandLine().getOut().format("reward size: %d", cnt.get()).println();
      logger.info("reward size: {}", cnt.get());
    }
    return 0;
  }

  private  void print(byte[] k, byte[] v) {
    try {
      Protocol.Block block = Protocol.Block.parseFrom(v);
      final String day = DATE_FORMAT_SHORT.format(new Date(block.getBlockHeader()
          .getRawData().getTimestamp()));
      List<Protocol.Transaction> list = block.getTransactionsList();
      list.forEach(transaction -> {
        try {
          String owner = getOwnerAddress(transaction);
          if (owner != null) {
            spec.commandLine().getOut().format("%s,%s ", day, owner).println();
            logger.info("{},{} ", day, owner);
            cnt.getAndIncrement();
          }
        } catch (InvalidProtocolBufferException e) {
          logger.error("{}", e.getMessage());
        }
      });
    } catch (InvalidProtocolBufferException e) {
      logger.error("{},{}", k, v);
    }
  }

  private String getOwnerAddress(Protocol.Transaction transaction)
      throws InvalidProtocolBufferException {
    Any any = transaction.getRawData().getContract(0).getParameter();

    Protocol.Transaction.Contract.ContractType type =
        transaction.getRawData().getContract(0).getType();
    if (type == Protocol.Transaction.Contract.ContractType.VoteWitnessContract) {
      return ByteArray.toHexString(any.unpack(WitnessContract.VoteWitnessContract.class)
          .getOwnerAddress().toByteArray());
    }
    if (type == Protocol.Transaction.Contract.ContractType.WithdrawBalanceContract) {
      return ByteArray.toHexString(any.unpack(BalanceContract.WithdrawBalanceContract.class)
          .getOwnerAddress().toByteArray());
    }
    if (type == Protocol.Transaction.Contract.ContractType.UnfreezeBalanceContract) {
      return ByteArray.toHexString(any.unpack(BalanceContract.UnfreezeBalanceContract.class)
          .getOwnerAddress().toByteArray());
    }
    return null;
  }
}

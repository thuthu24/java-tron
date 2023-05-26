package org.tron.plugins;

import com.google.protobuf.InvalidProtocolBufferException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.PriorityQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import me.tongfei.progressbar.ProgressBar;
import org.rocksdb.RocksDBException;
import org.tron.plugins.utils.ByteArray;
import org.tron.plugins.utils.Sha256Hash;
import org.tron.plugins.utils.db.DBInterface;
import org.tron.plugins.utils.db.DBIterator;
import org.tron.plugins.utils.db.DbTool;
import org.tron.protos.Protocol;
import picocli.CommandLine;

@Slf4j(topic = "block-stats")
@CommandLine.Command(name = "block-stats",
    description = "stats data from block.",
    exitCodeListHeading = "Exit Codes:%n",
    exitCodeList = {
        "0:Successful",
        "n:query failed,please check toolkit.log"})
public class DbBlockStats implements Callable<Integer> {

  @CommandLine.Spec
  CommandLine.Model.CommandSpec spec;
  @CommandLine.Parameters(index = "0",
      description = " db path for block")
  private Path db;

  @CommandLine.Option(names = {"-s", "--start" },
          defaultValue = "7588859",
          description = "start block. Default: ${DEFAULT-VALUE}")
  private  long start;

  @CommandLine.Option(names = {"-e", "--end"},
      defaultValue = "-1",
      description = "end block. Default: ${DEFAULT-VALUE}")
  private long end;

  @CommandLine.Option(names = {"-sd", "--start-day" },
      defaultValue = "7588859",
      description = "start block. Default: ${DEFAULT-VALUE}")
  private  String startDay;

  @CommandLine.Option(names = {"-ed", "--end-day"},
      defaultValue = "-1",
      description = "end block. Default: ${DEFAULT-VALUE}")
  private String endDay;

  @CommandLine.Option(names = {"-m", "--max"},
      description = "find max or min block. Default: ${DEFAULT-VALUE}")
  private boolean findMax = true;

  @CommandLine.Option(names = {"-c", "--count"}, help = true,
      description = "find max or min block cnt. Default: ${DEFAULT-VALUE}")
  private int cnt = 1;

  @CommandLine.Option(names = {"-h", "--help"}, help = true, description = "display a help message")
  private boolean help;

  private static final  String DB = "block";

  private final PriorityQueue<Protocol.Block> maxBlock = new PriorityQueue<>(cnt, (o1, o2) ->
      findMax ? Integer.compare(o1.getTransactionsCount(), o2.getTransactionsCount()) :
          Integer.compare(o2.getTransactionsCount(), o1.getTransactionsCount()));
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
      long min =  start;
      iterator.seek(ByteArray.fromLong(min));
      min = new Sha256Hash.BlockId(Sha256Hash.wrap(iterator.getKey())).getNum();
      if (end > 0) {
        iterator.seek(ByteArray.fromLong(end));
      } else {
        iterator.seekToLast();
      }
      long max = new Sha256Hash.BlockId(Sha256Hash.wrap(iterator.getKey())).getNum();
      long total = max - min + 1;
      spec.commandLine().getOut().format("stats block start from  %d to %d ", min, max).println();
      logger.info("stats block start from {} to {}", min, max);
      try (ProgressBar pb = new ProgressBar("block-stats", total)) {
        for (iterator.seek(ByteArray.fromLong(min)); iterator.hasNext() && total-- > 0;
             iterator.next()) {
          stats(iterator.getKey(), iterator.getValue());
          pb.step();
          pb.setExtraMessage("Reading...");
          scanTotal.getAndIncrement();
        }
      }
      spec.commandLine().getOut().format("total stat block size: %d", scanTotal.get()).println();
      logger.info("total stat block size: {}", scanTotal.get());


      maxBlock.stream().sorted((o1, o2) -> findMax
          ? Integer.compare(o2.getTransactionsCount(), o1.getTransactionsCount()) :
          Integer.compare(o1.getTransactionsCount(), o2.getTransactionsCount())).forEach(block -> {
            spec.commandLine().getOut().format("%d block  %d trxs %d Bytes ",
                block.getBlockHeader().getRawData().getNumber(),
                block.getTransactionsCount(), block.getSerializedSize()).println();
            logger.info("{} block  {} trxs {} Bytes",
                block.getBlockHeader().getRawData().getNumber(),
                block.getTransactionsCount(), block.getSerializedSize());
          });
    }
    return 0;
  }

  private  void stats(byte[] k, byte[] v) {
    try {
      maxBlock.add(Protocol.Block.parseFrom(v));
      if (maxBlock.size() > cnt) {
        maxBlock.poll();
      }
    } catch (InvalidProtocolBufferException e) {
      logger.error("{},{}", k, v);
    }
  }

}

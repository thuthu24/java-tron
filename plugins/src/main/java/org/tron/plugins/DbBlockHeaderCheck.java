package org.tron.plugins;

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
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;


@Slf4j(topic = "block-header-check")
@CommandLine.Command(name = "block-header-check",
    description = "scan header from block.",
    exitCodeListHeading = "Exit Codes:%n",
    exitCodeList = {
        "0:Successful",
        "n:query failed,please check toolkit.log"})
public class DbBlockHeaderCheck implements Callable<Integer> {

  @CommandLine.Spec
  CommandLine.Model.CommandSpec spec;
  @CommandLine.Parameters(index = "0",
      description = " db path for block")
  private Path db;

  @CommandLine.Option(names = {"-s", "--start" },
          defaultValue = "1",
          description = "start block. Default: ${DEFAULT-VALUE}")
  private  long start;

  @CommandLine.Option(names = {"-e", "--end" },
      description = "start block. Default: ${DEFAULT-VALUE}")
  private  long end = Long.MAX_VALUE;

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
      long min =  start;
      iterator.seek(ByteArray.fromLong(min));
      min = new Sha256Hash.BlockId(Sha256Hash.wrap(iterator.getKey())).getNum();
      iterator.seekToLast();
      long max = new Sha256Hash.BlockId(Sha256Hash.wrap(iterator.getKey())).getNum();
      max = Math.min(max, end);
      long total = max - min + 1;
      spec.commandLine().getOut().format("scan block header time start from  %d to %d ",
          min, max).println();
      logger.info("scan block start from {} to {}", min, max);
      try (ProgressBar pb = new ProgressBar("block-scan", total)) {
        iterator.seek(ByteArray.fromLong(min));
        for (int i = 0; iterator.hasNext() && i < total; iterator.next(), i++) {
          print(iterator.getKey(), iterator.getValue());
          pb.step();
          pb.setExtraMessage("Reading...");
          scanTotal.getAndIncrement();
        }
      }
      spec.commandLine().getOut().format("total scan block size: %d", scanTotal.get()).println();
      logger.info("total scan block size: {}", scanTotal.get());
      spec.commandLine().getOut().format("illegal header  size: %d", cnt.get()).println();
      logger.info("illegal header size: {}", cnt.get());
    }
    return 0;
  }

  private  void print(byte[] k, byte[] v) {
    try {
      Protocol.Block block = Protocol.Block.parseFrom(v);
      long num = block.getBlockHeader().getRawData().getNumber();
      long timestamp = block.getBlockHeader().getRawData().getTimestamp();
      if (timestamp % 3000 != 0) {
        spec.commandLine().getOut().format("%d, %d ", num, timestamp).println();
        logger.info("{}, {} ", num, timestamp);
        cnt.getAndIncrement();
      }
    } catch (InvalidProtocolBufferException e) {
      logger.error("{},{}", ByteArray.toHexString(k), ByteArray.toHexString(v));
    }
  }

}

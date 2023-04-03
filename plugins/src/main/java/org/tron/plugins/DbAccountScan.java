package org.tron.plugins;

import com.google.protobuf.InvalidProtocolBufferException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.RocksDBException;
import org.tron.plugins.utils.ByteArray;
import org.tron.plugins.utils.db.DBInterface;
import org.tron.plugins.utils.db.DBIterator;
import org.tron.plugins.utils.db.DbTool;
import org.tron.protos.Protocol;
import picocli.CommandLine;


@Slf4j(topic = "account-scan")
@CommandLine.Command(name = "account-scan",
    description = "scan data from account.",
    exitCodeListHeading = "Exit Codes:%n",
    exitCodeList = {
        "0:Successful",
        "n:query failed,please check toolkit.log"})
public class DbAccountScan implements Callable<Integer> {

  @CommandLine.Spec
  CommandLine.Model.CommandSpec spec;
  @CommandLine.Parameters(index = "0",
      description = " db path for account")
  private Path db;
  @CommandLine.Option(names = {"-h", "--help"}, help = true, description = "display a help message")
  private boolean help;

  private static final  String DB = "account";

  private final AtomicLong cnt = new AtomicLong(0);


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
      long c = 0;
      for (iterator.seekToFirst(); iterator.hasNext(); iterator.next(), c++) {
        print(iterator.getValue());
        if (c % 1000000 == 0) {
          spec.commandLine().getOut().format("current account size: %d", c).println();
        }
      }
      spec.commandLine().getOut().format("total account size: %d", c).println();
      logger.info("total account size: {}", c);
      spec.commandLine().getOut().format("multi-sig-account size: %d", cnt.get()).println();
      logger.info("multi-sig-account size: {}", cnt.get());
    }
    return 0;
  }

  private  void print(byte[] v) {
    String value = ByteArray.toHexString(v);
    try {
      Protocol.Account account = Protocol.Account.parseFrom(v);
      if (account.getOwnerPermission().getKeysCount() > 1
              || account.getActivePermissionList().stream().anyMatch(p -> p.getKeysCount() > 1)) {
        cnt.getAndIncrement();
        logger.info("{}", value);
      }
    } catch (InvalidProtocolBufferException e) {
      logger.error("e", e);
    }
  }
}

package org.tron.plugins;

import com.google.protobuf.InvalidProtocolBufferException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import lombok.extern.slf4j.Slf4j;
import me.tongfei.progressbar.ProgressBar;
import org.bouncycastle.util.encoders.Hex;
import org.tron.plugins.utils.db.DBInterface;
import org.tron.plugins.utils.db.DBIterator;
import org.tron.plugins.utils.db.DbTool;
import org.tron.protos.Protocol;
import picocli.CommandLine;

@Slf4j(topic = "reward-miss-scan")
@CommandLine.Command(name = "reward-miss-scan",
    description = "scan account before 2019-10-31 20:00:00 .",
    exitCodeListHeading = "Exit Codes:%n",
    exitCodeList = {
        "0:Successful",
        "n:query failed,please check toolkit.log"})
public class DbRewardMissScan implements Callable<Integer> {

  @CommandLine.Spec
  CommandLine.Model.CommandSpec spec;
  @CommandLine.Parameters(index = "0",
      description = " db path for delegation")
  private Path db;
  @CommandLine.Option(names = {"-q", "--query"})
  private boolean query;
  @CommandLine.Option(names = {"-h", "--help"}, help = true, description = "display a help message")
  private boolean help;

  private DBInterface delegationStore;

  private DBInterface accountStore;

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
    delegationStore = DbTool.getDB(this.db, "delegation");
    accountStore = DbTool.getDB(this.db, "account");
    return scanAccount();
  }

  private int scanAccount() {
    DBIterator iterator = accountStore.iterator();
    iterator.seekToFirst();
    ProgressBar.wrap(iterator, "scan account")
        .forEachRemaining(this::doScanAccount);
    return 0;
  }

  private void doScanAccount(Map.Entry<byte[], byte[]> e) {
    try {
      byte[] address = e.getKey();
      byte[] value = e.getValue();
      Protocol.Account account = Protocol.Account.parseFrom(value);
      if (account.getVotesCount() == 0) {
        return;
      }
      if (delegationStore.get(address) != null) {
        return;
      }
      logger.info("{}", Hex.toHexString(address));
    } catch (InvalidProtocolBufferException ex) {
      logger.error("{}", ex);
    }
  }
}

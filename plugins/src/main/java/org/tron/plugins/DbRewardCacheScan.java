package org.tron.plugins;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.tron.plugins.utils.ByteArray;
import org.tron.plugins.utils.db.DBInterface;
import org.tron.plugins.utils.db.DBIterator;
import org.tron.plugins.utils.db.DbTool;
import picocli.CommandLine;

@Slf4j(topic = "reward-cache-scan")
@CommandLine.Command(name = "reward-cache-scan",
    description = "scan reward  cache from reward-cache.",
    exitCodeListHeading = "Exit Codes:%n",
    exitCodeList = {
        "0:Successful",
        "n:query failed,please check toolkit.log"})
public class DbRewardCacheScan implements Callable<Integer> {

  @CommandLine.Spec
  CommandLine.Model.CommandSpec spec;
  @CommandLine.Parameters(index = "0",
      description = " db path for reward-cache")
  private Path db;
  @CommandLine.Option(names = {"-q", "--query"})
  private boolean query;
  @CommandLine.Option(names = {"-h", "--help"}, help = true, description = "display a help message")
  private boolean help;

  private DBInterface rewardCache;


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
    rewardCache = DbTool.getDB(this.db, "reward-cache");
    return queryRewardCache();
  }

  public int queryRewardCache() throws IOException {
    AtomicLong cnt = new AtomicLong();
    try (DBIterator iterator = rewardCache.iterator()) {
      iterator.seek(new byte[]{0x41});
      byte[] start = iterator.getKey();
      iterator.seekToLast();
      byte[] end = iterator.getKey();
      spec.commandLine().getOut().println(String.format("start: %s, end: %s",
          ByteArray.toHexString(start), ByteArray.toHexString(end)));
      iterator.seek(new byte[]{0x41});
      iterator.forEachRemaining(e -> {
        byte[] address = new byte[21];
        System.arraycopy(e.getKey(), 0, address, 0, 21);
        long reward = ByteArray.toLong(e.getValue());
        cnt.getAndIncrement();
        spec.commandLine().getOut().println(String.format("address: %s, reward: %d",
            ByteArray.toHexString(address), reward));
      });
    }
    spec.commandLine().getOut().println(String.format("total: %d", cnt.get()));
    return 0;
  }


}

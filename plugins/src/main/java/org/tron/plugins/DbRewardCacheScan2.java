package org.tron.plugins;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.primitives.Longs;
import lombok.extern.slf4j.Slf4j;
import org.tron.plugins.utils.ByteArray;
import org.tron.plugins.utils.db.DBInterface;
import org.tron.plugins.utils.db.DBIterator;
import org.tron.plugins.utils.db.DbTool;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j(topic = "reward-cache-scan-2")
@CommandLine.Command(name = "reward-cache-scan-2",
    description = "scan reward  cache from reward-cache.",
    exitCodeListHeading = "Exit Codes:%n",
    exitCodeList = {
        "0:Successful",
        "n:query failed,please check toolkit.log"})
public class DbRewardCacheScan2 implements Callable<Integer> {

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

  private final Cache<String, byte[]> rewardCache2 = CacheBuilder.newBuilder()
      .maximumWeight(1024 * 1024 * 1024)
      .weigher((String key, byte[] value) -> value.length + key.length())
      .build();

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
    Map<String, Integer> count = new TreeMap<>();
    try (DBIterator iterator = rewardCache.iterator()) {
      iterator.seekToFirst();
      iterator.forEachRemaining(e -> {
        cnt.getAndIncrement();
        String k = new String(e.getKey());
        String cycle = k.split("-")[0];
        count.put(cycle, count.getOrDefault(cycle, 0) + 1);
        if ("4707".equalsIgnoreCase(cycle)) {
          logger.info("key: {}, value: {}", k, ByteArray.toLong(e.getValue()));
        }
      });
    }
    int max = count.values().stream().mapToInt(Integer::intValue).max().getAsInt();
    int min = count.values().stream().mapToInt(Integer::intValue).min().getAsInt();
    spec.commandLine().getOut().println(String.format("max: %d, min: %d", max, min));
    count.forEach((k, v) -> spec.commandLine().getOut().println((String.format("%s: %d", k, v))));
    count.forEach((k, v) -> logger.info((String.format("%s: %d", k, v))));
    spec.commandLine().getOut().println(String.format("total: %d", cnt.get()));
    return 0;
  }


}

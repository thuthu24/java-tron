package org.tron.plugins;

import com.google.protobuf.InvalidProtocolBufferException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.stream.LongStream;
import lombok.extern.slf4j.Slf4j;
import me.tongfei.progressbar.ProgressBar;
import org.bouncycastle.util.encoders.Hex;
import org.tron.plugins.utils.ByteArray;
import org.tron.plugins.utils.db.DBInterface;
import org.tron.plugins.utils.db.DbTool;
import org.tron.protos.Protocol;
import picocli.CommandLine;

@Slf4j(topic = "reward-scan")
@CommandLine.Command(name = "reward-scan",
    description = "scan reward from delegation before 2023-01-20 14:00:00.",
    exitCodeListHeading = "Exit Codes:%n",
    exitCodeList = {
        "0:Successful",
        "n:query failed,please check toolkit.log"})
public class DbRewardScan implements Callable<Integer> {

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

  private static final long newRewardCalStartCycle = 4708L;
  private static final long REMARK = -1L;

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
    return calOldReward();
  }

  public Integer calOldReward() throws IOException {
    // try-with-resource block
    try (ProgressBar pb = new ProgressBar("cal old reward", -1)) {
      delegationStore.prefixQuery(new byte[]{0x41}).forEachRemaining(e -> {
        byte[] address = e.getKey();
        assert address.length == 21;
        byte[] value = e.getValue();
        long beginCycle = ByteArray.toLong(value);
        addRewardCal(address, beginCycle, pb);
        pb.setExtraMessage("Scanning...");
      });
    }
    return 0;
  }

  private void addRewardCal(byte[] address, long beginCycle, ProgressBar pb) {
    if (beginCycle >= newRewardCalStartCycle) {
      return;
    }
    long endCycle = getEndCycle(address);
    //skip the last cycle reward
    if (beginCycle + 1 == endCycle) {
      beginCycle += 1;
    }
    if (beginCycle >= newRewardCalStartCycle) {
      return;
    }
    Protocol.Account account = getAccount(address);
    if (account == null || account.getVotesList().isEmpty()) {
      return;
    }
    long start = System.currentTimeMillis();
    long reward = query ? queryReward(account, beginCycle) : 0;
    long end = System.currentTimeMillis();
    logger.info("{},{},{},{}", beginCycle, Hex.toHexString(address), reward, end - start);
    pb.step(); // step by 1
  }

  private long queryReward(Protocol.Account account, long beginCycle) {
    return LongStream.range(beginCycle, newRewardCalStartCycle)
        .map(i -> {
          assert account != null;
          return computeReward(i, account);
        }).sum();
  }

  private long computeReward(long cycle, Protocol.Account account) {
    long reward = 0;
    for (Protocol.Vote vote : account.getVotesList()) {
      byte[] srAddress = vote.getVoteAddress().toByteArray();
      long totalReward = getReward(cycle, srAddress);
      if (totalReward <= 0) {
        continue;
      }
      long totalVote = getWitnessVote(cycle, srAddress);
      if (totalVote == REMARK || totalVote == 0) {
        continue;
      }
      long userVote = vote.getVoteCount();
      double voteRate = (double) userVote / totalVote;
      reward += voteRate * totalReward;
    }
    return reward;
  }

  private long getReward(long cycle, byte[] address) {
    byte[] reward = delegationStore.get(buildRewardKey(cycle, address));
    if (reward == null) {
      return 0L;
    } else {
      return ByteArray.toLong(reward);
    }
  }

  private long getWitnessVote(long cycle, byte[] address) {
    byte[] vote = delegationStore.get(buildVoteKey(cycle, address));
    if (vote == null) {
      return REMARK;
    } else {
      return ByteArray.toLong(vote);
    }
  }
  public long getEndCycle(byte[] address) {
    byte[] endCycle = delegationStore.get(buildEndCycleKey(address));
    return endCycle == null ? REMARK : ByteArray.toLong(endCycle);
  }

  private Protocol.Account getAccount(byte[] address) {
    byte[] account = accountStore.get(address);
    try {
      return Protocol.Account.parseFrom(account);
    } catch (InvalidProtocolBufferException e) {
      logger.error("getAccount error: {}", e.getMessage());
    }
    return null;
  }

  private byte[] buildVoteKey(long cycle, byte[] address) {
    return (cycle + "-" + Hex.toHexString(address) + "-vote").getBytes();
  }

  private byte[] buildRewardKey(long cycle, byte[] address) {
    return (cycle + "-" + Hex.toHexString(address) + "-reward").getBytes();
  }

  private byte[] buildEndCycleKey(byte[] address) {
    return ("end-" + Hex.toHexString(address)).getBytes();
  }
}

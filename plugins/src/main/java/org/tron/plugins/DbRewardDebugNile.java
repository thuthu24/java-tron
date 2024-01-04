package org.tron.plugins;


import java.math.BigInteger;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.stream.LongStream;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.util.encoders.Hex;
import org.tron.plugins.utils.ByteArray;
import org.tron.plugins.utils.db.DBInterface;
import org.tron.plugins.utils.db.DbTool;
import picocli.CommandLine;

@Slf4j(topic = "reward-debug-nile")
@CommandLine.Command(name = "reward-debug-nile",
    description = "reward-debug-nile.",
    exitCodeListHeading = "Exit Codes:%n",
    exitCodeList = {
        "0:Successful",
        "n:query failed,please check toolkit.log"})
public class DbRewardDebugNile implements Callable<Integer> {

  @CommandLine.Spec
  CommandLine.Model.CommandSpec spec;
  @CommandLine.Parameters(index = "0",
      description = " db path for delegation")
  private Path db;
  @CommandLine.Option(names = {"-q", "--query"})
  private boolean query;
  @CommandLine.Option(names = {"-h", "--help"}, help = true, description = "display a help message")
  private boolean help;

  @CommandLine.Option(names = {"--cycle"})
  private int cycle = -1;

  @CommandLine.Option(names = {"--sr"})
  private String sr = "412DA8912B9A05CBB3D43E16FFFF1C660920D36AC7";

  @CommandLine.Option(names = {"-s", "--start"})
  private long start = -1;

  @CommandLine.Option(names = {"-e", "--end"})
  private long end = -1;

  private DBInterface delegationStore;

  private static final BigInteger DECIMAL_OF_VI_REWARD = BigInteger.valueOf(10).pow(18);


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
    DBInterface propertiesStore = DbTool.getDB(this.db, "properties");
    if (end == -1) {
      end = ByteArray.toLong(propertiesStore.get("CURRENT_CYCLE_NUMBER".getBytes()));
    }
    if (start == -1) {
      ByteArray.toLong(propertiesStore.get("NEW_REWARD_ALGORITHM_EFFECTIVE_CYCLE".getBytes()));
    }
    return calSrVi();
  }

  private int calSrVi() {
    accumulateWitnessReward(ByteArray.fromHexString(sr));
    return 0;
  }

  private void accumulateWitnessReward(byte[] witness) {
    if (cycle > 0) {
      accumulateWitnessVi(cycle, witness);
    } else {
      LongStream.range(start, end).forEach(c -> getWitnessVi(c, witness));
    }

  }


  public void accumulateWitnessVi(long cycle, byte[] address) {
    BigInteger preVi = getWitnessVi(cycle - 1, address);
    long voteCount = getWitnessVote(cycle, address);
    spec.commandLine().getOut().println(String.format("cycle:%d ,voteCount:%d",
        cycle, voteCount));
    logger.info("cycle:{} ,voteCount:{}", cycle, voteCount);
    long reward = getReward(cycle, address);
    spec.commandLine().getOut().println(String.format("cycle:%d ,reward:%d",
        cycle, reward));
    logger.info("cycle:{} ,reward:{}", cycle, reward);
    if (reward == 0 || voteCount == 0) { // Just forward pre vi
      if (!BigInteger.ZERO.equals(preVi)) { // Zero vi will not be record
        setWitnessVi(cycle, address, preVi);
      }
    } else { // Accumulate delta vi
      BigInteger deltaVi = BigInteger.valueOf(reward)
          .multiply(DECIMAL_OF_VI_REWARD)
          .divide(BigInteger.valueOf(voteCount));
      setWitnessVi(cycle, address, preVi.add(deltaVi));
    }
  }

  private void setWitnessVi(long cycle, byte[] address, BigInteger preVi) {
    spec.commandLine().getOut().println(String.format("cycle:%d ,vi:%s",
        cycle, ByteArray.toHexString(preVi.toByteArray())));
    logger.info("cycle:{} ,vi:{}", cycle, ByteArray.toHexString(preVi.toByteArray()));
  }

  private BigInteger getWitnessVi(long cycle, byte[] address) {
    byte[] v = delegationStore.get(buildViKey(cycle, address));
    logger.info("cycle:{} ,vi:{}", cycle, ByteArray.toHexString(v));
    if (v == null) {
      return BigInteger.ZERO;
    } else {
      return new BigInteger(v);
    }
  }

  private byte[] buildViKey(long cycle, byte[] address) {
    return (cycle + "-" + Hex.toHexString(address) + "-vi").getBytes();
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
      return 0;
    } else {
      return ByteArray.toLong(vote);
    }
  }


  private byte[] buildVoteKey(long cycle, byte[] address) {
    return (cycle + "-" + Hex.toHexString(address) + "-vote").getBytes();
  }

  private byte[] buildRewardKey(long cycle, byte[] address) {
    return (cycle + "-" + Hex.toHexString(address) + "-reward").getBytes();
  }
}

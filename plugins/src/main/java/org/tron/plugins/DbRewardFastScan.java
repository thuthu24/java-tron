package org.tron.plugins;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import com.google.common.collect.Streams;
import com.google.common.primitives.Bytes;
import lombok.extern.slf4j.Slf4j;
import me.tongfei.progressbar.ProgressBar;
import org.bouncycastle.util.encoders.Hex;
import org.tron.plugins.utils.ByteArray;
import org.tron.plugins.utils.MerkleRoot;
import org.tron.plugins.utils.Sha256Hash;
import org.tron.plugins.utils.db.DBInterface;
import org.tron.plugins.utils.db.DBIterator;
import org.tron.plugins.utils.db.DbTool;
import picocli.CommandLine;

@Slf4j(topic = "reward-fast-scan")
@CommandLine.Command(name = "reward-fast-scan",
    description = "scan reward from delegation before 2023-01-20 14:00:00.",
    exitCodeListHeading = "Exit Codes:%n",
    exitCodeList = {
        "0:Successful",
        "n:query failed,please check toolkit.log"})
public class DbRewardFastScan implements Callable<Integer> {

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

  private DBInterface witnessStore;

  private DBInterface rewardCacheStore;

  private static final long newRewardCalStartCycle = 4708L;

  private static final long cycle = 1;

  private static final AtomicLong total = new AtomicLong(0L);

  private static final BigInteger DECIMAL_OF_VI_REWARD = BigInteger.valueOf(10).pow(18);

  private static final byte[] IS_DONE_KEY = new byte[]{0x00};
  private static final byte[] IS_DONE_VALUE = new byte[]{0x01};
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
    witnessStore = DbTool.getDB(this.db, "witness");
    rewardCacheStore = DbTool.getDB(this.db, "reward-cache");
    return calSROldVi();
  }

  private int calSROldVi() {
    logger.info("calcMerkleRoot start");
    spec.commandLine().getOut().println("calcMerkleRoot start");
    DBIterator iterator = witnessStore.iterator();
    iterator.seekToFirst();
    ProgressBar.wrap(iterator, "scan witness").forEachRemaining(e ->
        accumulateWitnessReward(e.getKey()));
    rewardCacheStore.put(IS_DONE_KEY, IS_DONE_VALUE);
    logger.info("total key :{}", total.get());
    calcMerkleRoot();
    logger.info("calcMerkleRoot end");
    spec.commandLine().getOut().println("calcMerkleRoot end");
    return 0;
  }

  private void calcMerkleRoot() {
    logger.info("calcMerkleRoot start");
    spec.commandLine().getOut().println("calcMerkleRoot start");
    DBIterator iterator = rewardCacheStore.iterator();
    iterator.seekToFirst();
    ArrayList<Sha256Hash> ids = Streams.stream(iterator)
        .map(this::getHash)
        .collect(Collectors.toCollection(ArrayList::new));

    Sha256Hash rewardViRootLocal = MerkleRoot.root(ids);
    logger.info("calcMerkleRoot: {}", rewardViRootLocal);
    spec.commandLine().getOut().println("calcMerkleRoot: " + rewardViRootLocal);
  }

  private Sha256Hash getHash(Map.Entry<byte[], byte[]> entry) {
    return Sha256Hash.of(true,
        Bytes.concat(entry.getKey(), entry.getValue()));
  }

  private void accumulateWitnessReward(byte[] witness) {
    LongStream.range(cycle, newRewardCalStartCycle).forEach(c -> accumulateWitnessVi(c, witness));
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
      return -1;
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


  public void accumulateWitnessVi(long cycle, byte[] address) {
    BigInteger preVi = getWitnessVi(cycle - 1, address);
    long voteCount = getWitnessVote(cycle, address);
    long reward = getReward(cycle, address);
    if (voteCount == -1 && reward != 0) {
      logger.error("cycle {} address {} voteCount is -1, reward is {}", cycle, Hex.toHexString(address), reward);
      spec.commandLine().getErr().println(spec.commandLine().getColorScheme()
          .errorText(String.format("cycle %d address %s voteCount is -1, reward is %d", cycle, Hex.toHexString(address), reward)));
    }
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

  public void setWitnessVi(long cycle, byte[] address, BigInteger value) {
    byte[] k = buildViKey(cycle, address);
    byte[] v = value.toByteArray();
    rewardCacheStore.put(k, v);
    total.incrementAndGet();
  }

  private BigInteger getWitnessVi(long cycle, byte[] address) {

    byte[] v = rewardCacheStore.get(buildViKey(cycle, address));
    if (v == null) {
      return BigInteger.ZERO;
    } else {
      return new BigInteger(v);
    }
  }

  private byte[] buildViKey(long cycle, byte[] address) {
    return (cycle + "-" + Hex.toHexString(address) + "-vi").getBytes();
  }
}

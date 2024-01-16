package org.tron.plugins;


import com.google.common.collect.Streams;
import com.google.common.primitives.Bytes;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.util.encoders.Hex;
import org.tron.plugins.utils.ByteArray;
import org.tron.plugins.utils.MerkleTree;
import org.tron.plugins.utils.Sha256Hash;
import org.tron.plugins.utils.db.DBInterface;
import org.tron.plugins.utils.db.DBIterator;
import org.tron.plugins.utils.db.DbTool;
import picocli.CommandLine;

@Slf4j(topic = "old-reward-vi")
@CommandLine.Command(name = "old-reward-vi",
    description = "old-reward-vi.",
    exitCodeListHeading = "Exit Codes:%n",
    exitCodeList = {
        "0:Successful",
        "n:query failed,please check toolkit.log"})
public class DbOldRewardVi implements Callable<Integer> {

  @CommandLine.Spec
  CommandLine.Model.CommandSpec spec;
  @CommandLine.Parameters(index = "0",
      description = " db path for delegation")
  private Path db;

  @CommandLine.Option(names = {"-h", "--help"}, help = true, description = "display a help message")
  private boolean help;


  private long end = -1;

  private DBInterface delegationStore;

  private DBInterface oldRewardViStore;

  private DBInterface witnessStore;

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
    DBInterface propertiesStore = DbTool.getDB(this.db, "properties");
    witnessStore = DbTool.getDB(this.db, "witness");
    oldRewardViStore = DbTool.getDB(this.db, "old-reward-vi");
    end = ByteArray.toLong(propertiesStore.get("NEW_REWARD_ALGORITHM_EFFECTIVE_CYCLE".getBytes()));

    return calSrVi();
  }

  private int calSrVi() {
    DBIterator iterator = witnessStore.iterator();
    iterator.seekToFirst();
    iterator.forEachRemaining(entry -> accumulateWitnessReward(entry.getKey()));
    oldRewardViStore.put(IS_DONE_KEY, IS_DONE_VALUE);
    calcMerkleRoot();
    return 0;
  }

  private void accumulateWitnessReward(byte[] witness) {
    long start = 1;
    LongStream.range(start, end).forEach(c -> accumulateOldWitnessVi(c, witness));
  }

  public void accumulateOldWitnessVi(long cycle, byte[] address) {
    BigInteger preVi = getOldWitnessVi(cycle - 1, address);
    long voteCount = getWitnessVote(cycle, address);
    long reward = getReward(cycle, address);
    if (reward == 0 || voteCount == 0) { // Just forward pre vi
      if (!BigInteger.ZERO.equals(preVi)) { // Zero vi will not be record
        setOldWitnessVi(cycle, address, preVi);
        logger.info("{},{},{},{},{}", ByteArray.toHexString(address), cycle, reward, voteCount,
            ByteArray.toHexString(preVi.toByteArray()));
      }
    } else { // Accumulate delta vi
      BigInteger deltaVi = BigInteger.valueOf(reward)
          .multiply(DECIMAL_OF_VI_REWARD)
          .divide(BigInteger.valueOf(voteCount));
      BigInteger newVi = preVi.add(deltaVi);
      setOldWitnessVi(cycle, address, newVi);
      logger.info("{},{},{},{},{}", ByteArray.toHexString(address), cycle, reward, voteCount,
          ByteArray.toHexString(newVi.toByteArray()));
    }
  }

  private void calcMerkleRoot() {
    DBIterator iterator = oldRewardViStore.iterator();
    iterator.seekToFirst();
    ArrayList<Sha256Hash> ids = Streams.stream(iterator)
        .map(this::getHash)
        .collect(Collectors.toCollection(ArrayList::new));
    Sha256Hash rewardViRootLocal = MerkleTree.getInstance().createTree(ids).getRoot().getHash();
    logger.info("calcMerkleRoot: {}", rewardViRootLocal);
  }
  private Sha256Hash getHash(Map.Entry<byte[], byte[]> entry) {
    return Sha256Hash.of(true,
        Bytes.concat(entry.getKey(), entry.getValue()));
  }

  private void setOldWitnessVi(long cycle, byte[] address, BigInteger vi) {
    oldRewardViStore.put(buildViKey(cycle, address), vi.toByteArray());
  }

  private BigInteger getOldWitnessVi(long cycle, byte[] address) {
    byte[] v = oldRewardViStore.get(buildViKey(cycle, address));
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

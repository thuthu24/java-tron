package org.tron.plugins;


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

  private DBInterface delegationStore;


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
    return calSrVi();
  }

  private int calSrVi() {
    accumulateWitnessReward(ByteArray.fromHexString("412DA8912B9A05CBB3D43E16FFFF1C660920D36AC7"));
    return 0;
  }

  private void accumulateWitnessReward(byte[] witness) {
    LongStream.range(208102, 219324).forEach(c -> getWitnessVi(c, witness));
  }

  private void getWitnessVi(long cycle, byte[] address) {
    byte[] v = delegationStore.get(buildViKey(cycle, address));
    logger.info("cycle:{} ,vi:{}", cycle, ByteArray.toHexString(v));
  }

  private byte[] buildViKey(long cycle, byte[] address) {
    return (cycle + "-" + Hex.toHexString(address) + "-vi").getBytes();
  }
}

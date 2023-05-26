package org.tron.plugins;

import com.google.protobuf.InvalidProtocolBufferException;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import me.tongfei.progressbar.ProgressBar;
import org.apache.commons.io.FileUtils;
import org.rocksdb.RocksDBException;
import org.tron.plugins.utils.ByteArray;
import org.tron.plugins.utils.db.DBInterface;
import org.tron.plugins.utils.db.DBIterator;
import org.tron.plugins.utils.db.DbTool;
import org.tron.protos.Protocol;
import picocli.CommandLine;


@Slf4j(topic = "ret-scan")
@CommandLine.Command(name = "ret-scan",
    description = "scan data from ret.",
    exitCodeListHeading = "Exit Codes:%n",
    exitCodeList = {
        "0:Successful",
        "n:query failed,please check toolkit.log"})
public class DbBlockRetScan implements Callable<Integer> {

  private static final String DB = "transactionRetStore";
  private static final DateFormat DATE_FORMAT_SHORT = new SimpleDateFormat("yyyy-MM-dd");
  private final AtomicLong scanTotal = new AtomicLong(0);
  @CommandLine.Spec
  CommandLine.Model.CommandSpec spec;
  long transTotal = 0L;
  long energyFeeTotal = 0L;
  long netFeeTotal = 0L;
  long feeTotal = 0L;

  Map<String, Long> countMap = new TreeMap<>();
  Map<String, Long> energyFeeMap = new TreeMap<>();
  Map<String, Long> netFeeMap = new TreeMap<>();
  Map<String, Long> feeMap = new TreeMap<>();
  @CommandLine.Parameters(index = "0",
      description = " db path for ret")
  private Path db;
  @CommandLine.Option(names = {"-s", "--start"},
      defaultValue = "51255816",
      description = "start block. Default: ${DEFAULT-VALUE}")
  private long start;

  @CommandLine.Option(names = {"-e", "--end"},
      defaultValue = "-1",
      description = "end block. Default: ${DEFAULT-VALUE}")
  private long end;

  @CommandLine.Option(names = {"-t", "--trans"},
      description = "file for trans hash")
  private File trans;
  @CommandLine.Option(names = {"-h", "--help"}, help = true, description = "display a help message")
  private boolean help;

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
    List<String> stringList = new ArrayList<>();
    if (Objects.nonNull(trans)) {
      spec.commandLine().getOut().format("scan trans from %s ...", trans).println();
      logger.info("scan trans from {} ...", trans);
      stringList = FileUtils.readLines(trans, StandardCharsets.UTF_8);
      spec.commandLine().getOut().format("scan trans from %s done, line %d", trans,
          stringList.size()).println();
      logger.info("scan trans from {} done,line {}", trans, stringList.size());
    }
    Set<String> stringSet = new HashSet<>(stringList);

    spec.commandLine().getOut().format("scan trans  %s size: ", stringSet.size()).println();
    logger.info("scan trans  {} size: ", stringSet.size());

    try (DBInterface database = DbTool.getDB(this.db, DB);
         DBIterator iterator = database.iterator()) {
      long min = start;
      iterator.seek(ByteArray.fromLong(min));
      if (end > 0) {
        iterator.seek(ByteArray.fromLong(end));
      } else {
        iterator.seekToLast();
      }
      long max = ByteArray.toLong(iterator.getKey());
      if (max < min) {
        max = max + min;
        min = max - min;
        max = max - min;
      }
      long total = max - min + 1;
      spec.commandLine().getOut().format("scan ret start from  %d to %d ", min, max).println();
      logger.info("scan ret start from {} to {}", min, max);
      try (ProgressBar pb = new ProgressBar("ret-scan", total)) {
        for (iterator.seek(ByteArray.fromLong(min));
             iterator.hasNext() && total-- > 0;
             iterator.next()) {
          print(iterator.getKey(), iterator.getValue(), stringSet);
          pb.step();
          pb.setExtraMessage("Reading...");
          scanTotal.getAndIncrement();
        }
      }
      spec.commandLine().getOut().format("total scan ret size: %d", scanTotal.get()).println();
      logger.info("total scan ret size: {}", scanTotal.get());
      spec.commandLine().getOut().format("total scan ret size: %d", scanTotal.get()).println();
      logger.info("transTotal: {}", transTotal);
      spec.commandLine().getOut().format("transTotal: %d", transTotal).println();
      logger.info("energyFeeTotal: {}", energyFeeTotal);
      spec.commandLine().getOut().format("energyFeeTotal: %d", energyFeeTotal).println();
      logger.info("netFeeTotal: {}", netFeeTotal);
      spec.commandLine().getOut().format("netFeeTotal: %d", netFeeTotal).println();
      logger.info("feeTotal: {}", feeTotal);
      spec.commandLine().getOut().format("feeTotal: %d", feeTotal).println();

      logger.info("countMap : ============");
      spec.commandLine().getOut().format("countMap : ============").println();
      countMap.forEach((k, v) -> logger.info("{},{}", k, v));
      countMap.forEach((k, v) -> spec.commandLine().getOut().format("%s,%d", k, v).println());
      logger.info("energyFeeMap : ============");
      spec.commandLine().getOut().format("energyFeeMap : ============").println();
      energyFeeMap.forEach((k, v) -> logger.info("{},{}", k, v));
      energyFeeMap.forEach((k, v) -> spec.commandLine().getOut().format("%s,%d", k, v).println());
      logger.info("netFeeMap : ============");
      spec.commandLine().getOut().format("netFeeMap : ============").println();
      netFeeMap.forEach((k, v) -> logger.info("{},{}", k, v));
      netFeeMap.forEach((k, v) -> spec.commandLine().getOut().format("%s,%d", k, v).println());
      logger.info("feeMap : ============");
      spec.commandLine().getOut().format("feeMap : ============").println();
      feeMap.forEach((k, v) -> logger.info("{},{}", k, v));
      feeMap.forEach((k, v) -> spec.commandLine().getOut().format("%s,%d", k, v).println());
    }
    return 0;
  }

  private void print(byte[] k, byte[] v, Set<String> stringList) {
    try {
      Protocol.TransactionRet ret = Protocol.TransactionRet.parseFrom(v);
      if (ret.getTransactioninfoList().isEmpty()) {
        return;
      }
      final String day = DATE_FORMAT_SHORT.format(new Date(ret.getBlockTimeStamp()));
      List<Protocol.TransactionInfo>  list = ret.getTransactioninfoList().stream().filter(
          info -> stringList.isEmpty() || stringList.contains(
          ByteArray.toHexString(info.getId().toByteArray()))).collect(Collectors.toList());
      if (list.isEmpty()) {
        return;
      }
      List<Protocol.ResourceReceipt> receipts = list.stream().map(
          Protocol.TransactionInfo::getReceipt).collect(Collectors.toList());
      final long energyFees = receipts.stream().mapToLong(
          Protocol.ResourceReceipt::getEnergyFee).sum();
      final long netFees = receipts.stream().mapToLong(
          Protocol.ResourceReceipt::getNetFee).sum();
      final long fees = list.stream().mapToLong(Protocol.TransactionInfo::getFee).sum();

      transTotal += list.size();
      energyFeeTotal += energyFees;
      netFeeTotal += netFees;
      feeTotal += fees;
      countMap.put(day, countMap.getOrDefault(day, 0L) + list.size());
      energyFeeMap.put(day, energyFeeMap.getOrDefault(day, 0L) + energyFees);
      netFeeMap.put(day, netFeeMap.getOrDefault(day, 0L) + netFees);
      feeMap.put(day, feeMap.getOrDefault(day, 0L) + fees);
    } catch (InvalidProtocolBufferException e) {
      logger.error("{},{}", k, v);
    }
  }
}

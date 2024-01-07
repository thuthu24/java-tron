package org.tron.plugins;

import com.google.protobuf.InvalidProtocolBufferException;
import lombok.extern.slf4j.Slf4j;
import me.tongfei.progressbar.ProgressBar;
import org.rocksdb.RocksDBException;
import org.tron.plugins.utils.ByteArray;
import org.tron.plugins.utils.Sha256Hash;
import org.tron.plugins.utils.db.DBInterface;
import org.tron.plugins.utils.db.DBIterator;
import org.tron.plugins.utils.db.DbTool;
import org.tron.protos.Protocol;
import picocli.CommandLine;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Time;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Objects;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j(topic = "block-miss")
@CommandLine.Command(name = "block-miss",
    description = "stats miss block.",
    exitCodeListHeading = "Exit Codes:%n",
    exitCodeList = {
        "0:Successful",
        "n:query failed,please check toolkit.log"})
public class DbBlockMiss implements Callable<Integer> {

  @CommandLine.Spec
  CommandLine.Model.CommandSpec spec;
  @CommandLine.Parameters(index = "0",
      description = " db path for block")
  private Path db;

  @CommandLine.Option(names = {"-s", "--start" },
          defaultValue = "1",
          description = "start block. Default: ${DEFAULT-VALUE}")
  private  long start;

  @CommandLine.Option(names = {"-e", "--end"},
      defaultValue = "-1",
      description = "end block. Default: ${DEFAULT-VALUE}")
  private long end;

  @CommandLine.Option(names = {"-sd", "--start-day" },
      description = "startDay, in 'yyyy-MM-dd' format. Default: ${DEFAULT-VALUE}")
  private  Date startDay;

  @CommandLine.Option(names = {"-ed", "--end-day"},
      description = "endDay, in 'yyyy-MM-dd' format. Default: ${DEFAULT-VALUE}")
  private Date endDay;

  @CommandLine.Option(names = {"-h", "--help"}, help = true, description = "display a help message")
  private boolean help;

  private static final  String DB = "block";
  private final AtomicLong missTotal = new AtomicLong(0);

  private long lastBlockTime = -1;

  SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");



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


  private int query() throws RocksDBException, IOException, ParseException {

    try (DBInterface database = DbTool.getDB(this.db, DB);
         DBIterator iterator = database.iterator()) {
      if (startDay != null || endDay != null) {
        return query(startDay, endDay, iterator);
      }
      long min = start;
      iterator.seek(ByteArray.fromLong(min));
      min = new Sha256Hash.BlockId(Sha256Hash.wrap(iterator.getKey())).getNum();
      if (end > 0) {
        iterator.seek(ByteArray.fromLong(end));
      } else {
        iterator.seekToLast();
      }
      long max = new Sha256Hash.BlockId(Sha256Hash.wrap(iterator.getKey())).getNum();
      return query(min, max, iterator);
    }
  }

  private int query(Date start, Date end, DBIterator iterator) throws
      InvalidProtocolBufferException, ParseException {
    final DateFormat format = new SimpleDateFormat("yyyy-MM-dd");
    if (Objects.isNull(end)) {
      end = format.parse(format.format(new Date()));
    }
    if (Objects.isNull(start)) {
      start = new Date(0);
    }

    if (end.before(start)) {
      Date tmp = start;
      start = end;
      end = tmp;
    }

    Calendar calendar = Calendar.getInstance();
    calendar.setTime(end);
    calendar.add(Calendar.DATE, 1);
    end = calendar.getTime();

    long min = 1;
    iterator.seek(ByteArray.fromLong(min));
    min = new Sha256Hash.BlockId(Sha256Hash.wrap(iterator.getKey())).getNum();
    Date minDate = new Date(Protocol.Block.parseFrom(iterator.getValue())
        .getBlockHeader().getRawData().getTimestamp());
    if (end.before(minDate) || end.equals(minDate)) {
      calendar = Calendar.getInstance();
      calendar.setTime(end);
      calendar.add(Calendar.DATE, -1);
      end = calendar.getTime();
      spec.commandLine().getOut().format("stats block error, minDate in db %s, end %s ",
          format.format(minDate), format.format(end)).println();
      logger.info("stats block error, minDate in db {}, end {}",
          format.format(minDate), format.format(end));
      return 0;
    }
    iterator.seekToLast();
    long max = new Sha256Hash.BlockId(Sha256Hash.wrap(iterator.getKey())).getNum();
    Date maxDate = new Date(Protocol.Block.parseFrom(iterator.getValue())
        .getBlockHeader().getRawData().getTimestamp());
    if (start.after(maxDate)) {
      spec.commandLine().getOut().format("stats block error, start %s, maxDate in db %s ",
          format.format(start), format.format(maxDate)).println();
      logger.info("stats block error, start {}, maxDate in db {}",
          format.format(start), format.format(maxDate));
      return 0;
    }
    if (start.before(minDate)) {
      start = minDate;
      spec.commandLine().getOut().format("reset start to minDate %s ",
          format.format(minDate)).println();
      logger.info("reset start to minDate {}", format.format(minDate));
    } else if (start.after(minDate)) {
      long startTs = start.getTime();
      long gapBlocks = TimeUnit.MILLISECONDS.toMillis(startTs - minDate.getTime()) / 3000;
      long current = min + gapBlocks;
      if (current > max) {
        current = max;
      }
      while (gapBlocks > 27 || gapBlocks < 0) {
        spec.commandLine().getOut().format("try find start block at %d ", current).println();
        logger.info("try find start block at {}", current);
        iterator.seek(ByteArray.fromLong(current));
        Protocol.Block block = Protocol.Block.parseFrom(iterator.getValue());
        long ts = block.getBlockHeader().getRawData().getTimestamp();
        min = block.getBlockHeader().getRawData().getNumber();
        gapBlocks = TimeUnit.MILLISECONDS.toMillis(ts - startTs) / 3000;
        current -= gapBlocks;
      }
      if (gapBlocks > 0) {
        while (iterator.hasPrev()) {
          Protocol.Block block = Protocol.Block.parseFrom(iterator.getValue());
          long ts = block.getBlockHeader().getRawData().getTimestamp();
          if (ts < startTs) {
            break;
          }
          min = block.getBlockHeader().getRawData().getNumber();
          iterator.prev();
        }
      }
    }
    if (end.after(maxDate)) {
      end = maxDate;
      spec.commandLine().getOut().format("reset end to maxDate %s ",
          format.format(maxDate)).println();
      logger.info("reset end to maxDate {}", format.format(maxDate));
    } else {
      long endTs = end.getTime();
      long gapBlocks = TimeUnit.MILLISECONDS.toMillis(maxDate.getTime() - endTs) / 3000;
      long current = max - gapBlocks;
      if (current < min) {
        current = min;
      }
      while (gapBlocks > 27 || gapBlocks < 0) {
        spec.commandLine().getOut().format("try find end block at %d ", current).println();
        logger.info("try find end block at {}", current);
        iterator.seek(ByteArray.fromLong(current));
        Protocol.Block block = Protocol.Block.parseFrom(iterator.getValue());
        long ts = block.getBlockHeader().getRawData().getTimestamp();
        max = block.getBlockHeader().getRawData().getNumber();
        gapBlocks = TimeUnit.MILLISECONDS.toMillis(endTs - ts) / 3000;
        current += gapBlocks;
      }
      if (gapBlocks > 0) {
        while (iterator.hasNext()) {
          Protocol.Block block = Protocol.Block.parseFrom(iterator.getValue());
          Date date = new Date(block.getBlockHeader().getRawData().getTimestamp());
          date = format.parse(format.format(date));
          if (date.after(end) || date.equals(end)) {
            break;
          }
          max = block.getBlockHeader().getRawData().getNumber();
          iterator.next();
        }
      } else {
        max = max - 1;
      }
      calendar = Calendar.getInstance();
      calendar.setTime(end);
      calendar.add(Calendar.DATE, -1);
      end = calendar.getTime();
    }

    spec.commandLine().getOut().format("stats block start from  %s to %s ",
        format.format(start), format.format(end)).println();
    logger.info("stats block start from {} to {}", format.format(start), format.format(end));
    return query(min, max, iterator);
  }

  private int query(long min, long max, DBIterator iterator) {

    if (max < min) {
      max = max + min;
      min = max - min;
      max = max - min;
    }

    long total = max - min + 1;
    spec.commandLine().getOut().format("stats block start from  %d to %d ", min, max).println();
    logger.info("stats block start from {} to {}", min, max);
    try (ProgressBar pb = new ProgressBar("block-stats", total)) {
      for (iterator.seek(ByteArray.fromLong(min)); iterator.hasNext() && total-- > 0;
             iterator.next()) {
        stats(iterator.getKey(), iterator.getValue());
        pb.step();
        pb.setExtraMessage("Reading...");
      }
    }
    spec.commandLine().getOut().format("total miss block size: %d", missTotal.get()).println();
    logger.info("total miss block size: {}", missTotal.get());

    return 0;
  }

  private  void stats(byte[] k, byte[] v) {
    try {
      Protocol.Block block = Protocol.Block.parseFrom(v);
      long num = block.getBlockHeader().getRawData().getNumber();
      if (lastBlockTime != -1) {
        long tm = block.getBlockHeader().getRawData().getTimestamp();
        Time time = new Time(tm);
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        calendar.setTime(time);
        long hour = calendar.get(Calendar.HOUR_OF_DAY);
        long min = calendar.get(Calendar.MINUTE);
        long sec = calendar.get(Calendar.SECOND);
        long gap = tm - lastBlockTime;
        if (hour % 6 != 0 || min != 0 || sec != 9) {
          if (gap > 3000) {
            logger.info("{},{}", num, sdf.format(calendar.getTime()));
            missTotal.incrementAndGet();
          }
        } else {
          if (gap > 3000 * 3) {
            logger.info("{},{}", num, sdf.format(calendar.getTime()));
            missTotal.incrementAndGet();
          }
        }
      }
      lastBlockTime =  block.getBlockHeader().getRawData().getTimestamp();
    } catch (InvalidProtocolBufferException e) {
      logger.error("{},{}", k, v);
    }
  }

}

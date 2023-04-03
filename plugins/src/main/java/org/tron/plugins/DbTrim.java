package org.tron.plugins;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.primitives.Longs;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.concurrent.Callable;
import java.util.stream.LongStream;
import lombok.extern.slf4j.Slf4j;
import me.tongfei.progressbar.ProgressBar;
import org.rocksdb.RocksDBException;
import org.tron.plugins.utils.DBUtils;
import org.tron.plugins.utils.db.DBInterface;
import org.tron.plugins.utils.db.DBIterator;
import org.tron.plugins.utils.db.DbTool;
import org.tron.protos.Protocol;
import picocli.CommandLine;
import picocli.CommandLine.Option;

@Slf4j(topic = "trim")
@CommandLine.Command(name = "trim", description = "A helper to trim db for block、trans.")
public class DbTrim implements Callable<Integer> {

  private static final String BLOCK_DB_NAME = "block";
  private static final String BLOCK_INDEX_DB_NAME = "block-index";
  private static final String TRANS_DB_NAME = "trans";

  @CommandLine.Spec
  static CommandLine.Model.CommandSpec spec;

  @Option(names = {"-d", "--database-directory"},
      defaultValue = "output-directory/database",
      description = "java-tron database directory. Default: ${DEFAULT-VALUE}")
  private String databaseDirectory;

  @Option(names = { "-s", "--start"},
          required = true,
          description = "start to trim")
  private long start;

  @Option(names = {"-h", "--help"})
  private boolean help;


  @Override
  public Integer call() throws Exception {
    if (help) {
      spec.commandLine().usage(System.out);
      return 0;
    }

    File dbDirectory = new File(databaseDirectory);
    if (!dbDirectory.exists()) {
      spec.commandLine().getErr().format("Directory %s does not exist.",
          databaseDirectory).println();
      logger.info("Directory {} does not exist.", databaseDirectory);
      return 404;
    }

    if (!dbDirectory.isDirectory()) {
      spec.commandLine().getErr().format(" %s is not directory.",
          databaseDirectory).println();
      logger.info("{} is not directory.", databaseDirectory);
      return 405;
    }


    final long time = System.currentTimeMillis();

    Trim trim = new Trimmer(databaseDirectory, start);
    trim.doTrim();
    spec.commandLine().getOut().println("trim db done.");

    logger.info("DatabaseDirectory:{}, database trim use {} seconds total.",
        databaseDirectory, (System.currentTimeMillis() - time) / 1000);

    return 0;
  }


  interface Trim {
    default void doTrim() throws IOException, RocksDBException {
    }
  }

  static class Trimmer implements Trim {

    private final long startTime;
    private final Path sourceDir;
    private final long start;

    public Trimmer(String src, long start) {
      this.sourceDir = Paths.get(src);
      this.startTime = System.currentTimeMillis();
      this.start = start;
    }

    @Override
    public void doTrim() throws IOException, RocksDBException {
      checkArgument(start > 1, " start must > 1");
      try (DBInterface blockStore = DbTool.getDB(sourceDir, BLOCK_DB_NAME);
           DBInterface blockIndexStore = DbTool.getDB(sourceDir, BLOCK_INDEX_DB_NAME);
           DBInterface transStore = DbTool.getDB(sourceDir, TRANS_DB_NAME)) {
        DBIterator iterator = blockIndexStore.iterator();
        iterator.seek(Longs.toByteArray(1));
        checkArgument(iterator.valid(), " block is empty");
        final long min = Longs.fromByteArray(iterator.getKey());
        iterator.seekToLast();
        checkArgument(iterator.valid(), "end not find");
        long max = Longs.fromByteArray(iterator.getKey());
        iterator.close();
        checkArgument(start >= min && start <= max,
                "start %s need in [%s, %s]", start, min, max);

        spec.commandLine().getOut().format("Db trim start %d from  %d.", start, max).println();
        logger.info("Db trim start {} from  {}.", start, max);

        ProgressBar.wrap(LongStream.rangeClosed(start, max).boxed()
                .sorted(Collections.reverseOrder()), "trim").forEach(blockNum -> {
                  try {
                    byte[] blockId = blockIndexStore.get(Longs.toByteArray(blockNum));
                    byte[] block = blockStore.get(blockId);
                    // delete trans
                    Protocol.Block.parseFrom(block).getTransactionsList().stream()
                            .map(tc -> DBUtils.getTransactionId(tc).getBytes())
                            .forEach(transStore::delete);
                    // delete block-index
                    blockIndexStore.delete(Longs.toByteArray(blockNum));
                    //delete block
                    blockStore.delete(blockId);
                  } catch (IOException e) {
                    throw new RuntimeException(e.getMessage());
                  }
                });

        DBIterator blockIterator = blockStore.iterator();
        DBIterator indexIterator = blockIndexStore.iterator();
        blockIterator.seekToLast();
        indexIterator.seekToLast();
        checkArgument(blockIterator.valid(), "block end not find");
        checkArgument(indexIterator.valid(), "block-index end not find");
        long latestBlockNum = Longs.fromByteArray(indexIterator.getKey());
        long header = Protocol.Block.parseFrom(blockIterator.getValue())
                .getBlockHeader().getRawData().getNumber();
        checkArgument(latestBlockNum == header,
                "block !=  block-index, %s > %s",
                header, latestBlockNum);
        checkArgument(latestBlockNum == start - 1,
                "block !=  expect, %s != %s",
                latestBlockNum, start - 1);
        blockIterator.close();
        indexIterator.close();
        long use = System.currentTimeMillis() - startTime;
        spec.commandLine().getOut().format("Db trim use %d ms ", use).println();
        logger.info("Db trim use {} ms.", use);
      }
    }
  }
}
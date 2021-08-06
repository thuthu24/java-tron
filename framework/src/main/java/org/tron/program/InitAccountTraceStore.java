package org.tron.program;

import static org.fusesource.leveldbjni.JniDBFactory.factory;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.google.common.primitives.Bytes;
import com.google.common.primitives.Longs;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.fusesource.leveldbjni.JniDBFactory;
import org.iq80.leveldb.CompressionType;
import org.iq80.leveldb.DB;
import org.iq80.leveldb.DBIterator;
import org.iq80.leveldb.ReadOptions;
import org.iq80.leveldb.WriteBatch;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.AccountTraceCapsule;

@Slf4j(topic = "db")
public class InitAccountTraceStore {

  private static final int BATCH  = 256;

  public static void main(String[] args) throws Exception {

    int code = run(args);
    logger.info("exit code {}.", code);
    System.out.printf("exit code %d.\n", code);
    System.exit(code);
  }

  public static int run(String[] args) throws Exception {

    Args parameters = new Args();
    JCommander jc = JCommander.newBuilder()
        .addObject(parameters)
        .build();
    jc.parse(args);
    if (parameters.help) {
      jc.usage();
      return 0;
    }
    String dbPath = parameters.databaseDirectory;
    long latestBlockNum = latestBlockNum(dbPath);
    if (latestBlockNum < 1) {
      System.out.println("latestBlockNum :" + latestBlockNum);
      return -1;
    }
    System.out.println("latestBlockNum :" + latestBlockNum);
    DB account = newLevelDb(Paths.get(dbPath, "database", "account"));
    DB accountTrace = newLevelDb(Paths.get(dbPath, "database", "account-trace"));
    initAccountTraceStore(account, accountTrace,latestBlockNum);
    return 0;
  }

  public static long latestBlockNum(String db) {

    Path path  = Paths.get(db, "database", "block");
    ReadOptions readOptions = new ReadOptions().fillCache(false);
    try (DB block = newLevelDb(path);
         DBIterator iterable = block.iterator(readOptions)) {
      iterable.seekToLast();
      if (iterable.hasNext()) {
        Map.Entry<byte[],byte[]> blockV = iterable.peekNext();
        return Longs.fromByteArray(blockV.getKey());
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
    return -1;
  }


  public static void initAccountTraceStore(DB account, DB accountTrace, long latestBlockNum) {
    // convert
    List<byte[]> keys = new ArrayList<>(BATCH);
    List<byte[]> values = new ArrayList<>(BATCH);
    long count = 0;
    try (DBIterator accountIterator = account.iterator(
        new org.iq80.leveldb.ReadOptions().fillCache(false))) {
      JniDBFactory.pushMemoryPool(1024 * 1024);
      accountIterator.seekToFirst();

      while (accountIterator.hasNext()) {
        count++;
        if (count % 10000 == 0) {
          System.out.println("account scan : " + count);
        }
        Map.Entry<byte[], byte[]> entry = accountIterator.next();
        byte[] address = entry.getKey();
        byte[] value = entry.getValue();
        AccountCapsule accountCapsule = new AccountCapsule(value);
        byte[] key = Bytes.concat(address, Longs.toByteArray(xor(latestBlockNum)));
        AccountTraceCapsule accountTraceCapsule =
            new AccountTraceCapsule(accountCapsule.getBalance());
        keys.add(key);
        values.add(accountTraceCapsule.getData());
        if (keys.size() >= BATCH) {
          try {
            batchInsert(accountTrace, keys, values);
          } catch (Exception e) {
            logger.error("{}", e);
            return;
          }
        }
      }
      if (!keys.isEmpty()) {
        try {
          batchInsert(accountTrace, keys, values);
        } catch (Exception e) {
          logger.error("{}", e);
        }
      }
      System.out.println("account All : " + count);
    }  catch (Exception e) {
      logger.error("{}", e);
    } finally {
      try {
        account.close();
        accountTrace.close();
        JniDBFactory.popMemoryPool();
      } catch (Exception e1) {
        logger.error("{}", e1);
      }
    }
  }

  private static long xor(long l) {
    return l ^ Long.MAX_VALUE;
  }

  private static void batchInsert(DB leveldb, List<byte[]> keys, List<byte[]> values)
      throws Exception {
    try (WriteBatch batch = leveldb.createWriteBatch()) {
      for (int i = 0; i < keys.size(); i++) {
        byte[] k = keys.get(i);
        byte[] v = values.get(i);
        batch.put(k, v);
      }
      leveldb.write(batch);
    }
    keys.clear();
    values.clear();
  }

  public  static DB newLevelDb(Path db) throws Exception {
    File file = db.toFile();
    return factory.open(file, newDefaultLevelDbOptions());
  }

  public static org.iq80.leveldb.Options newDefaultLevelDbOptions() {
    org.iq80.leveldb.Options dbOptions = new org.iq80.leveldb.Options();
    dbOptions.createIfMissing(true);
    dbOptions.paranoidChecks(true);
    dbOptions.verifyChecksums(true);
    dbOptions.compressionType(CompressionType.SNAPPY);
    dbOptions.blockSize(4 * 1024);
    dbOptions.writeBufferSize(32 * 1024 * 1024);
    dbOptions.cacheSize(8 * 1024 * 1024L);
    dbOptions.maxOpenFiles(5000);
    return dbOptions;
  }

  public static class Args {
    @Parameter
    private List<String> parameters = new ArrayList<>();

    @Parameter(names = {"-d", "--database-directory"}, description = "java-tron database directory")
    private  String databaseDirectory = "output-directory";

    @Parameter(names = {"-h", "--help"}, help = true)
    private  boolean help;
  }
}

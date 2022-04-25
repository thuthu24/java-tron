package org.tron.program;

import static org.fusesource.leveldbjni.JniDBFactory.factory;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.google.common.primitives.Longs;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import org.fusesource.leveldbjni.JniDBFactory;
import org.iq80.leveldb.CompressionType;
import org.iq80.leveldb.DB;
import org.iq80.leveldb.DBIterator;
import org.rocksdb.BlockBasedTableConfig;
import org.rocksdb.BloomFilter;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksIterator;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.setting.RocksDbSettings;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.Sha256Hash;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.ExchangeCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.protos.Protocol.Transaction;
import org.tron.protos.contract.ExchangeContract;

public class ExchangeTransactionScan {

  private static final String exchangeV2Store = "exchange-v2";
  private static final String exchangeStore = "exchange";
  private static final String blockStore = "block";

  static {
    RocksDB.loadLibrary();
  }

  public static org.iq80.leveldb.Options newDefaultLevelDbOptions() {
    org.iq80.leveldb.Options dbOptions = new org.iq80.leveldb.Options();
    dbOptions.createIfMissing(true);
    dbOptions.paranoidChecks(true);
    dbOptions.verifyChecksums(true);
    dbOptions.compressionType(CompressionType.SNAPPY);
    dbOptions.blockSize(4 * 1024);
    dbOptions.writeBufferSize(10 * 1024 * 1024);
    dbOptions.cacheSize(10 * 1024 * 1024L);
    dbOptions.maxOpenFiles(1000);
    return dbOptions;
  }

  private static Options newDefaultRocksDbOptions() {
    Options options = new Options();
    options.setCreateIfMissing(true);
    options.setIncreaseParallelism(1);
    options.setLevelCompactionDynamicLevelBytes(true);
    options.setMaxOpenFiles(5000);

    // general options supported user config
    options.setNumLevels(7);
    options.setMaxBytesForLevelMultiplier(10);
    options.setMaxBytesForLevelBase(256);
    options.setMaxBackgroundCompactions(32);
    options.setTargetFileSizeMultiplier(1);
    options.setTargetFileSizeBase(256);

    // table options
    final BlockBasedTableConfig tableCfg;
    options.setTableFormatConfig(tableCfg = new BlockBasedTableConfig());
    tableCfg.setBlockSize(64);
    tableCfg.setBlockCache(RocksDbSettings.getCache());
    tableCfg.setCacheIndexAndFilterBlocks(true);
    tableCfg.setPinL0FilterAndIndexBlocksInCache(true);
    tableCfg.setFilter(new BloomFilter(10, false));
    return options;
  }

  public static DB newLevelDb(Path db) throws Exception {
    DB database;
    File file = db.toFile();
    org.iq80.leveldb.Options dbOptions = newDefaultLevelDbOptions();
    database = factory.open(file, dbOptions);
    return database;
  }

  public static RocksDB newRocksDb(Path db) throws Exception {
    RocksDB database;
    try (Options options = newDefaultRocksDbOptions()) {
      database = RocksDB.open(options, db.toString());
    }
    return database;
  }


  private static void doScan(Args parameters) throws Exception {
    final long time = System.currentTimeMillis();
    DB block = newLevelDb(Paths.get(parameters.databaseDirectory, blockStore));
    DB exchange = newLevelDb(Paths.get(parameters.databaseDirectory, exchangeStore));
    DB exchangeV2 = newLevelDb(Paths.get(parameters.databaseDirectory, exchangeV2Store));

    long lastNum = parameters.startNum;
    byte[] numBytes = Longs.toByteArray(parameters.startNum);
    try (DBIterator levelIterator = block.iterator(
        new org.iq80.leveldb.ReadOptions().fillCache(false))) {
      JniDBFactory.pushMemoryPool(1024 * 1024);
      if (parameters.startNum == 0) {
        levelIterator.seekToFirst();
      } else {
        levelIterator.seek(numBytes);
      }

      while (levelIterator.hasNext()) {
        Map.Entry<byte[], byte[]> entry = levelIterator.next();
        byte[] value = entry.getValue();

        BlockCapsule blockCapsule = new BlockCapsule(value);
        lastNum = blockCapsule.getNum();
        if (parameters.startNum > lastNum) {
          continue;
        }
        if (lastNum > parameters.endNum) {
          lastNum--;
          break;
        }
        for (TransactionCapsule transactionCapsule : blockCapsule.getTransactions()) {
          Transaction transaction = transactionCapsule.getInstance();
          if (transaction.getRawData().getContractList().isEmpty()) {
            continue;
          }
          if (transaction.getRawData().getContract(0).getType() ==
              Transaction.Contract.ContractType.ExchangeTransactionContract) {
            ExchangeContract.ExchangeTransactionContract exchangeTransactionContract =
                transaction.getRawData().getContract(0).getParameter()
                    .unpack(ExchangeContract.ExchangeTransactionContract.class);
            byte[] bytes = exchangeV2.get(ByteArray.fromLong(
                exchangeTransactionContract.getExchangeId()));
            if (bytes == null) {
              bytes = exchange.get(ByteArray.fromLong(exchangeTransactionContract.getExchangeId()));
            }
            ExchangeCapsule exchangeCapsule = new ExchangeCapsule(bytes);

            byte[] tokenID = exchangeTransactionContract.getTokenId().toByteArray();
            long tokenQuant = exchangeTransactionContract.getQuant();

            long anotherTokenQuant = exchangeCapsule.transaction(tokenID, tokenQuant);
            long anotherTokenQuantStrict = exchangeCapsule.transactionStrict(tokenID, tokenQuant);
            if (anotherTokenQuant != anotherTokenQuantStrict) {
              System.out.printf("'num':'%d' , 'tx':'%s', math:'%d', strict:'%d' \n",
                  blockCapsule.getNum(),
                  Sha256Hash.of(CommonParameter.getInstance().isECKeyCryptoEngine(),
                      transaction.getRawData().toByteArray()),
                  anotherTokenQuant, anotherTokenQuantStrict);
            }
          }
        }
      }
    } catch (Exception e) {
      e.printStackTrace();
    } finally {
      try {
        block.close();
        exchange.close();
        exchangeV2.close();
        JniDBFactory.popMemoryPool();
      } catch (Exception e1) {
        e1.printStackTrace();
      }
    }

    System.out.printf("Scan from %d to %d  use %d seconds total.\n", parameters.startNum, lastNum,
        (System.currentTimeMillis() - time) / 1000);
  }

  private static void doScanRocksDb(Args parameters) throws Exception {
    final long time = System.currentTimeMillis();
    RocksDB block = newRocksDb(Paths.get(parameters.databaseDirectory, blockStore));
    RocksDB exchange = newRocksDb(Paths.get(parameters.databaseDirectory, exchangeStore));
    RocksDB exchangeV2 = newRocksDb(Paths.get(parameters.databaseDirectory, exchangeV2Store));
    long lastNum = parameters.startNum;
    byte[] numBytes = Longs.toByteArray(parameters.startNum);
    try (RocksIterator rocksIterator = block.newIterator(
        new org.rocksdb.ReadOptions().setFillCache(false))) {

      if (parameters.startNum == 0) {
        rocksIterator.seekToFirst();
      } else {
        rocksIterator.seek(numBytes);
      }

      for (; rocksIterator.isValid(); rocksIterator.next()) {
        byte[] value = rocksIterator.value();
        BlockCapsule blockCapsule = new BlockCapsule(value);
        lastNum = blockCapsule.getNum();
        if (parameters.startNum > lastNum) {
          continue;
        }
        if (lastNum > parameters.endNum) {
          lastNum--;
          break;
        }
        for (TransactionCapsule transactionCapsule : blockCapsule.getTransactions()) {
          Transaction transaction = transactionCapsule.getInstance();
          if (transaction.getRawData().getContractList().isEmpty()) {
            continue;
          }
          if (transaction.getRawData().getContract(0).getType() ==
              Transaction.Contract.ContractType.ExchangeTransactionContract) {
            ExchangeContract.ExchangeTransactionContract exchangeTransactionContract =
                transaction.getRawData().getContract(0).getParameter()
                    .unpack(ExchangeContract.ExchangeTransactionContract.class);
            byte[] bytes = exchangeV2.get(ByteArray.fromLong(
                exchangeTransactionContract.getExchangeId()));
            if (bytes == null) {
              bytes = exchange.get(ByteArray.fromLong(exchangeTransactionContract.getExchangeId()));
            }
            ExchangeCapsule exchangeCapsule = new ExchangeCapsule(bytes);

            byte[] tokenID = exchangeTransactionContract.getTokenId().toByteArray();
            long tokenQuant = exchangeTransactionContract.getQuant();

            long anotherTokenQuant = exchangeCapsule.transaction(tokenID, tokenQuant);
            long anotherTokenQuantStrict = exchangeCapsule.transactionStrict(tokenID, tokenQuant);
            if (anotherTokenQuant != anotherTokenQuantStrict) {
              System.out.printf("'num':'%d' , 'tx':'%s', math:'%d', strict:'%d' \n",
                  blockCapsule.getNum(),
                  Sha256Hash.of(CommonParameter.getInstance().isECKeyCryptoEngine(),
                      transaction.getRawData().toByteArray()),
                  anotherTokenQuant, anotherTokenQuantStrict);
            }
          }
        }
      }
    } catch (Exception e) {
      e.printStackTrace();
    } finally {
      try {
        block.close();
        exchange.close();
        exchangeV2.close();
      } catch (Exception e1) {
        e1.printStackTrace();
      }
    }

    System.out.printf("Scan from %d to %d  use %d seconds total.\n", parameters.startNum, lastNum,
        (System.currentTimeMillis() - time) / 1000);
  }

  public static void main(String[] args) throws Exception {
    Args parameters = new Args();
    JCommander jc = JCommander.newBuilder()
        .addObject(parameters)
        .build();
    jc.parse(args);
    if (parameters.help) {
      jc.usage();
      return;
    }
    if ("leveldb".equalsIgnoreCase(parameters.databaseType)) {
      doScan(parameters);
    } else if ("rocksdb".equalsIgnoreCase(parameters.databaseType)) {
      doScanRocksDb(parameters);
    }

  }

  public static class Args {

    @Parameter(names = {"-d", "--database-directory"}, description = "java-tron database directory")
    private String databaseDirectory = "output-directory/database";

    @Parameter(names = {"-t", "--database-type"}, description = "leveldb|rocksdb")
    private String databaseType = "leveldb";

    @Parameter(names = {"-s", "--start-num"}, description = "san block start num")
    private long startNum = 0;

    @Parameter(names = {"-e", "--end-num"}, description = "san block end num")
    private long endNum = Long.MAX_VALUE;

    @Parameter(names = {"-h", "--help"}, help = true)
    private boolean help;
  }
}
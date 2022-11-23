package org.tron.plugins;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.primitives.Bytes;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import lombok.extern.slf4j.Slf4j;
import me.tongfei.progressbar.ProgressBar;
import org.rocksdb.RocksDBException;
import org.tron.plugins.utils.ByteArray;
import org.tron.plugins.utils.DBUtils;
import org.tron.plugins.utils.FileUtils;
import org.tron.plugins.utils.db.DBInterface;
import org.tron.plugins.utils.db.DBIterator;
import org.tron.plugins.utils.db.DbTool;
import org.tron.protos.Protocol;
import picocli.CommandLine;

@Slf4j(topic = "lite")
@CommandLine.Command(name = "lite",
    description = "Split lite data for java-tron.",
    exitCodeListHeading = "Exit Codes:%n",
    exitCodeList = {
        "0:Successful",
        "1:Internal error: exception occurred,please check toolkit.log"})
public class DbLite implements Callable<Integer> {

  private static final byte[] DB_KEY_LOWEST_BLOCK_NUM = "lowest_block_num".getBytes();
  private static final byte[] DB_KEY_NODE_TYPE = "node_type".getBytes();

  private static final long START_TIME = System.currentTimeMillis() / 1000;

  private static long RECENT_BLKS = 65536;

  private static final String SNAPSHOT_DIR_NAME = "snapshot";
  private static final String HISTORY_DIR_NAME = "history";
  private static final String INFO_FILE_NAME = "info.properties";
  private static final String BACKUP_DIR_PREFIX = ".bak_";
  private static final String CHECKPOINT_DB = "tmp";
  private static final String BLOCK_DB_NAME = "block";
  private static final String BLOCK_INDEX_DB_NAME = "block-index";
  private static final String TRANS_DB_NAME = "trans";
  private static final String COMMON_DB_NAME = "common";
  private static final String TRANSACTION_RET_DB_NAME = "transactionRetStore";
  private static final String TRANSACTION_HISTORY_DB_NAME = "transactionHistoryStore";
  private static final String PROPERTIES_DB_NAME = "properties";
  private static final String TRANS_CACHE_DB_NAME = "trans-cache";

  private static final String DIR_FORMAT_STRING = "%s%s%s";

  private static final List<String> archiveDbs = Arrays.asList(
      BLOCK_DB_NAME,
      BLOCK_INDEX_DB_NAME,
      TRANS_DB_NAME,
      TRANSACTION_RET_DB_NAME,
      TRANSACTION_HISTORY_DB_NAME);

  enum Operate { split, merge }

  enum Type { snapshot, history }

  @CommandLine.Spec
  CommandLine.Model.CommandSpec spec;

  @CommandLine.Option(
      names = {"--operate", "-o"},
      defaultValue = "split",
      description = "operate: [ ${COMPLETION-CANDIDATES} ]. Default: ${DEFAULT-VALUE}",
      order = 1)
  private Operate operate;

  @CommandLine.Option(
      names = {"--type", "-t"},
      defaultValue = "snapshot",
      description = "only used with operate=split: [ ${COMPLETION-CANDIDATES} ]."
          + " Default: ${DEFAULT-VALUE}",
      order = 2)
  private Type type;

  @CommandLine.Option(
      names = {"--fn-data-path", "-fn"},
      required = true,
      description = "the database path for split.",
      order = 3)
  private String fnDataPath;

  @CommandLine.Option(
      names = {"--dataset-path", "-ds"},
      required = true,
      description = "when operation is `split`,"
          + "`dataset-path` is the path that store the `snapshot` or `history`,"
          + "when operation is `split`,"
          + "`dataset-path` is be the `history` data path.",
      order = 4)
  private String datasetPath;

  @CommandLine.Option(
      names = {"--help", "-h"},
      help = true,
      order = 5)
  private boolean help;


  @Override
  public Integer call() {
    if (help) {
      spec.commandLine().usage(System.out);
      return 0;
    }
    try {
      switch (this.operate) {
        case split:
          if (Type.snapshot == this.type) {
            generateSnapshot(fnDataPath, datasetPath);
          } else if (Type.history == type) {
            generateHistory(fnDataPath, datasetPath);
          }
          break;
        case merge:
          completeHistoryData(datasetPath, fnDataPath);
          break;
        default:
      }
      return 0;
    } catch (Exception e) {
      logger.error("{}", e);
      spec.commandLine().getErr().println(spec.commandLine().getColorScheme()
          .errorText(e.getMessage()));
      spec.commandLine().usage(System.out);
      return 1;
    } finally {
      DbTool.close();
    }
  }

  /**
   * Create the snapshot dataset.
   *
   * @param sourceDir the original fullnode database dir,
   *                  same with {storage.db.directory} in conf file.
   * @param snapshotDir the path that stores the snapshot dataset
   */
  public void generateSnapshot(String sourceDir, String snapshotDir) {
    logger.info("Start create snapshot.");
    spec.commandLine().getOut().println("Start create snapshot.");
    long start = System.currentTimeMillis();
    snapshotDir = Paths.get(snapshotDir, SNAPSHOT_DIR_NAME).toString();
    try {
      hasEnoughBlock(sourceDir);
      List<String> snapshotDbs = getSnapshotDbs(sourceDir);
      split(sourceDir, snapshotDir, snapshotDbs);
      mergeCheckpoint2Snapshot(sourceDir, snapshotDir);
      // write genesisBlock , latest recent blocks and trans
      fillSnapshotBlockAndTransDb(sourceDir, snapshotDir);
      generateInfoProperties(Paths.get(snapshotDir, INFO_FILE_NAME).toString(), sourceDir);
    } catch (IOException | RocksDBException e) {
      logger.error("Create snapshot failed, {}.", e.getMessage());
      spec.commandLine().getErr().println(spec.commandLine().getColorScheme()
          .stackTraceText(e));
      return;
    }
    long during = (System.currentTimeMillis() - start) / 1000;
    logger.info("Create snapshot finished, take {} s.", during);
    spec.commandLine().getOut().format("Create snapshot finished, take %d s.", during).println();
  }

  /**
   * Create the history dataset.
   *
   * @param sourceDir the original fullnode database dir,
   *                  same with {storage.db.directory} in conf file.
   * @param historyDir the path that stores the history dataset
   */
  public void generateHistory(String sourceDir, String historyDir) {
    logger.info("Start create history.");
    spec.commandLine().getOut().println("Start create history.");
    long start = System.currentTimeMillis();
    historyDir = Paths.get(historyDir, HISTORY_DIR_NAME).toString();
    try {
      if (isLite(sourceDir)) {
        throw new IllegalStateException(
            String.format("Unavailable sourceDir: %s is not fullNode data.", sourceDir));
      }
      hasEnoughBlock(sourceDir);
      split(sourceDir, historyDir, archiveDbs);
      mergeCheckpoint2History(sourceDir, historyDir);
      generateInfoProperties(Paths.get(historyDir, INFO_FILE_NAME).toString(), sourceDir);
    } catch (IOException | RocksDBException e) {
      logger.error("Create history failed, {}.", e.getMessage());
      spec.commandLine().getErr().println(spec.commandLine().getColorScheme()
          .stackTraceText(e));
      return;
    }
    long during = (System.currentTimeMillis() - start) / 1000;
    logger.info("Create history finished, take {} s.", during);
    spec.commandLine().getOut().format("Create history finished, take %d s.", during).println();
  }

  /**
   * Merge the history dataset into database.
   *
   * @param historyDir the path that stores the history dataset
   *
   * @param databaseDir lite fullnode database path
   */
  public void completeHistoryData(String historyDir, String databaseDir) {
    logger.info("Start merge history to lite node.");
    spec.commandLine().getOut().println("Start merge history to lite node.");
    long start = System.currentTimeMillis();
    BlockNumInfo blockNumInfo = null;
    try {
      // check historyDir is from lite data
      if (isLite(historyDir)) {
        throw new IllegalStateException(
            String.format("Unavailable history: %s is not generated by fullNode data.",
                historyDir));
      }
      // 1. check block number and genesis block are compatible,
      //    and return the block numbers of snapshot and history
      blockNumInfo = checkAndGetBlockNumInfo(historyDir, databaseDir);
      // 2. move archive dbs to bak
      backupArchiveDbs(databaseDir);
      // 3. copy history data to databaseDir
      copyHistory2Database(historyDir, databaseDir);
      // 4. delete the duplicate block data in history data
      trimHistory(databaseDir, blockNumInfo);
      // 5. merge bak to database
      mergeBak2Database(databaseDir);
      // 6. delete snapshot flag
      deleteSnapshotFlag(databaseDir);
    } catch (IOException | RocksDBException  e) {
      logger.error("Merge history data to database failed, {}.", e.getMessage());
      spec.commandLine().getErr().println(spec.commandLine().getColorScheme()
          .stackTraceText(e));
      return;
    }
    long during = (System.currentTimeMillis() - start) / 1000;
    logger.info("Merge history finished, take {} s.", during);
    spec.commandLine().getOut().format("Merge history finished, take %d s.", during).println();
  }

  private List<String> getSnapshotDbs(String sourceDir) {
    List<String> snapshotDbs = Lists.newArrayList();
    File basePath = new File(sourceDir);
    Arrays.stream(Objects.requireNonNull(basePath.listFiles()))
            .filter(File::isDirectory)
            .filter(dir -> !archiveDbs.contains(dir.getName()))
            .forEach(dir -> snapshotDbs.add(dir.getName()));
    return snapshotDbs;
  }

  private void mergeCheckpoint2Snapshot(String sourceDir, String historyDir) {
    List<String> snapshotDbs = getSnapshotDbs(sourceDir);
    mergeCheckpoint(sourceDir, historyDir, snapshotDbs);
  }

  private void mergeCheckpoint2History(String sourceDir, String destDir) {
    mergeCheckpoint(sourceDir, destDir, archiveDbs);
  }

  private void split(String sourceDir, String destDir, List<String> dbs) throws IOException {
    logger.info("Begin to split the dbs.");
    spec.commandLine().getOut().println("Begin to split the dbs.");
    if (!new File(sourceDir).isDirectory()) {
      throw new RuntimeException(String.format("sourceDir: %s must be a directory ", sourceDir));
    }
    File destPath = new File(destDir);
    if (new File(destDir).exists()) {
      throw new RuntimeException(String.format(
          "destDir: %s is already exist, please remove it first", destDir));
    }
    if (!destPath.mkdirs()) {
      throw new RuntimeException(String.format("destDir: %s create failed, please check", destDir));
    }
    FileUtils.copyDatabases(Paths.get(sourceDir), Paths.get(destDir), dbs);
  }

  private void mergeCheckpoint(String sourceDir, String destDir, List<String> destDbs) {
    logger.info("Begin to merge checkpoint to dataset.");
    spec.commandLine().getOut().println("Begin to merge checkpoint to dataset.");
    try {
      List<String> cpList = getCheckpointV2List(sourceDir);
      if (cpList.size() > 0) {
        for (String cp : cpList) {
          DBInterface checkpointDb = DbTool.getDB(
              sourceDir + "/" + DBUtils.CHECKPOINT_DB_V2, cp);
          recover(checkpointDb, destDir, destDbs);
        }
      } else if (Paths.get(sourceDir, CHECKPOINT_DB).toFile().exists()) {
        DBInterface tmpDb = DbTool.getDB(sourceDir, CHECKPOINT_DB);
        recover(tmpDb, destDir, destDbs);
      }
    } catch (IOException | RocksDBException e) {
      throw new RuntimeException(e);
    }
  }

  private void recover(DBInterface db, String destDir, List<String> destDbs)
      throws IOException, RocksDBException {
    try (DBIterator iterator = db.iterator()) {
      for (iterator.seekToFirst(); iterator.hasNext(); iterator.next()) {
        byte[] key = iterator.getKey();
        byte[] value = iterator.getValue();
        String dbName = DBUtils.simpleDecode(key);
        // skip trans-cache db
        if (TRANS_CACHE_DB_NAME.equalsIgnoreCase(dbName)) {
          continue;
        }
        byte[] realKey = Arrays.copyOfRange(key, dbName.getBytes().length + 4, key.length);
        byte[] realValue =
            value.length == 1 ? null : Arrays.copyOfRange(value, 1, value.length);
        if (destDbs != null && destDbs.contains(dbName)) {
          DBInterface destDb = DbTool.getDB(destDir, dbName);
          if (realValue != null) {
            destDb.put(realKey, realValue);
          } else {
            byte op = value[0];
            if (DBUtils.Operator.DELETE.getValue() == op) {
              destDb.delete(realKey);
            } else {
              destDb.put(realKey, new byte[0]);
            }
          }
        }
      }
    }
  }

  private void generateInfoProperties(String propertyfile, String databaseDir)
          throws IOException, RocksDBException {
    logger.info("Create {} for dataset.", INFO_FILE_NAME);
    spec.commandLine().getOut().format("Create %s for dataset.", INFO_FILE_NAME).println();
    if (!FileUtils.createFileIfNotExists(propertyfile)) {
      throw new RuntimeException("Create properties file failed.");
    }
    if (!FileUtils.writeProperty(propertyfile, DBUtils.SPLIT_BLOCK_NUM,
            Long.toString(getLatestBlockHeaderNum(databaseDir)))) {
      throw new RuntimeException("Write properties file failed.");
    }
  }

  private long getLatestBlockHeaderNum(String databaseDir) throws IOException, RocksDBException {
    // query latest_block_header_number from checkpoint first
    final String latestBlockHeaderNumber = "latest_block_header_number";
    List<String> cpList = getCheckpointV2List(databaseDir);
    DBInterface checkpointDb = null;
    if (cpList.size() > 0) {
      String lastestCp = cpList.get(cpList.size() - 1);
      checkpointDb = DbTool.getDB(
          databaseDir + "/" + DBUtils.CHECKPOINT_DB_V2, lastestCp);
    } else {
      checkpointDb = DbTool.getDB(databaseDir, CHECKPOINT_DB);
    }
    Long blockNumber = getLatestBlockHeaderNumFromCP(checkpointDb,
        latestBlockHeaderNumber.getBytes());
    if (blockNumber != null) {
      return blockNumber;
    }
    // query from propertiesDb if checkpoint not contains latest_block_header_number
    DBInterface propertiesDb = DbTool.getDB(databaseDir, PROPERTIES_DB_NAME);
    return Optional.ofNullable(propertiesDb.get(ByteArray.fromString(latestBlockHeaderNumber)))
            .map(ByteArray::toLong)
            .orElseThrow(
                () -> new IllegalArgumentException("not found latest block header number"));
  }

  private Long getLatestBlockHeaderNumFromCP(DBInterface db, byte[] key) {
    byte[] value = db.get(Bytes.concat(simpleEncode(PROPERTIES_DB_NAME), key));
    if (value != null && value.length > 1) {
      return ByteArray.toLong(Arrays.copyOfRange(value, 1, value.length));
    }
    return null;
  }

  /**
   * recent blocks, trans and genesis block.
   */
  private void fillSnapshotBlockAndTransDb(String sourceDir, String snapshotDir)
          throws IOException, RocksDBException {
    logger.info("Begin to fill {} block, genesis block and trans to snapshot.", RECENT_BLKS);
    spec.commandLine().getOut().format(
        "Begin to fill %d block, genesis block and trans to snapshot.", RECENT_BLKS).println();
    DBInterface sourceBlockIndexDb = DbTool.getDB(sourceDir, BLOCK_INDEX_DB_NAME);
    DBInterface sourceBlockDb = DbTool.getDB(sourceDir, BLOCK_DB_NAME);
    // init snapshot db ,keep engine same as source
    DBInterface destBlockDb = DbTool.getDB(sourceDir, snapshotDir, BLOCK_DB_NAME);
    DBInterface destBlockIndexDb = DbTool.getDB(sourceDir, snapshotDir, BLOCK_INDEX_DB_NAME);
    DBInterface destTransDb = DbTool.getDB(sourceDir, snapshotDir, TRANS_DB_NAME);
    // put genesis block and block-index into snapshot
    long genesisBlockNum = 0L;
    byte[] genesisBlockID = sourceBlockIndexDb.get(ByteArray.fromLong(genesisBlockNum));
    destBlockIndexDb.put(ByteArray.fromLong(genesisBlockNum), genesisBlockID);
    destBlockDb.put(genesisBlockID, sourceBlockDb.get(genesisBlockID));

    long latestBlockNum = getLatestBlockHeaderNum(sourceDir);
    long startIndex = latestBlockNum - RECENT_BLKS + 1;
    // put the recent blocks and trans in snapshot
    ProgressBar.wrap(LongStream.rangeClosed(startIndex, latestBlockNum), "fillBlockAndTrans")
        .forEach(blockNum ->  {
          try {
            byte[] blockId = getDataFromSourceDB(sourceDir, BLOCK_INDEX_DB_NAME,
                Longs.toByteArray(blockNum));
            byte[] block = getDataFromSourceDB(sourceDir, BLOCK_DB_NAME, blockId);
            // put block
            destBlockDb.put(blockId, block);
            // put block index
            destBlockIndexDb.put(ByteArray.fromLong(blockNum), blockId);
            // put trans
            long finalBlockNum = blockNum;
            Protocol.Block.parseFrom(block).getTransactionsList().stream().map(
                tc -> DBUtils.getTransactionId(tc).getBytes())
                .map(bytes -> Maps.immutableEntry(bytes, Longs.toByteArray(finalBlockNum)))
                .forEach(e -> destTransDb.put(e.getKey(), e.getValue()));
          } catch (IOException | RocksDBException e) {
            throw new RuntimeException(e.getMessage());
          }
        });

    DBInterface destCommonDb = DbTool.getDB(snapshotDir, COMMON_DB_NAME);
    destCommonDb.put(DB_KEY_NODE_TYPE, ByteArray.fromInt(DBUtils.NODE_TYPE_LIGHT_NODE));
    destCommonDb.put(DB_KEY_LOWEST_BLOCK_NUM, ByteArray.fromLong(startIndex));
    // copy engine.properties for block、block-index、trans from source if exist
    copyEngineIfExist(sourceDir, snapshotDir, BLOCK_DB_NAME, BLOCK_INDEX_DB_NAME, TRANS_DB_NAME);
  }

  private void copyEngineIfExist(String source, String dest, String... dbNames) {
    for (String dbName : dbNames) {
      Path ori = Paths.get(source, dbName, DBUtils.FILE_ENGINE);
      if (ori.toFile().exists()) {
        FileUtils.copy(ori, Paths.get(dest, dbName, DBUtils.FILE_ENGINE));
      }
    }
  }

  private byte[] getGenesisBlockHash(String parentDir) throws IOException, RocksDBException {
    long genesisBlockNum = 0L;
    DBInterface blockIndexDb = DbTool.getDB(parentDir, BLOCK_INDEX_DB_NAME);
    byte[] result = blockIndexDb.get(ByteArray.fromLong(genesisBlockNum));
    // when merge history, block-index db will be moved to bak dir and replaced by history
    // so should close this db and reopen it.
    DbTool.closeDB(parentDir, BLOCK_INDEX_DB_NAME);
    return result;
  }

  private static byte[] simpleEncode(String s) {
    byte[] bytes = s.getBytes();
    byte[] length = Ints.toByteArray(bytes.length);
    byte[] r = new byte[4 + bytes.length];
    System.arraycopy(length, 0, r, 0, 4);
    System.arraycopy(bytes, 0, r, 4, bytes.length);
    return r;
  }

  private BlockNumInfo checkAndGetBlockNumInfo(String historyDir, String databaseDir)
          throws IOException, RocksDBException {
    logger.info("Check the compatibility of this history.");
    spec.commandLine().getOut().println("Check the compatibility of this history.");
    String snapshotInfo = String.format(
            DIR_FORMAT_STRING, databaseDir, File.separator, INFO_FILE_NAME);
    String historyInfo = String.format(
            DIR_FORMAT_STRING, historyDir, File.separator, INFO_FILE_NAME);
    if (!FileUtils.isExists(snapshotInfo)) {
      throw new FileNotFoundException(
              "Snapshot property file is not found. maybe this is a complete fullnode?");
    }
    if (!FileUtils.isExists(historyInfo)) {
      throw new FileNotFoundException("history property file is not found.");
    }
    long snapshotBlkNum = Long.parseLong(FileUtils.readProperty(snapshotInfo, DBUtils
            .SPLIT_BLOCK_NUM));
    long historyBlkNum = Long.parseLong(FileUtils.readProperty(historyInfo, DBUtils
            .SPLIT_BLOCK_NUM));
    if (historyBlkNum < snapshotBlkNum) {
      throw new RuntimeException(
          String.format(
              "History latest block number is lower than snapshot, history: %d, snapshot: %d",
          historyBlkNum, snapshotBlkNum));
    }
    // check genesis block is equal
    if (!Arrays.equals(getGenesisBlockHash(databaseDir), getGenesisBlockHash(historyDir))) {
      throw new RuntimeException(String.format(
          "Genesis block hash is not equal, history: %s, database: %s",
          Arrays.toString(getGenesisBlockHash(historyDir)),
          Arrays.toString(getGenesisBlockHash(databaseDir))));
    }
    return new BlockNumInfo(snapshotBlkNum, historyBlkNum);
  }

  private void backupArchiveDbs(String databaseDir) throws IOException {
    String bakDir = String.format("%s%s%s%d",
            databaseDir, File.separator, BACKUP_DIR_PREFIX, START_TIME);
    logger.info("Backup the archive dbs to {}.", bakDir);
    spec.commandLine().getOut().format("Backup the archive dbs to %s.", bakDir).println();
    if (!FileUtils.createDirIfNotExists(bakDir)) {
      throw new RuntimeException(String.format("create bak dir %s failed", bakDir));
    }
    FileUtils.copyDatabases(Paths.get(databaseDir), Paths.get(bakDir), archiveDbs);
    archiveDbs.forEach(db -> FileUtils.deleteDir(new File(databaseDir, db)));
  }

  private void copyHistory2Database(String historyDir, String databaseDir) throws IOException {
    logger.info("Begin to copy history to database.");
    spec.commandLine().getOut().println("Begin to copy history to database.");
    FileUtils.copyDatabases(Paths.get(historyDir), Paths.get(databaseDir), archiveDbs);
  }

  private void trimHistory(String databaseDir, BlockNumInfo blockNumInfo)
          throws IOException, RocksDBException {
    logger.info("Begin to trim the history data.");
    spec.commandLine().getOut().println("Begin to trim the history data.");
    DBInterface blockIndexDb = DbTool.getDB(databaseDir, BLOCK_INDEX_DB_NAME);
    DBInterface blockDb = DbTool.getDB(databaseDir, BLOCK_DB_NAME);
    DBInterface transDb = DbTool.getDB(databaseDir, TRANS_DB_NAME);
    DBInterface tranRetDb = DbTool.getDB(databaseDir, TRANSACTION_RET_DB_NAME);
    for (long n = blockNumInfo.getHistoryBlkNum(); n > blockNumInfo.getSnapshotBlkNum(); n--) {
      byte[] blockIdHash = blockIndexDb.get(ByteArray.fromLong(n));
      Protocol.Block block = Protocol.Block.parseFrom(blockDb.get(blockIdHash));
      // delete transactions
      for (Protocol.Transaction e : block.getTransactionsList()) {
        transDb.delete(DBUtils.getTransactionId(e).getBytes());
      }
      // delete transaction result
      tranRetDb.delete(ByteArray.fromLong(n));
      // delete block
      blockDb.delete(blockIdHash);
      // delete block index
      blockIndexDb.delete(ByteArray.fromLong(n));
    }
  }

  private void mergeBak2Database(String databaseDir) throws IOException, RocksDBException {
    String bakDir = String.format("%s%s%s%d",
            databaseDir, File.separator, BACKUP_DIR_PREFIX, START_TIME);
    logger.info("Begin to merge {} to database.", bakDir);
    spec.commandLine().getOut().format("Begin to merge %s to database.", bakDir).println();
    for (String dbName : archiveDbs) {
      DBInterface bakDb = DbTool.getDB(bakDir, dbName);
      DBInterface destDb = DbTool.getDB(databaseDir, dbName);
      try (DBIterator iterator = bakDb.iterator()) {
        for (iterator.seekToFirst(); iterator.hasNext(); iterator.next()) {
          destDb.put(iterator.getKey(), iterator.getValue());
        }
      }
    }
  }

  private byte[] getDataFromSourceDB(String sourceDir, String dbName, byte[] key)
          throws IOException, RocksDBException {
    DBInterface sourceDb = DbTool.getDB(sourceDir, dbName);
    DBInterface checkpointDb = DbTool.getDB(sourceDir, CHECKPOINT_DB);
    // get data from tmp first.
    byte[] valueFromTmp = checkpointDb.get(Bytes.concat(simpleEncode(dbName), key));
    byte[] value;
    if (isEmptyBytes(valueFromTmp)) {
      value = sourceDb.get(key);
    } else {
      value = valueFromTmp.length == 1
          ? null : Arrays.copyOfRange(valueFromTmp, 1, valueFromTmp.length);
    }
    if (isEmptyBytes(value)) {
      throw new RuntimeException(String.format("data not found in store, dbName: %s, key: %s",
              dbName, Arrays.toString(key)));
    }
    return value;
  }

  /**
   * return true if byte array is null or length is 0.
   * @param b bytes
   * @return true or false
   */
  private static boolean isEmptyBytes(byte[] b) {
    if (b != null) {
      return b.length == 0;
    }
    return true;
  }

  private void deleteSnapshotFlag(String databaseDir) throws IOException, RocksDBException {
    logger.info("Delete the info file from {}.", databaseDir);
    spec.commandLine().getOut().format("Delete the info file from %s.", databaseDir).println();
    Files.delete(Paths.get(databaseDir, INFO_FILE_NAME));
    if (!isLite(databaseDir)) {
      DBInterface destCommonDb = DbTool.getDB(databaseDir, COMMON_DB_NAME);
      destCommonDb.delete(DB_KEY_NODE_TYPE);
      destCommonDb.delete(DB_KEY_LOWEST_BLOCK_NUM);
      logger.info("Deleted {} and {} from {} to identify this node is a real fullnode.",
          "node_type", "lowest_block_num", COMMON_DB_NAME);
      spec.commandLine().getOut().format(
          "Deleted %s and %s from %s to identify this node is a real fullnode.",
          "node_type", "lowest_block_num", COMMON_DB_NAME).println();
    }

  }

  private void hasEnoughBlock(String sourceDir) throws RocksDBException, IOException {
    // check latest
    long latest = getLatestBlockHeaderNum(sourceDir);
    // check second ,skip 0;
    long second = getSecondBlock(sourceDir);
    if (latest - second + 1 < RECENT_BLKS) {
      throw new NoSuchElementException(
          String.format("At least %d blocks in block store, actual latestBlock:%d, firstBlock:%d.",
          RECENT_BLKS, latest, second));
    }
  }

  private boolean isLite(String databaseDir) throws RocksDBException, IOException {
    return getSecondBlock(databaseDir) > 1;
  }

  private long getSecondBlock(String databaseDir) throws RocksDBException, IOException {
    long num = 0;
    DBInterface sourceBlockIndexDb = DbTool.getDB(databaseDir, BLOCK_INDEX_DB_NAME);
    DBIterator iterator = sourceBlockIndexDb.iterator();
    iterator.seek(ByteArray.fromLong(1));
    if (iterator.hasNext()) {
      num =  Longs.fromByteArray(iterator.getKey());
    }
    return num;
  }

  @VisibleForTesting
  public static void setRecentBlks(long recentBlks) {
    RECENT_BLKS = recentBlks;
  }

  @VisibleForTesting
  public static void reSetRecentBlks() {
    RECENT_BLKS = 65536;
  }

  private List<String> getCheckpointV2List(String sourceDir) {
    File file = new File(Paths.get(sourceDir, DBUtils.CHECKPOINT_DB_V2).toString());
    if (file.exists() && file.isDirectory() && file.list() != null) {
      return Arrays.stream(Objects.requireNonNull(file.list())).sorted()
          .collect(Collectors.toList());
    }
    return Lists.newArrayList();
  }

  static class BlockNumInfo {
    private final long snapshotBlkNum;
    private final long historyBlkNum;

    public BlockNumInfo(long snapshotBlkNum, long historyBlkNum) {
      this.snapshotBlkNum = snapshotBlkNum;
      this.historyBlkNum = historyBlkNum;
    }

    public long getSnapshotBlkNum() {
      return snapshotBlkNum;
    }

    public long getHistoryBlkNum() {
      return historyBlkNum;
    }
  }
}




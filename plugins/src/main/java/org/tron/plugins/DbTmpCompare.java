package org.tron.plugins;

import com.google.common.primitives.Ints;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.hyperledger.besu.ethereum.trie.MerklePatriciaTrie;
import org.hyperledger.besu.ethereum.trie.SimpleMerklePatriciaTrie;
import org.rocksdb.RocksDBException;
import org.tron.plugins.utils.DBUtils;
import org.tron.plugins.utils.db.DBInterface;
import org.tron.plugins.utils.db.DBIterator;
import org.tron.plugins.utils.db.DbTool;
import picocli.CommandLine;

@Slf4j(topic = "tmp-compare")
@CommandLine.Command(name = "tmp-compare",
    description = "compare data between two path for tmp.",
    exitCodeListHeading = "Exit Codes:%n",
    exitCodeList = {
        "0:Successful",
        "n:compare diff find,please check toolkit.log"})
public class DbTmpCompare implements Callable<Integer> {

  @CommandLine.Spec
  CommandLine.Model.CommandSpec spec;
  @CommandLine.Parameters(index = "0",
      description = " input path for base")
  private File base;
  @CommandLine.Parameters(index = "1",
      description = "input path for compare")
  private File compare;
  @CommandLine.Option(names = {"-h", "--help"}, help = true, description = "display a help message")
  private boolean help;


  @Override
  public Integer call() throws Exception {
    if (help) {
      spec.commandLine().usage(System.out);
      return 0;
    }
    if (!base.exists()) {
      logger.info(" {} does not exist.", base);
      spec.commandLine().getErr().println(spec.commandLine().getColorScheme()
          .errorText(String.format("%s does not exist.", base)));
      return 404;
    }
    if (!compare.exists()) {
      logger.info(" {} does not exist.", compare);
      spec.commandLine().getErr().println(spec.commandLine().getColorScheme()
          .errorText(String.format("%s does not exist.", compare)));
      return 404;
    }

    Comparison service = new DbComparison(base.toPath(), compare.toPath());
    return service.doCompare() ? 0 : 1;

  }

  interface Comparison {
    boolean doCompare() throws Exception;
  }

  static class DbComparison implements Comparison {

    private final Path basePath;
    private final Path dstPath;
    private final String dbName = "tmp";

    private String baseAccRoot = org.apache.tuweni.bytes.Bytes32.ZERO.toHexString();
    private String baseRowRoot = org.apache.tuweni.bytes.Bytes32.ZERO.toHexString();
    private String dstAccRoot = org.apache.tuweni.bytes.Bytes32.ZERO.toHexString();
    private String dstRowRoot = org.apache.tuweni.bytes.Bytes32.ZERO.toHexString();

    public DbComparison(Path srcDir, Path dstDir) {
      this.basePath = srcDir;
      this.dstPath = dstDir;
    }

    @Override
    public boolean doCompare() throws Exception {
      return compare();
    }



    private boolean compare() throws RocksDBException, IOException {
      try (
          DBInterface base  = DbTool.getDB(this.basePath, this.dbName);
          DBIterator baseIterator = base.iterator();
          DBInterface dst  = DbTool.getDB(this.dstPath, this.dbName);
          DBIterator dstIterator = dst.iterator()) {

        // check
        logger.info("compare database {} start", this.dbName);

        CompletableFuture<Void> dest = CompletableFuture.runAsync(() -> {
          final MerklePatriciaTrie<org.apache.tuweni.bytes.Bytes, org.apache.tuweni.bytes.Bytes>
              acc = new SimpleMerklePatriciaTrie<>(Function.identity());
          final MerklePatriciaTrie<org.apache.tuweni.bytes.Bytes, org.apache.tuweni.bytes.Bytes>
              row = new SimpleMerklePatriciaTrie<>(Function.identity());
          for (dstIterator.seekToFirst(); dstIterator.hasNext(); dstIterator.next()) {
            byte[] key = dstIterator.getKey();
            byte[] value = dstIterator.getValue();
            String dbName = DBUtils.simpleDecode(key);
            if ("account".equalsIgnoreCase(dbName)) {
              acc.put(org.apache.tuweni.bytes.Bytes.wrap(key),
                  org.apache.tuweni.bytes.Bytes.wrap(value));
            }
            if ("storage-row".equalsIgnoreCase(dbName)) {
              row.put(org.apache.tuweni.bytes.Bytes.wrap(key),
                  org.apache.tuweni.bytes.Bytes.wrap(value));
            }

          }
          dstAccRoot = acc.getRootHash().toHexString();
          dstRowRoot = row.getRootHash().toHexString();
          logger.info("dst :{}, acc root: {}, row root: {}", dstPath, dstAccRoot, dstRowRoot);
        });

        CompletableFuture<Void> source = CompletableFuture.runAsync(() -> {
          final MerklePatriciaTrie<org.apache.tuweni.bytes.Bytes, org.apache.tuweni.bytes.Bytes>
              acc = new SimpleMerklePatriciaTrie<>(Function.identity());
          final MerklePatriciaTrie<org.apache.tuweni.bytes.Bytes, org.apache.tuweni.bytes.Bytes>
              row = new SimpleMerklePatriciaTrie<>(Function.identity());
          for (baseIterator.seekToFirst(); baseIterator.hasNext(); baseIterator.next()) {
            byte[] key = baseIterator.getKey();
            byte[] value = baseIterator.getValue();
            String dbName = DBUtils.simpleDecode(key);
            if ("account".equalsIgnoreCase(dbName)) {
              acc.put(org.apache.tuweni.bytes.Bytes.wrap(key),
                  org.apache.tuweni.bytes.Bytes.wrap(value));
            }
            if ("storage-row".equalsIgnoreCase(dbName)) {
              row.put(org.apache.tuweni.bytes.Bytes.wrap(key),
                  org.apache.tuweni.bytes.Bytes.wrap(value));
            }
          }
          baseAccRoot = acc.getRootHash().toHexString();
          baseRowRoot = row.getRootHash().toHexString();
          logger.info("base :{}, acc root: {}, row root: {}", basePath, baseAccRoot, baseRowRoot);
        });
        CompletableFuture<Void> ret = CompletableFuture.allOf(dest, source);
        ret.whenComplete((t, action) -> logger.info(
            "Check database {} end,baseAccRoot {}, dstAccRoot {}, baseRowRoot: {},dstRowRoot: {}",
            dbName, baseAccRoot, dstAccRoot,
            baseRowRoot, dstRowRoot));
        ret.join();
        return baseAccRoot.equals(dstAccRoot) && baseRowRoot.equals(dstRowRoot);
      }
    }

    private byte[] simpleEncode(String s) {
      byte[] bytes = s.getBytes();
      byte[] length = Ints.toByteArray(bytes.length);
      byte[] r = new byte[4 + bytes.length];
      System.arraycopy(length, 0, r, 0, 4);
      System.arraycopy(bytes, 0, r, 4, bytes.length);
      return r;
    }
  }
}

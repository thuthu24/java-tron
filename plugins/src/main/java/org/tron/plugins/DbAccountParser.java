package org.tron.plugins;

import com.google.common.base.Objects;
import com.google.protobuf.InvalidProtocolBufferException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.tron.plugins.utils.ByteArray;
import org.tron.plugins.utils.StringUtil;
import org.tron.protos.Protocol;
import picocli.CommandLine;


@Slf4j(topic = "account-parser")
@CommandLine.Command(name = "account-parser",
    description = "parse account from file.",
    exitCodeListHeading = "Exit Codes:%n",
    exitCodeList = {
        "0:Successful",
        "n:query failed,please check toolkit.log"})
public class DbAccountParser implements Callable<Integer> {

  @CommandLine.Spec
  CommandLine.Model.CommandSpec spec;
  @CommandLine.Parameters(index = "0",
      description = " db path for account")
  private Path multiAccount;
  @CommandLine.Option(names = {"-h", "--help"}, help = true, description = "display a help message")
  private boolean help;


  @Override
  public Integer call() throws Exception {
    if (help) {
      spec.commandLine().usage(System.out);
      return 0;
    }
    if (!multiAccount.toFile().exists()) {
      logger.info(" {} does not exist.", multiAccount);
      spec.commandLine().getErr().println(spec.commandLine().getColorScheme()
          .errorText(String.format("%s does not exist.", multiAccount)));
      return 404;
    }
    return parse();
  }


  private int parse() throws IOException {
    try (Stream<String> stream = Files.lines(multiAccount)) {
      stream.forEach(this::print);
    }
    return 0;
  }

  private  MultiAccount print(String v) {
    byte[] value = ByteArray.fromHexString(v);

    try {
      Protocol.Account account = Protocol.Account.parseFrom(value);

      Protocol.Permission ownerP = account.getOwnerPermission();
      Permission own = new Permission(ownerP.getThreshold(), ownerP.getKeysCount(),
              ownerP.getKeysList().stream().collect(Collectors
                      .toMap(k -> StringUtil.encode58Check(k.getAddress().toByteArray()),
                              Protocol.Key::getWeight)));

      List<Permission> active = account.getActivePermissionList().stream().map(
          p -> new Permission(p.getThreshold(), p.getKeysCount(),
              p.getKeysList().stream().collect(
                      Collectors.toMap(k -> StringUtil.encode58Check(k.getAddress().toByteArray()),
                              Protocol.Key::getWeight)))).collect(Collectors.toList());

      MultiAccount  multiAccount = new MultiAccount(
              StringUtil.encode58Check(account.getAddress().toByteArray()), account.getBalance(),
              own, active);
      if (ownerP.getKeysCount() <= 1 && active.stream().allMatch(p -> p.size <= 1)) {
        return null;
      }
      if (own.bad() || active.stream().anyMatch(Permission::bad)) {
        logger.info("{}", multiAccount);
      }

      return multiAccount;
    } catch (InvalidProtocolBufferException e) {
      logger.error("{}", e);
    }

    return null;
  }


  public static class MultiAccount implements Comparable<MultiAccount> {

    private final String address;
    private final long balance;

    final Permission ownAddress;
    final List<Permission> activeAddress;

    public MultiAccount(String address, long balance, Permission ownAddress,
                        List<Permission> activeAddress) {
      this.address = address;
      this.balance = balance;
      this.ownAddress = ownAddress;
      this.activeAddress = activeAddress;
    }


    @Override
    public int compareTo(@NonNull MultiAccount o) {
      int ret = Long.compare(this.balance, o.balance);
      if (ret != 0) {
        return ret;
      }
      return  Integer.compare(this.ownAddress.size, o.ownAddress.size);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      MultiAccount that = (MultiAccount) o;
      return Objects.equal(address, that.address);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(address);
    }

    @Override
    public String toString() {
      return address + '\t' + balance + '\t'
              + ownAddress  + '\t' + activeAddress;
    }
  }

  public static class Permission {
    private final long threshold;
    private final int size;
    private final Map<String, Long>  weights;

    public Permission(long threshold, int size, Map<String, Long> weights) {
      this.threshold = threshold;
      this.size = size;
      this.weights = weights;
    }

    public boolean bad() {
      if (this.weights.values().stream().anyMatch(w -> w >= threshold)) {
        return false;
      }
      return this.weights.values().stream().anyMatch(w -> w * 4 >= threshold);
    }

    @Override
    public String toString() {
      return "{"
              + "threshold=" + threshold
              + ", size=" + size
              + ", weights=" + weights
              + '}';
    }
  }
}

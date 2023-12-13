package org.tron.plugins;

import com.google.common.base.Charsets;
import com.google.common.io.Files;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;

import lombok.extern.slf4j.Slf4j;
import me.tongfei.progressbar.ProgressBar;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jetbrains.annotations.NotNull;
import picocli.CommandLine;

@Slf4j(topic = "reward-check")
@CommandLine.Command(name = "reward-check",
    description = "reward check .",
    exitCodeListHeading = "Exit Codes:%n",
    exitCodeList = {
        "0:Successful",
        "n:query failed,please check toolkit.log"})
public class DbRewardCheck implements Callable<Integer> {

  @CommandLine.Spec
  CommandLine.Model.CommandSpec spec;
  @CommandLine.Parameters(index = "0",
      description = " file for account")
  private Path file;

  @CommandLine.Option(names = {"-h", "--help"}, help = true, description = "display a help message")
  private boolean help;

  private static  final  String BASE_URL = "https://api.trongrid.io/wallet/getReward";
  private static final String EXP_URL = "http://tron.api.halibobo.cn/wallet/getReward";
  private static final OkHttpClient client = new OkHttpClient.Builder()
      .connectTimeout(1, TimeUnit.MINUTES)
      .readTimeout(1, TimeUnit.MINUTES)
      .build();
  private static final ExecutorService executorService = Executors.newFixedThreadPool(256);


  @Override
  public Integer call() throws Exception {
    if (help) {
      spec.commandLine().usage(System.out);
      return 0;
    }
    if (!file.toFile().exists()) {
      logger.info(" {} does not exist.", file);
      spec.commandLine().getErr().println(spec.commandLine().getColorScheme()
          .errorText(String.format("%s does not exist.", file)));
      return 404;
    }
    return checkAccount();
  }

  private int checkAccount() throws IOException {
    List<Future<Boolean>> futures = new ArrayList<>();
    Files.readLines(file.toFile(), Charsets.UTF_8)
        .forEach(acc -> futures.add(executorService.submit(() -> processLine(acc))));
    ProgressBar.wrap(futures, "check account")
        .forEach(future -> {
          try {
            future.get();
          } catch (InterruptedException | ExecutionException e) {
            logger.error("{}", e);
          }
        });
    return 0;
  }


  private boolean processLine(@NotNull String line) {
    String address = line.trim().split(",")[0];
    HttpUrl.Builder baseUrlBuilder
        = Objects.requireNonNull(HttpUrl.parse(BASE_URL)).newBuilder();
    baseUrlBuilder.addQueryParameter("address", address);
    HttpUrl.Builder expUrlBuilder
        = Objects.requireNonNull(HttpUrl.parse(EXP_URL)).newBuilder();
    expUrlBuilder.addQueryParameter("address", address);

    CompletableFuture<String> base = CompletableFuture.supplyAsync(() -> {
      try {
        Response response = client.newCall(new Request.Builder().url(baseUrlBuilder.build())
            .build()).execute();
        assert response.body() != null;
        return response.body().string();
      } catch (IOException e) {
        logger.error("{}", e);
        return null;
      }
    });
    CompletableFuture<String> exp = CompletableFuture.supplyAsync(() -> {
      try {
        Response response = client.newCall(new Request.Builder().url(expUrlBuilder.build())
            .build()).execute();
        assert response.body() != null;
        return response.body().string();
      } catch (IOException e) {
        logger.error("{}", e);
        return null;
      }
    });
    CompletableFuture.allOf(base, exp).join();
    try {
      String baseResult = base.get();
      String expResult = exp.get();
      if (! Objects.equals(baseResult, expResult)) {
        logger.info("acc: {},base: {}, exp: {}", address, baseResult, expResult);
        spec.commandLine().getOut().println(String.format("%s,%s,%s",
            address, baseResult, expResult));
      }

    } catch (InterruptedException | ExecutionException e) {
      logger.error("{}", e);
    }
    return true;
  }
}

package org.tron.plugins;

import org.junit.Assert;
import org.junit.Test;
import picocli.CommandLine;

public class DbRewardCheckTest {

  CommandLine cli = new CommandLine(new Toolkit());

  @Test
  public void testRun() {
    String[] args = new String[] { "db", "reward-check",  "/Users/lizibo/tron/db/exp/reward.log"};
    Assert.assertEquals(0, cli.execute(args));
  }

}

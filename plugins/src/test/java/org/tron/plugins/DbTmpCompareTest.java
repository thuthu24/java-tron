package org.tron.plugins;

import org.junit.Assert;
import org.junit.Test;
import picocli.CommandLine;

public class DbTmpCompareTest{

  CommandLine cli = new CommandLine(new Toolkit());

  @Test
  public void testRun() {
    String[] args = new String[] { "db", "tmp-compare",  "/Users/lizibo/tron/tmp/472",
        "/Users/lizibo/tron/tmp/473"};
    Assert.assertEquals(0, cli.execute(args));
  }

}

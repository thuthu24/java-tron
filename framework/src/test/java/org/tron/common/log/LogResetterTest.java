package org.tron.common.log;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;

public class LogResetterTest {

  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Rule
  public ExpectedException thrown = ExpectedException.none();


  @Test
  public void testDefault() {
    LogResetter.load("");
    LogResetter.load(null);
  }

  @Test
  public void testNotFound() {
    thrown.expect(IllegalArgumentException.class);
    File file = Paths.get(temporaryFolder.getRoot().getAbsolutePath(), "logback-not.xml").toFile();
    LogResetter.load(file.getAbsolutePath());
  }

  @Test
  public void testIllegalFile() throws IOException {
    thrown.expect(IllegalStateException.class);
    File file = temporaryFolder.newFile("logback.xml");
    LogResetter.load(file.getAbsolutePath());
  }
}

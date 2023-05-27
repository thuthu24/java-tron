package org.tron.plugins;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import org.junit.AssumptionViolatedException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class TempFolderTest {

  @Rule
  public TemporaryFolder folder = new TemporaryFolder();

  @Test
  public void tempFolderIsOnlyAccessibleByOwner() throws IOException {
    TemporaryFolder folder = new TemporaryFolder();
    folder.create();
    Set<String> expectedPermissions = new TreeSet<>(Arrays.asList(
        "OWNER_READ", "OWNER_WRITE", "OWNER_EXECUTE"));
    Set<String> actualPermissions = getPosixFilePermissions(folder.getRoot());
    assertEquals(expectedPermissions, actualPermissions);
  }

  private Set<String> getPosixFilePermissions(File root) {
    try {
      Class<?> pathClass = Class.forName("java.nio.file.Path");
      Object linkOptionArray = Array.newInstance(Class.forName("java.nio.file.LinkOption"), 0);
      Class<?> filesClass = Class.forName("java.nio.file.Files");
      Object path = File.class.getDeclaredMethod("toPath").invoke(root);
      Method posixFilePermissionsMethod = filesClass.getDeclaredMethod(
          "getPosixFilePermissions", pathClass, linkOptionArray.getClass());
      Set<?> permissions = (Set<?>) posixFilePermissionsMethod.invoke(null,
          path, linkOptionArray);
      SortedSet<String> convertedPermissions = new TreeSet<String>();
      for (Object item : permissions) {
        convertedPermissions.add(item.toString());
      }
      return convertedPermissions;
    } catch (Exception e) {
      throw new AssumptionViolatedException("Test requires at least Java 1.7", e);
    }
  }
}

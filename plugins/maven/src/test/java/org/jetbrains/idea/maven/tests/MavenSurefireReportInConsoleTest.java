// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.idea.maven.tests;

import com.intellij.execution.filters.Filter;
import com.intellij.testFramework.fixtures.LightCodeInsightFixtureTestCase;
import org.jetbrains.idea.maven.project.MavenTestConsoleFilter;
import org.jetbrains.idea.maven.server.MavenServerManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * @author Sergey Evdokimov
 */
public class MavenSurefireReportInConsoleTest extends LightCodeInsightFixtureTestCase {

  private Filter myFilter;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    myFilter = new MavenTestConsoleFilter();
  }

  @Override
  protected void tearDown() throws Exception {
    try {
      MavenServerManager.getInstance().shutdown(true);
    }
    catch (Throwable e) {
      addSuppressedException(e);
    }
    finally {
      super.tearDown();
    }
  }

  private List<String> passLine(String line) {
    if (!line.endsWith("\n")) {
      line += '\n';
    }

    Filter.Result result = myFilter.applyFilter(line, line.length());
    if (result == null) return Collections.emptyList();

    List<String> res = new ArrayList<>();

    for (Filter.ResultItem item : result.getResultItems()) {
      res.add(line.substring(item.getHighlightStartOffset(), item.getHighlightEndOffset()));
    }

    return res;
  }

  public void testSurefire2_14() {
    myFixture.addClass("public class CccTest {\n" +
                       "  public void testTtt() {}\n" +
                       "  public void testTtt2() {}\n" +
                       "}");

    String tempDirPath = myFixture.getTempDirPath();

    assertEquals(passLine("[INFO] Scanning for projects..."), Collections.emptyList());
    assertEquals(passLine("[INFO] Surefire report directory: " + tempDirPath), Collections.singletonList(tempDirPath));
    assertEquals(passLine("[ERROR] Please refer to " + tempDirPath + " for the individual test results."), Collections.singletonList(
      tempDirPath));
  }
}

// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.vfs;

import com.intellij.concurrency.JobLauncher;
import com.intellij.concurrency.JobSchedulerImpl;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.FrequentEventDetector;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.io.IoTestUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.ex.temp.TempFileSystem;
import com.intellij.openapi.vfs.newvfs.ManagingFS;
import com.intellij.openapi.vfs.newvfs.NewVirtualFile;
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.openapi.vfs.newvfs.impl.VirtualDirectoryImpl;
import com.intellij.openapi.vfs.newvfs.persistent.PersistentFS;
import com.intellij.testFramework.*;
import com.intellij.testFramework.fixtures.BareTestFixtureTestCase;
import com.intellij.testFramework.fixtures.impl.LightTempDirTestFixtureImpl;
import com.intellij.testFramework.rules.TempDirectory;
import com.intellij.util.ExceptionUtil;
import com.intellij.util.ThrowableRunnable;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.ui.UIUtil;
import gnu.trove.TIntHashSet;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

@RunFirst
@SkipSlowTestLocally
public class VfsUtilPerformanceTest extends BareTestFixtureTestCase {
  @Rule public TempDirectory myTempDir = new TempDirectory();

  @Test
  public void testFindChildByNamePerformance() throws IOException {
    File tempDir = myTempDir.newFolder();
    VirtualFile vDir = LocalFileSystem.getInstance().findFileByIoFile(tempDir);
    assertNotNull(vDir);
    assertTrue(vDir.isDirectory());

    WriteCommandAction.writeCommandAction(null).run(() -> {
      for (int i = 0; i < 10_000; i++) {
        String name = i + ".txt";
        vDir.createChildData(vDir, name);
      }
    });

    VirtualFile theChild = vDir.findChild("5111.txt");
    assertNotNull(theChild);
    UIUtil.pump(); // wait for all event handlers to calm down

    LOG.debug("Start searching...");
    PlatformTestUtil.startPerformanceTest("finding child", 1500, () -> {
      for (int i = 0; i < 1_000_000; i++) {
        VirtualFile child = vDir.findChild("5111.txt");
        assertEquals(theChild, child);
      }
    }).assertTiming();

    WriteCommandAction.writeCommandAction(null).run(() -> {
      for (VirtualFile file : vDir.getChildren()) {
        file.delete(this);
      }
    });
  }

  @Test
  public void testFindRootPerformance() throws IOException {
    File tempJar = IoTestUtil.createTestJar(myTempDir.newFile("test.jar"));
    VirtualFile jar = LocalFileSystem.getInstance().findFileByIoFile(tempJar);
    assertNotNull(jar);

    JarFileSystem fs = JarFileSystem.getInstance();
    String path = jar.getPath() + "!/";
    NewVirtualFile root = ManagingFS.getInstance().findRoot(path, fs);
    PlatformTestUtil.startPerformanceTest("finding root", 10_000,
        () -> JobLauncher.getInstance().invokeConcurrentlyUnderProgress(
        Collections.nCopies(500, null), null,
        __ -> {
          for (int i = 0; i < 20_000; i++) {
            NewVirtualFile rootJar = ManagingFS.getInstance().findRoot(path, fs);
            assertNotNull(rootJar);
            assertSame(root, rootJar);
          }
          return true;
        })).assertTiming();
  }

  @Test
  public void testGetParentPerformance() throws IOException {
    File tempDir = myTempDir.newFolder();
    VirtualFile vDir = LocalFileSystem.getInstance().findFileByIoFile(tempDir);
    assertNotNull(vDir);
    assertTrue(vDir.isDirectory());
    int depth = 10;
    WriteCommandAction.writeCommandAction(null).run(() -> {
      VirtualFile dir = vDir;
      for (int i = 0; i < depth; i++) {
        dir = dir.createChildDirectory(this, "foo");
      }
      VirtualFile leafDir = dir;
      ThrowableRunnable checkPerformance = new ThrowableRunnable() {
        private VirtualFile findRoot(VirtualFile file) {
          while (true) {
            VirtualFile parent = file.getParent();
            if (parent == null) {
              return file;
            }
            file = parent;
          }
        }

        @Override
        public void run() {
          for (int i = 0; i < 5_000_000; i++) {
            checkRootsEqual();
          }
        }

        private void checkRootsEqual() {
          assertEquals(findRoot(vDir), findRoot(leafDir));
        }
      };
      int time = 1500;
      PlatformTestUtil.startPerformanceTest("getParent before movement", time, checkPerformance).assertTiming();
      VirtualFile dir1 = vDir.createChildDirectory(this, "dir1");
      VirtualFile dir2 = vDir.createChildDirectory(this, "dir2");
      for (int i = 0; i < 13; i++) {  /*13 is max length with THashMap capacity of 17, we get plenty collisions then*/
        dir1.createChildData(this, "a" + i + ".txt").move(this, dir2);
      }
      PlatformTestUtil.startPerformanceTest("getParent after movement", time, checkPerformance).assertTiming();
    });
  }

  @Test
  public void testGetPathPerformance() throws Exception {
    LightTempDirTestFixtureImpl fixture = new LightTempDirTestFixtureImpl();
    fixture.setUp();
    Disposer.register(getTestRootDisposable(), () -> {
      try {
        fixture.tearDown();
      }
      catch (Exception e) {
        ExceptionUtil.rethrowAllAsUnchecked(e);
      }
    });

    EdtTestUtil.runInEdtAndWait(() -> {
      String path = "unitTest_testGetPathPerformance_6542623412414351229/" +
                    "junit6921058097194294088/" +
                    StringUtil.repeat("xxx/", 50) +
                    "fff.txt";
      VirtualFile file = fixture.findOrCreateDir(path);

      PlatformTestUtil.startPerformanceTest("VF.getPath()", 10_000, () -> {
        for (int i = 0; i < 1_000_000; ++i) {
          file.getPath();
        }
      }).assertTiming();
    });
  }

  @Test
  public void testAsyncRefresh() throws Throwable {
    Ref<Throwable> ex = Ref.create();
    boolean success = JobLauncher.getInstance().invokeConcurrentlyUnderProgress(
      Arrays.asList(new Object[JobSchedulerImpl.getJobPoolParallelism()]), ProgressManager.getInstance().getProgressIndicator(),
      o -> {
        try {
          doAsyncRefreshTest();
        }
        catch (Throwable t) {
          ex.set(t);
        }
        return true;
      });

    if (!ex.isNull()) throw ex.get();
    if (!success) fail("!success");
  }

  private void doAsyncRefreshTest() throws Exception {
    int N = 1_000;
    byte[] data = "xxx".getBytes(StandardCharsets.UTF_8);

    File temp = myTempDir.newFolder();
    LocalFileSystem fs = LocalFileSystem.getInstance();
    VirtualFile vTemp = fs.findFileByIoFile(temp);
    assertNotNull(vTemp);

    VirtualFile[] children = new VirtualFile[N];
    long[] timestamp = new long[N];

    for (int i = 0; i < N; i++) {
      File file = new File(temp, i + ".txt");
      FileUtil.writeToFile(file, data);
      VirtualFile child = fs.refreshAndFindFileByIoFile(file);
      assertNotNull(child);
      children[i] = child;
      timestamp[i] = file.lastModified();
    }

    vTemp.refresh(false, true);

    for (int i = 0; i < N; i++) {
      File file = new File(temp, i + ".txt");
      assertEquals(timestamp[i], file.lastModified());
      VirtualFile child = fs.findFileByIoFile(file);
      assertNotNull(child);
      IoTestUtil.assertTimestampsEqual(timestamp[i], child.getTimeStamp());
    }

    for (int i = 0; i < N; i++) {
      File file = new File(temp, i + ".txt");
      FileUtil.writeToFile(file, data);
      assertTrue(file.setLastModified(timestamp[i] - 2_000));
      long modified = file.lastModified();
      assertTrue("File:" + file.getPath() + "; time:" + modified, timestamp[i] != modified);
      timestamp[i] = modified;
      IoTestUtil.assertTimestampsNotEqual(children[i].getTimeStamp(), modified);
    }

    Disposable refreshEngaged = Disposer.newDisposable();
    CountDownLatch latch;
    try {
      FrequentEventDetector.disableUntil(refreshEngaged);
      latch = new CountDownLatch(N);
      for (VirtualFile child : children) {
        child.refresh(true, true, latch::countDown);
      }
    }
    finally {
      Disposer.dispose(refreshEngaged);
    }
    while (latch.getCount() > 0) {
      latch.await(100, TimeUnit.MILLISECONDS);
      UIUtil.pump();
    }

    for (int i = 0; i < N; i++) {
      VirtualFile child = children[i];
      IoTestUtil.assertTimestampsEqual(timestamp[i], child.getTimeStamp());
    }
  }

  @Test
  public void PersistentFS_performance_ofManyFilesCreateDelete() {
    int N = 30_000;
    List<VFileEvent> events = new ArrayList<>(N);
    VirtualDirectoryImpl temp = createTempFsDirectory();

    UIUtil.invokeAndWaitIfNeeded((Runnable)() -> {
      PlatformTestUtil.startPerformanceTest("many files creations", 3_000, () -> {
        assertEquals(N, events.size());
        processEvents(events);
        assertEquals(N, temp.getCachedChildren().size());
      })
      .setup(() -> {
        eventsForDeleting(events, temp);
        if (!events.isEmpty()) {
          processEvents(events);
        }
        eventsForCreating(events, N, temp);
        assertEquals(N, TempFileSystem.getInstance().list(temp).length); // do not call getChildren which caches everything
      })
      .assertTiming();

      PlatformTestUtil.startPerformanceTest("many files deletions", 3_300, () -> {
        assertEquals(N, events.size());
        processEvents(events);
        assertEquals(0, temp.getCachedChildren().size());
      })
      .setup(() -> {
        if (temp.getCachedChildren().size() != N) {
          eventsForDeleting(events, temp);
          if (!events.isEmpty()) {
            processEvents(events);
          }
          eventsForCreating(events, N, temp);
          processEvents(events);
        }
        eventsForDeleting(events, temp);
        assertEquals(N, TempFileSystem.getInstance().list(temp).length); // do not call getChildren which caches everything
      })
      .assertTiming();
      }
    );
  }

  private VirtualDirectoryImpl createTempFsDirectory() {
    VirtualFile root = TempFileSystem.getInstance().findFileByPath("/");
    VirtualDirectoryImpl temp = (VirtualDirectoryImpl)VfsTestUtil.createDir(root, "temp");
    Disposer.register(getTestRootDisposable(), () -> VfsTestUtil.deleteFile(temp));
    return temp;
  }

  private static void processEvents(List<? extends VFileEvent> events) {
    WriteCommandAction.runWriteCommandAction(null, () -> PersistentFS.getInstance().processEvents(events));
  }

  private void eventsForCreating(List<? super VFileEvent> events, int N, VirtualDirectoryImpl temp) {
    events.clear();
    TempFileSystem fs = TempFileSystem.getInstance();
    IntStream.range(0, N)
      .mapToObj(i -> new VFileCreateEvent(this, temp, i + ".txt", false, null, null, false, null))
      .peek(event -> {
        if (fs.findModelChild(temp, event.getChildName()) == null) {
          fs.createChildFile(this, temp, event.getChildName());
        }
      })
      .forEach(events::add);
    List<CharSequence> names = ContainerUtil.map(events, e -> ((VFileCreateEvent)e).getChildName());
    temp.removeChildren(new TIntHashSet(), names);
  }

  private void eventsForDeleting(List<? super VFileEvent> events, VirtualDirectoryImpl temp) {
    events.clear();
    temp.getCachedChildren().stream()
      .map(v->new VFileDeleteEvent(this, v, false))
      .forEach(events::add);
  }

}

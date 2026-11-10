/*
 * Copyright 2020 by OLTPBenchmark Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.resbench;

import com.resbench.types.State;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BenchmarkState {

  private static final Logger LOG = LoggerFactory.getLogger(BenchmarkState.class);

  private final long testStartNs;
  private final CountDownLatch startBarrier;
  private final AtomicInteger notDoneCount;
  private volatile State state = State.WARMUP;

  // 所有commit的数据都换成在内存变量DATA_PERSISTENCE中，这些数据不会因为数据库故障而被影响
  public static final ConcurrentHashMap<String, Long> DATA_PERSISTENCE =
      new ConcurrentHashMap<>();
  public static final ConcurrentHashMap<String, Long> DATA_VER = new ConcurrentHashMap<>();
  public static final ConcurrentHashMap<String, Long> CUST_TAB_LATEST_TS = new ConcurrentHashMap<>();

  public static final ConcurrentHashMap<Long, Long> DATA_MISSED = new ConcurrentHashMap<>();
  public static final ConcurrentHashMap<Long, Long> BUSINESS_OUTAGE = new ConcurrentHashMap<>();

  // Safety, only one special worker modify these variables currently.
  public static final ArrayList<DataLossResult> DATA_LOSS_HISTORY = new ArrayList<>();
  public static final ArrayList<FaultInfo> FAULT_INFOS = new ArrayList<>();

  public static void clear() {
    DATA_PERSISTENCE.clear();
    DATA_VER.clear();
    DATA_MISSED.clear();
    BUSINESS_OUTAGE.clear();
    DATA_LOSS_HISTORY.clear();
    FAULT_INFOS.clear();
  }

  /**
   * @param numThreads number of threads involved in the test: including the master thread.
   */
  public BenchmarkState(int numThreads) {
    startBarrier = new CountDownLatch(numThreads);
    notDoneCount = new AtomicInteger(numThreads);

    testStartNs = System.nanoTime();
  }

  // Protected by this

  public long getTestStartNs() {
    return testStartNs;
  }

  public State getState() {
    synchronized (this) {
      return state;
    }
  }

  /** Wait for all threads to call this. Returns once all the threads have entered. */
  public void blockForStart() {

    startBarrier.countDown();
    try {
      startBarrier.await();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * worker 在进入 blockForStart 之前就异常退出时，替它把启动栅栏放掉。
   *
   * <p>否则 master 会永远停在 blockForStart 的 await() 上——这也是连接故障下可能出现的一种挂死。
   */
  public void abortStartBarrier() {
    startBarrier.countDown();
  }

  /** 还没报 done 的线程数（master + workers），用于收尾超时的诊断日志。 */
  public int getNotDoneCount() {
    return notDoneCount.get();
  }

  public void startMeasure() {
    state = State.MEASURE;
  }

  public void startColdQuery() {
    state = State.COLD_QUERY;
  }

  public void startHotQuery() {
    state = State.MEASURE;
  }

  public void signalLatencyComplete() {
    state = State.LATENCY_COMPLETE;
  }

  public void ackLatencyComplete() {
    state = State.MEASURE;
  }

  public void signalError() {
    // 这里不再动 notDoneCount：每个 Worker 在 run() 的 finally 里会且只会报一次 done
    // （Worker.signalDoneOnce），重复减会让计数变负、EXIT 永远凑不到 0。
    if (state != State.EXIT) {
      state = State.ERROR;
    }
  }

  public void startCoolDown() {
    state = State.DONE;

    // The master thread must also signal that it is done
    signalDone();
  }

  /** Notify that this thread has entered the done state. */
  public int signalDone() {

    int current = notDoneCount.decrementAndGet();

    if (LOG.isDebugEnabled()) {
      LOG.debug(String.format("%d workers are not done. Waiting until they finish", current));
    }
    if (current == 0) {
      // We are the last thread to notice that we are done: wake any
      // blocked workers
      this.state = State.EXIT;
    }
    return current;
  }

  public static void addDataLossHistory(DataLossResult dataLossHistory) {
    DATA_LOSS_HISTORY.add(dataLossHistory);
  }

  @Data
  public static final class FaultInfo {
    private final String name;
    private final long startMillSeconds;
    private final long durationMillSeconds;
  }

  @Data
  public static final class DataLossResult {
    private final long checkTsMillSeconds;
    private final long lossSeconds;
  }
}

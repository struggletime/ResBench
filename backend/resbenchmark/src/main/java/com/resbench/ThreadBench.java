/*
 * Copyright 2020 by OLTPBenchmark Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 *
 */

package com.resbench;

import com.resbench.LatencyRecord.Sample;
import com.resbench.api.*;
import com.resbench.common.fault.bdserver.DBService;
import com.resbench.common.event.EventBus;
import com.resbench.event.WorkloadWarmupCompletedEvent;
import com.resbench.impl.MonitoringManager;
import com.resbench.types.State;
import com.resbench.common.config.MonitorInfo;
import com.resbench.util.StringUtil;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.apache.commons.collections4.map.ListOrderedMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ThreadBench implements Thread.UncaughtExceptionHandler {
  private static final Logger LOG = LoggerFactory.getLogger(ThreadBench.class);

  // Determines how long (in ms) to wait until monitoring thread rejoins the
  // main thread.
  private static final int MONITOR_REJOIN_TIME = 60000;

  // 收尾阶段（TERMINATE）的最长等待：worker 报完 done 后主线程才能安全退出。
  // 连接被打断时曾有 worker 直接死掉、没报 done，main 在 phase==null 的忙等里
  // 空转 100% CPU 永不退出（2026-10-31 19:33 的 03-net-loss 轮）。到点强制收尾，
  // 用已采集的数据出报告，而不是无限等。
  private static final long TERMINATE_TIMEOUT_NS = TimeUnit.SECONDS.toNanos(120);
  // 收尾等待期间的让出间隔，避免纯自旋。
  private static final long TERMINATE_PARK_NS = TimeUnit.MILLISECONDS.toNanos(10);
  // finalizeWorkers 的总等待上限：不再按每个 worker 60 秒叠加。
  private static final long FINALIZE_TIMEOUT_NS = TimeUnit.SECONDS.toNanos(120);

  private final BenchmarkState testState;

  private final List<? extends Worker<? extends BenchmarkModule>> workers;

  private final ArrayList<Thread> workerThreads;

  private final WorkloadConfiguration workConf;

  private final ArrayList<LatencyRecord.Sample> samples = new ArrayList<>();

  private final MonitorInfo monitorInfo;

  // Fault injection params
  private long faultMin;

  private boolean faultInjected = false;

  private long faultInjectTime = 0;

  private long recoveryTime = 0;

  private FaultState faultList;

  private MonitoringManager monitoringManager = null;

  private boolean faultStarted = false;

  private final EventBus eventBus;

  private ThreadBench(
      List<? extends Worker<? extends BenchmarkModule>> workers,
      WorkloadConfiguration workConf,
      MonitoringManager monitoringManager, EventBus eventBus) {
    this.workers = workers;
    this.workConf = workConf;
    this.workerThreads = new ArrayList<>(workers.size());
    this.monitorInfo = monitoringManager.getMonitorInfo();
    this.monitoringManager = monitoringManager;
    this.testState = new BenchmarkState(workers.size() + 1);
    this.eventBus = eventBus;
  }

  public static Results runRateLimitedBenchmark(
          List<Worker<? extends BenchmarkModule>> workers,
          WorkloadConfiguration workConf,
          MonitoringManager monitoringManager, EventBus eventBus) {
    ThreadBench bench = new ThreadBench(workers, workConf, monitoringManager, eventBus);
    return bench.runRateLimitedMultiPhase();
  }

  private void createWorkerThreads() {

    for (Worker<?> worker : workers) {
      worker.initializeState();
//      Thread thread = new Thread(worker);
//      thread.setUncaughtExceptionHandler(this);
//      thread.start();
      Thread thread = Thread.ofVirtual().name("resbench-worker",1).uncaughtExceptionHandler(this).start(worker);
      this.workerThreads.add(thread);
    }
  }

  private void interruptWorkers() {
    for (Worker<?> worker : workers) {
      worker.cancelStatement();
    }
  }

  private int finalizeWorkers(ArrayList<Thread> workerThreads) throws InterruptedException {

    int requests = 0;

    new WatchDogThread().start();

    // 总等待上限固定，不再对每个 worker 各等 60 秒（200 个 worker 会叠成 3 小时）。
    long deadlineNs = System.nanoTime() + FINALIZE_TIMEOUT_NS;
    for (int i = 0; i < workerThreads.size(); ++i) {
      long remainMs = TimeUnit.NANOSECONDS.toMillis(deadlineNs - System.nanoTime());
      if (remainMs > 0) {
        workerThreads.get(i).join(Math.min(60000L, remainMs));
      }

      requests += workers.get(i).getRequests();

      LOG.debug("threadbench calling teardown");

      workers.get(i).tearDown();
    }

    // 强制收尾路径下可能还有线程没退：中断一次，给一个很短的窗口，然后继续写归档。
    int alive = 0;
    for (Thread t : workerThreads) {
      if (t.isAlive()) {
        alive++;
        t.interrupt();
      }
    }
    if (alive > 0) {
      LOG.error("{} 个 worker 线程在收尾阶段仍未退出（已发送中断），继续用已采集的数据生成结果。", alive);
      for (Thread t : workerThreads) {
        if (t.isAlive()) {
          t.join(2000);
        }
      }
    }

    return requests;
  }

  private Results runRateLimitedMultiPhase() {
    boolean errorsThrown = false;
//    List<WorkloadState> workStates = new ArrayList<>();

    this.workConf.initializeState(testState);
    WorkloadState workState = this.workConf.getWorkloadState();

    this.createWorkerThreads();

    // long measureStart = start;
    Phase phase = null;

    // used to determine the longest sleep interval
    double lowestRate = Double.MAX_VALUE;


      workState.switchToNextPhase();
      phase = workState.getCurrentPhase();
      LOG.info(phase.currentPhaseString());
      if (phase.getRate() < lowestRate) {
        lowestRate = phase.getRate();
      }
    // Change testState to cold query if execution is serial, since we don't
    // have a warm-up phase for serial execution but execute a cold and a
    // measured query in sequence.
    if (phase != null && phase.isLatencyRun()) {
      synchronized (testState) {
        testState.startColdQuery();
      }
    }

    long startTs = System.currentTimeMillis();
    long start = System.nanoTime();
    long warmupStart = System.nanoTime();
    long warmup = warmupStart;
    long measureEnd = -1;

    long intervalNs = getInterval(lowestRate, phase.getArrival());

    long nextInterval = start + intervalNs;
    int nextToAdd = 1;
    int rateFactor;

    boolean resetQueues = true;

    long delta = phase.getTime() * 1000000000L;
    boolean lastEntry = false;

    // Initialize the Special Workload thread
    LOG.info("Initialize the Special Workload for Data Check ...");
    ArrayList<SpecialWorkRunner> specialWorkerRunners = new ArrayList<>();

    // only one worker is enough
    specialWorkerRunners.add(new SpecialWorkRunner(this.workers.getLast()));

    for (SpecialWorkRunner r : specialWorkerRunners) {
      r.start();
    }

    // Allow workers to start work.
    testState.blockForStart();

//    WorkloadConfiguration workloadConf = workConf;
    DBService service = this.workConf.getDBService();

    // Main Loop
    long doneSinceNs = -1L;
    while (true) {
      // posting new work... and resetting the queue in case we have new
      // portion of the workload...

        if (workState.getCurrentPhase() != null) {
          rateFactor = (int) (workState.getCurrentPhase().getRate() / lowestRate);
        } else {
          rateFactor = 1;
        }
        workState.addToQueue(nextToAdd * rateFactor, resetQueues);
        DBWorkload.result_begin = System.currentTimeMillis();
      resetQueues = false;

      // Wait until the interval expires, which may be "don't wait"
      long now = System.nanoTime();
      if (phase != null) {
        warmup = warmupStart + phase.getWarmupTime() * 1000000000L;
      }
      long diff = nextInterval - now;

      // this can wake early: sleep multiple times to avoid that
      while (diff > 1000000) { // if diff is too small, the waiting interval is not necessary
        long ms = diff / 1000000;
        diff = diff % 1000000;
        try {
          Thread.sleep(ms, (int) diff);
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
        now = System.nanoTime();
        diff = nextInterval - now;
      }

      boolean phaseComplete = false;
      if (phase != null) {
        if (phase.isLatencyRun())
        // Latency runs (serial run through each query) have their own
        // state to mark completion
        {
          phaseComplete = testState.getState() == State.LATENCY_COMPLETE;
        } else {
          phaseComplete = testState.getState() == State.MEASURE && (start + delta <= now);
        }
      }

      // Go to next phase if this one is complete or enter if error was thrown
      boolean errorThrown = testState.getState() == State.ERROR;
      errorsThrown = errorsThrown || errorThrown;
      if ((phaseComplete || errorThrown) && !lastEntry) {
        // enters here after each phase of the test
        // reset the queues so that the new phase is not affected by the
        // queue of the previous one
        resetQueues = true;

        // Fetch a new Phase
        synchronized (testState) {
          if (phase.isLatencyRun()) {
            testState.ackLatencyComplete();
          }
            synchronized (workState) {
              workState.switchToNextPhase();
              faultStarted = false;
              lowestRate = Integer.MAX_VALUE;
              phase = workState.getCurrentPhase();
              interruptWorkers();
              if (phase == null && !lastEntry) {
                // Last phase
                lastEntry = true;
                testState.startCoolDown();
                measureEnd = now;
                LOG.info(
                    "{} :: Waiting for all terminals to finish ..", StringUtil.bold("TERMINATE"));
              } else if (phase != null) {
                // Reset serial execution parameters.
                if (phase.isLatencyRun()) {
                  phase.resetSerial();
                  testState.startColdQuery();
                }
                LOG.info(phase.currentPhaseString());
                if (phase.getRate() < lowestRate) {
                  lowestRate = phase.getRate();
                }
              }
            }

          if (phase != null) {
            // update frequency in which we check according to
            // wakeup
            // speed
            // intervalNs = (long) (1000000000. / (double)
            // lowestRate + 0.5);
            delta += phase.getTime() * 1000000000L;
          }
        }
      }

      // Compute the next interval
      // and how many messages to deliver
      if (phase != null) {
        intervalNs = 0;
        nextToAdd = 0;
        do {
          intervalNs += getInterval(lowestRate, phase.getArrival());
          nextToAdd++; // how many work request should add to Queue
        } while ((-diff) > intervalNs && !lastEntry);
        nextInterval += intervalNs;
      }

      // Update the test state appropriately
      State state = testState.getState();
      if (state == State.WARMUP && now >= warmup) {
        synchronized (testState) {
          if (phase != null && phase.isLatencyRun()) {
            testState.startColdQuery();
          } else {
            testState.startMeasure();
          }
          interruptWorkers();
        }
        start = now;
        LOG.info("{} :: Warmup complete, starting measurements.", StringUtil.bold("MEASURE"));
        // measureEnd = measureStart + measureSeconds * 1000000000L;

        // For serial executions, we want to do every query exactly
        // once, so we need to restart in case some of the queries
        // began during the warmup phase.
        // If we're not doing serial executions, this function has no
        // effect and is thus safe to call regardless.
        phase.resetSerial();
        this.eventBus.publish(new WorkloadWarmupCompletedEvent(
            phase,
            service,
            workConf.getMonitoringHistoryName()));
      } else if (state == State.EXIT) {
        // All threads have noticed the done, meaning all measured
        // requests have definitely finished.
        // Time to quit.
        break;
      }

      if (phase == null && state != State.EXIT) {
        // 进入收尾（DONE / ERROR 都算）后开始计时：无论状态是哪种，只要 EXIT 迟迟不来
        // 都强制结束，避免主线程永远空转。
        if (doneSinceNs < 0) {
          doneSinceNs = now;
        } else if (now - doneSinceNs > TERMINATE_TIMEOUT_NS) {
          LOG.error(
              "TERMINATE 等待超时：{} 秒内仍有 {} 个线程没有报 done，强制结束本轮（用已采集数据出报告）。",
              TimeUnit.NANOSECONDS.toSeconds(now - doneSinceNs),
              testState.getNotDoneCount());
          break;
        }
        // phase==null 时上面的限速 sleep 不会执行，这里让出 CPU，避免收尾阶段纯自旋打满核。
        LockSupport.parkNanos(TERMINATE_PARK_NS);
      }
    }

    // Stop the monitoring thread separately from cleanup all the workers so we can ignore errors
    // from these threads (including possible SQLExceptions), but not the others.
    try {
      if (this.monitoringManager != null) {
        monitoringManager.stopMonitoring();
      }

      for (SpecialWorkRunner r : specialWorkerRunners) {
        r.interrupt();
        r.join(MONITOR_REJOIN_TIME);
      }
    } catch (Exception e) {
      LOG.error(e.getMessage(), e);
    }

    try {
      int requests = finalizeWorkers(this.workerThreads);

      // Combine all the latencies together in the most disgusting way
      // possible: sorting!
      for (Worker<?> w : workers) {
        for (LatencyRecord.Sample sample : w.getLatencyRecords()) {
          samples.add(sample);
        }
      }
      Collections.sort(samples);

      // Compute stats on all the latencies
      int[] latencies = new int[samples.size()];
      for (int i = 0; i < samples.size(); ++i) {
        latencies[i] = samples.get(i).getLatencyMicrosecond();
      }
      DistributionStatistics stats = DistributionStatistics.computeStatistics(latencies);

      Results results =
          new Results(
              // If any errors were thrown during the execution, proprogate that fact to the
              // final Results state so we can exit non-zero *after* we output the results.
              errorsThrown ? State.ERROR : testState.getState(),
              startTs,
              measureEnd - start,
              requests,
              stats,
              samples);

      LOG.info(
          "results.getLatencySamples: "
              + results.getLatencySamples().size()
              + ", "
              + results.getState());

      // Compute transaction histogram
      Set<TransactionType> txnTypes = new HashSet<>();
      txnTypes.addAll(workConf.getTransTypes());
      txnTypes.remove(TransactionType.INVALID);

      results.getUnknown().putAll(txnTypes, 0);
      results.getSuccess().putAll(txnTypes, 0);
      results.getRetry().putAll(txnTypes, 0);
      results.getAbort().putAll(txnTypes, 0);
      results.getError().putAll(txnTypes, 0);
      results.getRetryDifferent().putAll(txnTypes, 0);


      for (Worker<?> w : workers) {
        // put the Histogram stats from work to results
        results.getUnknown().putHistogram(w.getTransactionUnknownHistogram());
        results.getSuccess().putHistogram(w.getTransactionSuccessHistogram());
        results.getRetry().putHistogram(w.getTransactionRetryHistogram());
        results.getAbort().putHistogram(w.getTransactionAbortHistogram());
        results.getError().putHistogram(w.getTransactionErrorHistogram());
        results.getRetryDifferent().putHistogram(w.getTransactionRetryDifferentHistogram());
      }

      return (results);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  private long getInterval(double lowestRate, Phase.Arrival arrival) {
    // TODO Auto-generated method stub
    if (arrival == Phase.Arrival.POISSON) {
      return (long) ((-Math.log(1 - Math.random()) / lowestRate) * 1000000000.);
    } else {
      // if lowestRate=1000 means the interval is 1000000ns, namely 1ms
      return (long) (1000000000. / lowestRate + 0.5);
    }
  }

  @Override
  public void uncaughtException(Thread t, Throwable e) {
    // Here we handle the case in which one of our worker threads died
    LOG.error(e.getMessage(), e);
    // We do not continue with the experiment. Instead, bypass rest of
    // phases that were left in the test and signal error state.
    // The rest of the workflow to finish the experiment remains the same,
    // and partial metrics will be reported (i.e., until failure happened).
    synchronized (testState) {
        synchronized (workConf.getWorkloadState()) {
          WorkloadState workState = workConf.getWorkloadState();
          Phase phase = workState.getCurrentPhase();
          while (phase != null) {
            workState.switchToNextPhase();
            faultStarted = false;
            phase = workState.getCurrentPhase();
          }
        }

      testState.signalError();
    }
  }

  public static final class TimeBucketIterable implements Iterable<DistributionStatistics> {
    private final Iterable<Sample> samples;

    private final int windowSizeSeconds;

    private final TransactionType transactionType;

    /**
     * @param samples
     * @param windowSizeSeconds
     * @param transactionType Allows to filter transactions by type
     */
    public TimeBucketIterable(
        Iterable<Sample> samples, int windowSizeSeconds, TransactionType transactionType) {
      this.samples = samples;
      this.windowSizeSeconds = windowSizeSeconds;
      this.transactionType = transactionType;
    }

    @Override
    public Iterator<DistributionStatistics> iterator() {
      return new TimeBucketIterator(samples.iterator(), windowSizeSeconds, transactionType);
    }
  }

  private static final class TimeBucketIterator implements Iterator<DistributionStatistics> {
    private final Iterator<Sample> samples;

    private final int windowSizeSeconds;

    private final TransactionType txType;

    private Sample sample;

    private long nextStartNanosecond;

    private DistributionStatistics next;

    /**
     * @param samples
     * @param windowSizeSeconds
     * @param txType Allows to filter transactions by type
     */
    public TimeBucketIterator(
        Iterator<LatencyRecord.Sample> samples, int windowSizeSeconds, TransactionType txType) {
      this.samples = samples;
      this.windowSizeSeconds = windowSizeSeconds;
      this.txType = txType;

      if (samples.hasNext()) {
        sample = samples.next();
        // TODO: To be totally correct, we would want this to be the
        // timestamp of the start
        // of the measurement interval. In most cases this won't matter.
        nextStartNanosecond = sample.getStartNanosecond();
        calculateNext();
      }
    }

    private void calculateNext() {

      // Collect all samples in the time window
      ArrayList<Integer> latencies = new ArrayList<>();
      long endNanoseconds = nextStartNanosecond + (windowSizeSeconds * 1000000000L);
      while (sample != null && sample.getStartNanosecond() < endNanoseconds) {

        // Check if a TX Type filter is set, in the default case,
        // INVALID TXType means all should be reported, if a filter is
        // set, only this specific transaction
        if (txType.equals(TransactionType.INVALID)
            || txType.getId() == sample.getTransactionType()) {
          latencies.add(sample.getLatencyMicrosecond());
        }

        if (samples.hasNext()) {
          sample = samples.next();
        } else {
          sample = null;
        }
      }

      // Set up the next time window

      nextStartNanosecond = endNanoseconds;

      int[] l = new int[latencies.size()];
      for (int i = 0; i < l.length; ++i) {
        l[i] = latencies.get(i);
      }

      next = DistributionStatistics.computeStatistics(l);
    }

    @Override
    public boolean hasNext() {
      return next != null;
    }

    @Override
    public DistributionStatistics next() {
      if (next == null) {
        throw new NoSuchElementException();
      }
      DistributionStatistics out = next;
      next = null;
      if (sample != null) {
        calculateNext();
      }
      return out;
    }

    @Override
    public void remove() {
      throw new UnsupportedOperationException("unsupported");
    }
  }

  private class WatchDogThread extends Thread {
    {
      this.setDaemon(true);
    }

    @Override
    public void run() {
      Map<String, Object> m = new ListOrderedMap<>();
      LOG.info("Starting WatchDogThread");
      while (true) {
        try {
          Thread.sleep(20000);
        } catch (InterruptedException ex) {
          return;
        }

        m.clear();
        int faultWorkers = 0;
        for (Thread t : workerThreads) {
          m.put(t.getName(), t.isAlive());
          if (!t.isAlive()) {
            faultWorkers++;
          }
        }
        LOG.info("Worker Thread Status:\n{}", StringUtil.formatMaps(m));
        if (faultWorkers == workerThreads.size()) {
          break;
        }
      }
    }
  }

  private class SpecialWorkRunner extends Thread {
    private Worker<?> worker;

    int interval = 1000;

    private SpecialWorkRunner(Worker<?> _worker) {
      this.worker = _worker;
    }

    @Override
    public void run() {
      LOG.info("Starting SpecialWorkRunner ...");

      while (true) {
        long start = System.nanoTime();
        try {
          this.worker.executeWorkForDataCheck(0);
          // Currently we check data miss in every interval, and store the result into array miss[].
          // If a fault starts at s_ts and ends at e_ts,
          // miss[e_ts] - miss[s_ts-1] is the data loss result caused by this fault.
          this.worker.checkDataMiss();
        } catch (Exception e) {
          LOG.error("executeWorkForDataCheck met exception: " + e.getMessage());
          e.printStackTrace();
        }

        try {
          long end = System.nanoTime();
          long execElapsed = (end - start) / 1_000_000;
          // TODO@wy need to relax the interval?
          if (execElapsed / interval > 1 / 5) {
            LOG.warn(
                "Elapsed "
                    + execElapsed
                    + "ms, it's too long for SpecialWorkRunner interval="
                    + interval);
          } else {
            Thread.sleep(interval - (end - start) / 1_000_000);
          }
        } catch (InterruptedException ex) {
          LOG.error("SpecialWorkRunner InterruptedException: " + ex.getMessage());
          return;
        }
      }
    }
  }
}

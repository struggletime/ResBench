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
import java.util.LinkedList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class is used to manage the fault occurring a single workload.
 *
 * @author liupingapple
 */
public class FaultState {
  private static final Logger LOG = LoggerFactory.getLogger(FaultState.class);

  public static final short DORMANCY = 0;
  public static final short TRIGGERED = 1;
  public static final short ONGOING = 2;
  public static final short RECOVERED = 3;

  private final BenchmarkState benchmarkState;
  private final LinkedList<SubmittedFault> faultQueue = new LinkedList<>();

  private int faultsWaiting = 0;

  @SuppressWarnings("unused") // never read
  private int faultsfaulting = 0;

  private int faultNeedSleep;

  private Phase currentPhase = null;

  public FaultState(BenchmarkState benchmarkState) {
    this.benchmarkState = benchmarkState;
  }

  /** Add a request to do fault. */
  public void addToQueue(int amount, boolean resetQueues) {
    int faultAdded = 0;

    synchronized (this) {
      if (resetQueues) {
        faultQueue.clear();
      }

      // Only use the fault queue if the phase is enabled and rate limited.
      if (currentPhase == null
          || currentPhase.isDisabled()
          || !currentPhase.isRateLimited()
          || currentPhase.isSerial()) {
        return;
      }

      // Wake up sleeping faults to deal with the new fault.
      int numToWake = Math.min(faultAdded, faultsWaiting);
      while (numToWake-- > 0) {
        this.notify();
        try {
          faultQueue.pop().execute();
        } catch (Exception e) {
          LOG.error(e.toString());
        }
      }
    }
  }

  public void signalDone() {
    int current = this.benchmarkState.signalDone();
    if (current == 0) {
      synchronized (this) {
        if (faultsWaiting > 0) {
          this.notifyAll();
        }
      }
    }
  }

  /** Called by ThreadPoolThreads when waiting for fault. */
  public SubmittedFault fetchfault() {
    synchronized (this) {
      if (currentPhase != null && currentPhase.isSerial()) {
        ++faultsWaiting;
        while (getGlobalState() == State.LATENCY_COMPLETE) {
          try {
            this.wait();
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          }
        }
        --faultsWaiting;

        if (getGlobalState() == State.EXIT || getGlobalState() == State.DONE) {
          return null;
        }

        ++faultsfaulting;
        return null; // return new SubmittedFault();
      }
    }

    // Unlimited-rate phases don't use the fault queue.
    if (currentPhase != null && !currentPhase.isRateLimited()) {
      synchronized (this) {
        ++faultsfaulting;
      }
      return null; // return new SubmittedFault();
    }

    synchronized (this) {
      // Sleep until fault is available.
      if (faultQueue.peek() == null) {
        faultsWaiting += 1;
        while (faultQueue.peek() == null) {
          if (this.benchmarkState.getState() == State.EXIT
              || this.benchmarkState.getState() == State.DONE) {
            return null;
          }

          try {
            this.wait();
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          }
        }
        faultsWaiting -= 1;
      }

      ++faultsfaulting;

      return faultQueue.remove();
    }
  }

  public void finishedFault() {
    synchronized (this) {
      --faultsfaulting;
    }
  }

  /*
   * Called by faults to ask if they should stay awake in this phase
   */
  public void stayAwake() {
    synchronized (this) {
      while (faultNeedSleep > 0) {
        faultNeedSleep--;
        try {
          this.wait();
        } catch (InterruptedException e) {
          LOG.error(e.getMessage(), e);
        }
      }
    }
  }

  /** Delegates pre-start blocking to the global state handler */
  public void blockForStart() {
    benchmarkState.blockForStart();
  }

  /**
   * Delegates a global state query to the benchmark state handler
   *
   * @return global state
   */
  public State getGlobalState() {
    return benchmarkState.getState();
  }

  public long getTestStartNs() {
    return benchmarkState.getTestStartNs();
  }
}

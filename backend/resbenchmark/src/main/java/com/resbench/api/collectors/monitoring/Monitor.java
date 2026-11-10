package com.resbench.api.collectors.monitoring;

import com.resbench.BenchmarkState;
import com.resbench.LatencyRecord;
import com.resbench.api.BenchmarkModule;
import com.resbench.api.Worker;
import com.resbench.buffer.BenchResultBuffer;
import com.resbench.pojo.dto.BenchResult;
import com.resbench.common.constant.PathConstant;
import com.resbench.common.util.FileUtil;
import com.resbench.common.config.MonitorInfo;
import com.resbench.util.StringUtil;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generic monitoring class that reports the throughput of the executing workers while the benchmark
 * is being executed.
 */
public class Monitor extends Thread {
  protected static final Logger LOG = LoggerFactory.getLogger(Monitor.class);

  protected final MonitorInfo monitorInfo;

  protected final BenchmarkState testState;

  protected final List<? extends Worker<? extends BenchmarkModule>> workers;


  protected final PrintStream ps;

  {
    this.setDaemon(false);
  }

  private final String historyName;

  Monitor(
    MonitorInfo monitorInfo,
    BenchmarkState testState,
    List<? extends Worker<? extends BenchmarkModule>> workers,
    String historyName) {
    this.monitorInfo = monitorInfo;
    this.testState = testState;
    this.workers = workers;
    this.historyName = historyName;

    //    this.webSocketClient = new WebMonitorInfo();
    //    this.webSocketClient.connect(this.monitorInfo.getMonitoringWsURL());
    try {
      String outputDir = "tmp";
      String tmpFile = "monitor.csv";
      // Delete history records
      FileUtils.deleteDirectory(new File("tmp"));
      FileUtil.makeDirIfNotExists(outputDir);
      ps = new PrintStream(FileUtil.joinPath(outputDir, tmpFile));
      initTmpFile();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private void initTmpFile() throws IOException {
    String[] header = {
      "timestamp",
      "tps",
      "avgLatencyMs",
      "maxLatencyMs",
      "minLatencyMs",
      "latencyMs5th",
      "latencyMs95th"
    };
    ps.println(StringUtil.join(",", header));
    ps.flush();
  }

  private void writeStatus(MonitorResponse response) throws Exception {
    String[] row = {
      String.valueOf(response.timestamp),
      String.valueOf(response.tps),
      String.valueOf(response.avgLatencyMs),
      String.valueOf(response.maxLatencyMs),
      String.valueOf(response.minLatencyMs),
      String.valueOf(response.latencyMs5th),
      String.valueOf(response.latencyMs95th)
    };
    ps.println(StringUtil.join(",", row));
  }

  @Override
  public void run() {
    int interval = this.monitorInfo.getMonitoringInterval();

    LOG.info("Starting MonitorThread Interval [{}ms]", interval);
    int queuePivot = 0;
    int txnPivot = 0;
    while (!Thread.currentThread().isInterrupted()) {
      long start = System.nanoTime();
      // Compute the last throughput
      long measuredRequests = 0;

      int faultId = -1; // greater than 0, odd means trigger, even means recover

      Timestamp timestamp = new Timestamp(System.currentTimeMillis());
      SimpleDateFormat simf = new SimpleDateFormat("HH:mm:ss");
      List<LatencyRecord.Sample> samples = new ArrayList<>();
      synchronized (this.testState) {
        for (Worker<?> w : this.workers) {

          measuredRequests += w.getAndResetIntervalRequests();

          // String unknownRequest = w.getTransactionUnknownHistogram().toJSONString();

          // int phase = w.getWorkloadConfiguration().getWorkloadState().getCurrentPhase().getId();
          int size = w.getLatencySamples().size();
          int i;
          for (i = 1; i <= size; i++) {
            if (i >= w.getSamplePivot()) {
              samples.add(w.getLatencySamples().get(i - 1));
            }
          }
          w.setSamplePivot(i);
        }
      }

      // 初始化默认值
      double maxLatencyMicrosecond = 0;
      double minLatencyMicrosecond = 0;
      double latencyMicrosecond_95 = 0;
      double latencyMicrosecond_5 = 0;
      double avgLatencyMicrosecond = 0;
      long sumLatencyMicrosecond = 0;
      int successTps = 0;
      if (!samples.isEmpty()) {
        samples.sort(Comparator.comparingInt(LatencyRecord.Sample::getLatencyMicrosecond));

        minLatencyMicrosecond = samples.getFirst().getLatencyMicrosecond();
        maxLatencyMicrosecond = samples.getLast().getLatencyMicrosecond();
        latencyMicrosecond_5 = percentile(samples, 0.05);
        latencyMicrosecond_95 = percentile(samples, 0.95);
        for (LatencyRecord.Sample sample : samples) {
          sumLatencyMicrosecond += sample.getLatencyMicrosecond();
          if (sample.isSuccess()) {
            successTps++;
          }
        }
        avgLatencyMicrosecond = (double) sumLatencyMicrosecond / samples.size();
      }

      try {
        MonitorResponse.MonitorResponseBuilder monitorResponseBuilder = MonitorResponse.builder();
              monitorResponseBuilder.tps((double) successTps);
        monitorResponseBuilder.timestamp(System.currentTimeMillis());
          // 纳秒转换为微秒
        monitorResponseBuilder.avgLatencyMs(BigDecimal.valueOf(avgLatencyMicrosecond).divide(BigDecimal.valueOf(1000), 2, RoundingMode.HALF_UP).doubleValue());
        monitorResponseBuilder.maxLatencyMs(BigDecimal.valueOf(maxLatencyMicrosecond).divide(BigDecimal.valueOf(1000), 2, RoundingMode.HALF_UP).doubleValue());
        monitorResponseBuilder.minLatencyMs(BigDecimal.valueOf(minLatencyMicrosecond).divide(BigDecimal.valueOf(1000), 2, RoundingMode.HALF_UP).doubleValue());
        monitorResponseBuilder.latencyMs5th(BigDecimal.valueOf(latencyMicrosecond_5).divide(BigDecimal.valueOf(1000), 2, RoundingMode.HALF_UP).doubleValue());
        monitorResponseBuilder.latencyMs95th(BigDecimal.valueOf(latencyMicrosecond_95).divide(BigDecimal.valueOf(1000), 2, RoundingMode.HALF_UP).doubleValue());
        writeStatus(monitorResponseBuilder.build());

        BenchResult benchResult =
          BenchResult.builder()
            .timestamp(timestamp)
            .faultId(faultId)
            .tps((double) successTps)
            .build();
        BenchResultBuffer.getInstance().add(benchResult);
      } catch (Exception e) {
        LOG.error(e.getMessage());
        LOG.info("re-connect to ws ...");
        //        this.webSocketClient.connect(monitorInfo.getMonitoringWsURL()); // re-connect
      }

      try {
        long end = System.nanoTime();
        long execElapsed = (end - start) / 1_000_000;
        if (execElapsed / interval > 1 / 5) {
          LOG.warn(
            "Elapsed " + execElapsed + "ms, it's too long for Monitoring interval=" + interval);
        } else {
          Thread.sleep(interval - (end - start) / 1_000_000);
        }
      } catch (InterruptedException ex) {
        // Restore interrupt flag.
        Thread.currentThread().interrupt();
      }
    }
    ps.flush();
    ps.close();
    //    this.webSocketClient.close();
  }

  private static int percentile(List<LatencyRecord.Sample> samples, double percentile) {
    int total = samples.size();
    int index = (int) Math.ceil(percentile * total) - 1;
    index = Math.min(Math.max(0, index), total - 1);
    return samples.get(index).getLatencyMicrosecond();
  }

  /**
   * Called at the end of the test to do any clean up that may be required.
   */
  public void tearDown() {
  }
}

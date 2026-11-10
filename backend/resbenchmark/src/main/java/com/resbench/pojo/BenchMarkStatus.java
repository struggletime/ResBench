package com.resbench.pojo;

import com.resbench.common.job.JobState;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class BenchMarkStatus {
  private String startTimestamp;

  private String endTimestamp;

  private boolean end = true;

  private boolean success = false;

  private String message;

  private String dbType;

  private String workloadType;

  private String workloadConfig;

  private List<String> faults;

  public BenchMarkStatus(JobState jobState){
    this.startTimestamp = jobState.getStartTimeStamp();
    this.endTimestamp = jobState.getStopTimeStamp();
    this.success = jobState.isSuccess();
    this.end = (jobState.getCurState() != JobState.State.RUNNING) ? true: false;
    if (this.end) {
      this.message = jobState.getCurState() == JobState.State.MANUAL ? "任务已经被手工停止":"任务已经被优雅停止";
    }
  }
  public void reset() {
    message = "";
    end = true;
    success = false;
    startTimestamp = "";
    endTimestamp = "";
  }

  public BenchMarkStatus reset(JobState jobState){
    this.startTimestamp = jobState.getStartTimeStamp();
    this.endTimestamp = jobState.getStopTimeStamp();
    this.success = jobState.isSuccess();
    this.end = (jobState.getCurState() != JobState.State.RUNNING) ? true: false;
    if (this.end) {
      this.message = jobState.getCurState() == JobState.State.MANUAL ? "任务已经被手工停止":"任务已经被优雅停止";
    }
    return this;
  }
}

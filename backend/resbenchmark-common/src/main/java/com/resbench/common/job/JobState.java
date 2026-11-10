package com.resbench.common.job;

import com.resbench.common.util.JSONSerializable;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONStringer;

import java.io.IOException;

public class JobState {
    // 当前状态
    private State curState;
    // 时间戳
    private String startTimeStamp;

    private String stopTimeStamp;

    // 是否成功
    private boolean isSuccess;

    public enum State {
        // 执行中
        RUNNING,
        // 正常结束
        GRACEFUL,
        // 手动结束
        MANUAL
    }

    public JobState() {
        this.curState = State.RUNNING;
        this.startTimeStamp = String.valueOf(System.currentTimeMillis());
        this.stopTimeStamp = "";
        this.isSuccess = false;
    }

    public JobState(State curState, String timeStamp, boolean isSuccess) {
        this.curState = curState;
        this.startTimeStamp = timeStamp;
        this.stopTimeStamp = "";
        this.isSuccess = isSuccess;
    }

    public State getCurState() {
        return curState;
    }

    public void setCurState(State curState) {
        this.curState = curState;
    }

    public String getStartTimeStamp() {
        return startTimeStamp;
    }

    public void setStartTimeStamp(String startTimeStamp) {
        this.startTimeStamp = startTimeStamp;
    }

    public boolean isSuccess() {
        return isSuccess;
    }

    public void setSuccess(boolean success) {
        isSuccess = success;
    }

    public String getStopTimeStamp() {
        return stopTimeStamp;
    }

    public void setStopTimeStamp(String stopTimeStamp) {
        this.stopTimeStamp = stopTimeStamp;
    }
}

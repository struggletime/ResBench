import { createSlice } from '@reduxjs/toolkit';

const testRunSlice = createSlice({
  name: 'testRun',
  initialState: {
    hasTestStarted: false,
    currentRunId: '',
    lastRunId: '',
    currentContainerName: '',
    // 当前测试的数据库类型（mysql/postgresql/opengauss...）。监控会话要带上它，
    // manager 才能给 db.* 指标选对数据源（og_* 还是 mysql_*）。
    currentDbType: '',
    manuallyStopped: false,
  },
  reducers: {
    setRunState: (state, action) => {
      state.hasTestStarted = Boolean(action.payload?.hasTestStarted);
      state.currentRunId = action.payload?.currentRunId || '';
      state.currentContainerName = action.payload?.containerName || '';
      state.currentDbType = action.payload?.dbType || '';
      state.manuallyStopped = false;
      if (state.currentRunId) {
        state.lastRunId = state.currentRunId;
      }
    },
    finishRunState: (state, action) => {
      const runId = action.payload?.runId || state.currentRunId || state.lastRunId;
      state.hasTestStarted = false;
      state.currentRunId = runId || '';
      state.lastRunId = runId || state.lastRunId;
    },
    setManuallyStopped: (state, action) => {
      state.manuallyStopped = Boolean(action.payload);
    },
    clearRunState: (state) => {
      state.hasTestStarted = false;
      state.currentRunId = '';
      state.currentContainerName = '';
      state.currentDbType = '';
      state.manuallyStopped = false;
    },
  },
});

export const { setRunState, finishRunState, setManuallyStopped, clearRunState } = testRunSlice.actions;
export default testRunSlice.reducer;

import { createSlice } from '@reduxjs/toolkit';

const monitorSlice = createSlice({
  name: 'monitor',
  initialState: {
    data: [],
    suiteState: 'unknown',
    runId: '',
    faultRegions: [],
    faultInjectInfo: [],
  },
  reducers: {
    setMonitorData: (state, action) => {
      const newRunId = action.payload.runId;
      if (newRunId && newRunId !== state.runId) {
        state.faultRegions = [];
        state.faultInjectInfo = [];
      }
      state.data = action.payload.data || [];
      state.suiteState = action.payload.suiteState || 'unknown';
      state.runId = newRunId || state.runId;
    },
    setFaultRegions: (state, action) => {
      state.faultRegions = Array.isArray(action.payload) ? action.payload : [];
    },
    setFaultInjectInfo: (state, action) => {
      state.faultInjectInfo = Array.isArray(action.payload) ? action.payload : [];
    },
    clearMonitorData: (state) => {
      state.data = [];
      state.suiteState = 'unknown';
      state.runId = '';
      state.faultRegions = [];
      state.faultInjectInfo = [];
    },
  },
});

export const { setMonitorData, setFaultRegions, setFaultInjectInfo, clearMonitorData } = monitorSlice.actions;
export default monitorSlice.reducer;

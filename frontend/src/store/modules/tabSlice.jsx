import { createSlice } from '@reduxjs/toolkit';

const topTabDefaultSubMap = {
  config: 'database',
  results: 'history',
};

const subTabTopMap = {
  database: 'config',
  workload: 'config',
  fault: 'config',
  monitoring: 'config',
  history: 'results',
  compare: 'results',
};

const tabSlice = createSlice({
  name: 'tab',
  initialState: {
    topTab: 'config',
    activeTab: 'database',
    topTabLastSubTab: {
      config: 'database',
      results: 'history',
    },
  },
  reducers: {
    setTopTab: (state, action) => {
      const nextTopTab = action.payload;
      state.topTab = nextTopTab;
      state.activeTab =
        state.topTabLastSubTab[nextTopTab] ||
        topTabDefaultSubMap[nextTopTab] ||
        topTabDefaultSubMap.config;
    },
    setSubTab: (state, action) => {
      const nextSubTab = action.payload;
      const nextTopTab = subTabTopMap[nextSubTab] || 'config';

      state.activeTab = nextSubTab;
      state.topTab = nextTopTab;
      state.topTabLastSubTab[nextTopTab] = nextSubTab;
    },
    setActiveTab: (state, action) => {
      // Backward compatibility: keep old API while maintaining top-level state.
      const nextSubTab = action.payload;
      const nextTopTab = subTabTopMap[nextSubTab] || 'config';

      state.activeTab = nextSubTab;
      state.topTab = nextTopTab;
      state.topTabLastSubTab[nextTopTab] = nextSubTab;
    },
  },
});

export const { setTopTab, setSubTab, setActiveTab } = tabSlice.actions;
export default tabSlice.reducer;
// src/store/index.js
import { configureStore } from '@reduxjs/toolkit';
import tabReducer from './modules/tabSlice';
import databaseReducer from './modules/databaseSlice';
import workloadReducer from "./modules/workloadSlice";
import faultSilce from "./modules/faultSlice";
import historySlice from "./modules/historySlice";
import testRunReducer from "./modules/testRunSlice";
import monitorReducer from "./modules/monitorSlice";
import observabilityReducer from "./modules/observabilitySlice";
import { loadPersistedState, persistState } from './persistence';

const persisted = loadPersistedState();
const observabilityInitialState = observabilityReducer(undefined, { type: '@@INIT' });

export const store = configureStore({
  reducer: {
    tab: tabReducer,
    workload: workloadReducer,
    database: databaseReducer,
    faults: faultSilce,
    history: historySlice,
    testRun: testRunReducer,
    monitor: monitorReducer,
    observability: observabilityReducer,
  },
  preloadedState: persisted ? {
    database: persisted.database,
    workload: persisted.workload,
    faults: persisted.faults,
    testRun: persisted.testRun,
    observability: persisted.observability ? {
      ...observabilityInitialState,
      ...persisted.observability,
    } : undefined,
  } : undefined,
});

let saveTimer = null;
store.subscribe(() => {
  if (saveTimer) clearTimeout(saveTimer);
  saveTimer = setTimeout(() => {
    persistState(store.getState());
  }, 500);
});
